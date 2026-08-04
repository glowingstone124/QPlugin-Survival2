package vip.qoriginal.quantumplugin.metro;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.OldMinecartBehavior;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;

/**
 * Minecraft 26.2's old minecart behavior with its internal 2 block/tick cap removed.
 *
 * <p>The server still uses the old minecart network protocol. Bukkit's maxSpeed is
 * retained as the final movement limit, so this does not require the experimental
 * minecart data pack on either the server or the client.</p>
 */
final class UnlockedOldMinecartBehavior extends OldMinecartBehavior {
    private static final double VANILLA_POWERED_RAIL_BOOST = 0.06D;
    private static final Field PURPUR_CONFIG_FIELD = findField(ServerLevel.class, "purpurConfig");
    private static final Field PURPUR_RAIL_BOOST_FIELD = findPurpurRailBoostField();

    UnlockedOldMinecartBehavior(AbstractMinecart minecart) {
        super(minecart);
    }

    @Override
    public void moveAlongTrack(ServerLevel level) {
        BlockPos pos = minecart.getCurrentBlockPosOrRailBelow();
        BlockState state = level().getBlockState(pos);
        minecart.resetFallDistance();
        double x = minecart.getX();
        double y = minecart.getY();
        double z = minecart.getZ();
        Vec3 oldPos = getPos(x, y, z);
        y = pos.getY();
        boolean poweredRail = false;
        boolean brakeRail = false;
        if (state.is(Blocks.POWERED_RAIL)) {
            poweredRail = state.getValue(PoweredRailBlock.POWERED);
            brakeRail = !poweredRail;
        }

        double slopeAcceleration = 0.0078125D;
        if (minecart.isInWater()) {
            slopeAcceleration *= 0.2D;
        }

        Vec3 movement = getDeltaMovement();
        RailShape shape = state.getValue(((BaseRailBlock) state.getBlock()).getShapeProperty());
        switch (shape) {
            case ASCENDING_EAST -> {
                setDeltaMovement(movement.add(-slopeAcceleration, 0.0D, 0.0D));
                y++;
            }
            case ASCENDING_WEST -> {
                setDeltaMovement(movement.add(slopeAcceleration, 0.0D, 0.0D));
                y++;
            }
            case ASCENDING_NORTH -> {
                setDeltaMovement(movement.add(0.0D, 0.0D, slopeAcceleration));
                y++;
            }
            case ASCENDING_SOUTH -> {
                setDeltaMovement(movement.add(0.0D, 0.0D, -slopeAcceleration));
                y++;
            }
            default -> {
            }
        }

        movement = getDeltaMovement();
        Pair<Vec3i, Vec3i> exits = AbstractMinecart.exits(shape);
        Vec3i exit0 = exits.getFirst();
        Vec3i exit1 = exits.getSecond();
        double xDirection = exit1.getX() - exit0.getX();
        double zDirection = exit1.getZ() - exit0.getZ();
        double directionLength = Math.sqrt(xDirection * xDirection + zDirection * zDirection);
        if (movement.x * xDirection + movement.z * zDirection < 0.0D) {
            xDirection = -xDirection;
            zDirection = -zDirection;
        }

        // 26.2 OldMinecartBehavior uses Math.min(2.0, horizontalDistance()) here.
        // maxSpeed is applied below, so the extra hard cap is both redundant and
        // the cause of the exact 108 km/h passenger limit.
        double speed = movement.horizontalDistance();
        movement = new Vec3(speed * xDirection / directionLength, movement.y, speed * zDirection / directionLength);
        setDeltaMovement(movement);

        Entity controllingPassenger = minecart.getFirstPassenger();
        Vec3 moveIntent = controllingPassenger instanceof ServerPlayer player
                ? player.getLastClientMoveIntent()
                : Vec3.ZERO;
        if (controllingPassenger instanceof Player && moveIntent.lengthSqr() > 0.0D) {
            Vec3 riderMovement = moveIntent.normalize();
            double ownSpeed = getDeltaMovement().horizontalDistanceSqr();
            if (riderMovement.lengthSqr() > 0.0D && ownSpeed < 0.01D) {
                setDeltaMovement(getDeltaMovement().add(moveIntent.x * 0.001D, 0.0D, moveIntent.z * 0.001D));
                brakeRail = false;
            }
        }

        if (brakeRail) {
            double speedLength = getDeltaMovement().horizontalDistance();
            if (speedLength < 0.03D) {
                setDeltaMovement(Vec3.ZERO);
            } else {
                setDeltaMovement(getDeltaMovement().multiply(0.5D, 0.0D, 0.5D));
            }
        }

        double x0 = pos.getX() + 0.5D + exit0.getX() * 0.5D;
        double z0 = pos.getZ() + 0.5D + exit0.getZ() * 0.5D;
        double x1 = pos.getX() + 0.5D + exit1.getX() * 0.5D;
        double z1 = pos.getZ() + 0.5D + exit1.getZ() * 0.5D;
        xDirection = x1 - x0;
        zDirection = z1 - z0;
        double progress;
        if (xDirection == 0.0D) {
            progress = z - pos.getZ();
        } else if (zDirection == 0.0D) {
            progress = x - pos.getX();
        } else {
            double relativeX = x - x0;
            double relativeZ = z - z0;
            progress = (relativeX * xDirection + relativeZ * zDirection) * 2.0D;
        }

        x = x0 + xDirection * progress;
        z = z0 + zDirection * progress;
        setPos(x, y, z);

        double maxSpeed = getMaxSpeed(level);
        movement = getDeltaMovement();
        // The old behavior also multiplies passenger carts by 0.75 here. That
        // would turn a configured 400 km/h limit into 300 km/h after removing
        // the first cap, so maxSpeed is the sole limit in this patched behavior.
        minecart.move(MoverType.SELF, new Vec3(
                Mth.clamp(movement.x, -maxSpeed, maxSpeed),
                0.0D,
                Mth.clamp(movement.z, -maxSpeed, maxSpeed)
        ));

        if (exit0.getY() != 0
                && Mth.floor(minecart.getX()) - pos.getX() == exit0.getX()
                && Mth.floor(minecart.getZ()) - pos.getZ() == exit0.getZ()) {
            setPos(minecart.getX(), minecart.getY() + exit0.getY(), minecart.getZ());
        } else if (exit1.getY() != 0
                && Mth.floor(minecart.getX()) - pos.getX() == exit1.getX()
                && Mth.floor(minecart.getZ()) - pos.getZ() == exit1.getZ()) {
            setPos(minecart.getX(), minecart.getY() + exit1.getY(), minecart.getZ());
        }

        applyNaturalSlowdown();
        Vec3 newPos = getPos(minecart.getX(), minecart.getY(), minecart.getZ());
        if (newPos != null && oldPos != null) {
            double slopeSpeed = (oldPos.y - newPos.y) * 0.05D;
            Vec3 currentMovement = getDeltaMovement();
            double currentSpeed = currentMovement.horizontalDistance();
            if (currentSpeed > 0.0D) {
                double factor = (currentSpeed + slopeSpeed) / currentSpeed;
                setDeltaMovement(currentMovement.multiply(factor, 1.0D, factor));
            }

            setPos(minecart.getX(), newPos.y, minecart.getZ());
        }

        int newBlockX = Mth.floor(minecart.getX());
        int newBlockZ = Mth.floor(minecart.getZ());
        if (newBlockX != pos.getX() || newBlockZ != pos.getZ()) {
            Vec3 currentMovement = getDeltaMovement();
            double currentSpeed = currentMovement.horizontalDistance();
            setDeltaMovement(
                    currentSpeed * (newBlockX - pos.getX()),
                    currentMovement.y,
                    currentSpeed * (newBlockZ - pos.getZ())
            );
        }

        if (poweredRail) {
            Vec3 currentMovement = getDeltaMovement();
            double currentSpeed = currentMovement.horizontalDistance();
            if (currentSpeed > 0.01D) {
                double boost = poweredRailBoost(level);
                setDeltaMovement(currentMovement.add(
                        currentMovement.x / currentSpeed * boost,
                        0.0D,
                        currentMovement.z / currentSpeed * boost
                ));
            } else {
                double dx = currentMovement.x;
                double dz = currentMovement.z;
                if (shape == RailShape.EAST_WEST) {
                    if (minecart.isRedstoneConductor(pos.west())) {
                        dx = 0.02D;
                    } else if (minecart.isRedstoneConductor(pos.east())) {
                        dx = -0.02D;
                    }
                } else if (shape == RailShape.NORTH_SOUTH) {
                    if (minecart.isRedstoneConductor(pos.north())) {
                        dz = 0.02D;
                    } else if (minecart.isRedstoneConductor(pos.south())) {
                        dz = -0.02D;
                    }
                } else {
                    return;
                }

                setDeltaMovement(dx, currentMovement.y, dz);
            }
        }
    }

    private void applyNaturalSlowdown() {
        Vec3 movement = getDeltaMovement().multiply(getSlowdownFactor(), 0.0D, getSlowdownFactor());
        if (minecart.isInWater()) {
            movement = movement.scale(0.95D);
        }
        setDeltaMovement(movement);
    }

    private static double poweredRailBoost(ServerLevel level) {
        if (PURPUR_CONFIG_FIELD == null || PURPUR_RAIL_BOOST_FIELD == null) {
            return VANILLA_POWERED_RAIL_BOOST;
        }
        try {
            return PURPUR_RAIL_BOOST_FIELD.getDouble(PURPUR_CONFIG_FIELD.get(level));
        } catch (IllegalAccessException ignored) {
            return VANILLA_POWERED_RAIL_BOOST;
        }
    }

    private static Field findPurpurRailBoostField() {
        if (PURPUR_CONFIG_FIELD == null) {
            return null;
        }
        try {
            return findField(PURPUR_CONFIG_FIELD.getType(), "poweredRailBoostModifier");
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> owner, String name) {
        try {
            Field field = owner.getField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
