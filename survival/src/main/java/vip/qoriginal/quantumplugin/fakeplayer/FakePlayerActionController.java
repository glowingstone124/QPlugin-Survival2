package vip.qoriginal.quantumplugin.fakeplayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class FakePlayerActionController {
    enum Action {
        ATTACK,
        USE
    }

    enum Mode {
        ONCE,
        START,
        STOP
    }

    enum Target {
        ENTITY,
        BLOCK,
        AIR
    }

    private static final int USE_COOLDOWN_TICKS = 4;
    private static final int BLOCK_BREAK_COOLDOWN_TICKS = 5;

    private final Map<UUID, ControlledPlayer> controlledPlayers = new ConcurrentHashMap<>();
    private BukkitTask task;
    private int sequence;

    void startTicker(Plugin plugin) {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    Target configure(ServerPlayer player, Action action, Mode mode) {
        return switch (mode) {
            case ONCE -> performOnce(player, action);
            case START -> {
                ControlledPlayer controlled = controlledPlayers.computeIfAbsent(player.getUUID(), ignored -> new ControlledPlayer(player));
                controlled.actions.add(action);
                yield action == Action.ATTACK ? attack(controlled, true) : use(controlled, true);
            }
            case STOP -> {
                stop(player, action);
                yield Target.AIR;
            }
        };
    }

    void clear(ServerPlayer player) {
        ControlledPlayer controlled = controlledPlayers.remove(player.getUUID());
        if (controlled != null) {
            abortBlockAttack(controlled);
            releaseUse(player);
        }
    }

    void shutdown() {
        for (ControlledPlayer controlled : controlledPlayers.values()) {
            abortBlockAttack(controlled);
            releaseUse(controlled.player);
        }
        controlledPlayers.clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        controlledPlayers.values().removeIf(controlled -> {
            ServerPlayer player = controlled.player;
            if (player.isRemoved() || !player.isAlive()) {
                abortBlockAttack(controlled);
                releaseUse(player);
                return true;
            }

            if (controlled.useCooldown > 0) {
                controlled.useCooldown--;
            }
            if (controlled.blockBreakCooldown > 0) {
                controlled.blockBreakCooldown--;
            }
            if (controlled.actions.contains(Action.USE)) {
                use(controlled, true);
            }
            if (controlled.actions.contains(Action.ATTACK)) {
                attack(controlled, true);
            } else {
                abortBlockAttack(controlled);
            }
            return controlled.actions.isEmpty();
        });
    }

    private Target performOnce(ServerPlayer player, Action action) {
        ControlledPlayer controlled = new ControlledPlayer(player);
        Target target = action == Action.ATTACK ? attack(controlled, false) : use(controlled, false);
        if (action == Action.ATTACK) {
            abortBlockAttack(controlled);
        } else {
            releaseUse(player);
        }
        return target;
    }

    private void stop(ServerPlayer player, Action action) {
        ControlledPlayer controlled = controlledPlayers.get(player.getUUID());
        if (controlled == null) {
            if (action == Action.USE) {
                releaseUse(player);
            }
            return;
        }

        controlled.actions.remove(action);
        if (action == Action.ATTACK) {
            abortBlockAttack(controlled);
        } else {
            releaseUse(player);
            controlled.useCooldown = 0;
        }
        if (controlled.actions.isEmpty()) {
            controlledPlayers.remove(player.getUUID(), controlled);
        }
    }

    private Target attack(ControlledPlayer controlled, boolean continuous) {
        ServerPlayer player = controlled.player;
        RayTraceResult hit = target(player);
        if (hit != null && hit.getHitEntity() instanceof Entity entity) {
            abortBlockAttack(controlled);
            net.minecraft.world.entity.Entity handle = ((CraftEntity) entity).getHandle();
            player.connection.handleAttack(new ServerboundAttackPacket(handle.getId()));
            player.swing(InteractionHand.MAIN_HAND);
            return Target.ENTITY;
        }

        if (hit != null && hit.getHitBlock() != null && hit.getHitBlockFace() != null) {
            attackBlock(controlled, hit, continuous);
            player.swing(InteractionHand.MAIN_HAND);
            return Target.BLOCK;
        }

        abortBlockAttack(controlled);
        player.swing(InteractionHand.MAIN_HAND);
        player.resetLastActionTime();
        return Target.AIR;
    }

    private void attackBlock(ControlledPlayer controlled, RayTraceResult hit, boolean continuous) {
        ServerPlayer player = controlled.player;
        BlockPos pos = blockPos(hit);
        Direction direction = direction(hit.getHitBlockFace());
        if (!continuous) {
            blockAction(player, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, direction);
            blockAction(player, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pos, direction);
            return;
        }
        if (controlled.blockBreakCooldown > 0) {
            return;
        }

        if (!pos.equals(controlled.breakingBlock)) {
            abortBlockAttack(controlled);
            controlled.breakingBlock = pos;
            controlled.breakingDirection = direction;
            controlled.breakingTicks = 0;
            blockAction(player, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, direction);
        }

        BlockState state = player.level().getBlockState(pos);
        if (state.isAir()) {
            controlled.breakingBlock = null;
            controlled.breakingTicks = 0;
            controlled.blockBreakCooldown = BLOCK_BREAK_COOLDOWN_TICKS;
            return;
        }

        controlled.breakingTicks++;
        float progressPerTick = state.getDestroyProgress(player, player.level(), pos);
        if (progressPerTick * controlled.breakingTicks >= 1.0F) {
            blockAction(player, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, direction);
            controlled.breakingBlock = null;
            controlled.breakingTicks = 0;
            controlled.blockBreakCooldown = BLOCK_BREAK_COOLDOWN_TICKS;
        }
    }

    private Target use(ControlledPlayer controlled, boolean continuous) {
        ServerPlayer player = controlled.player;
        if (continuous && (player.isUsingItem() || controlled.useCooldown > 0)) {
            return Target.AIR;
        }

        RayTraceResult hit = target(player);
        if (hit != null && hit.getHitEntity() instanceof Entity entity) {
            net.minecraft.world.entity.Entity handle = ((CraftEntity) entity).getHandle();
            Vec3 hitPosition = vec3(hit.getHitPosition());
            Vec3 relative = hitPosition.subtract(handle.position());
            player.connection.handleInteract(new ServerboundInteractPacket(
                    handle.getId(),
                    InteractionHand.MAIN_HAND,
                    relative,
                    player.isShiftKeyDown()
            ));
            controlled.useCooldown = USE_COOLDOWN_TICKS;
            return Target.ENTITY;
        }

        if (hit != null && hit.getHitBlock() != null && hit.getHitBlockFace() != null) {
            BlockHitResult blockHit = new BlockHitResult(
                    vec3(hit.getHitPosition()),
                    direction(hit.getHitBlockFace()),
                    blockPos(hit),
                    false
            );
            ServerboundUseItemOnPacket packet = new ServerboundUseItemOnPacket(
                    InteractionHand.MAIN_HAND,
                    blockHit,
                    nextSequence()
            );
            packet.timestamp = System.currentTimeMillis();
            player.connection.handleUseItemOn(packet);
            controlled.useCooldown = USE_COOLDOWN_TICKS;
            return Target.BLOCK;
        }

        ServerboundUseItemPacket packet = new ServerboundUseItemPacket(
                InteractionHand.MAIN_HAND,
                nextSequence(),
                player.getYRot(),
                player.getXRot()
        );
        packet.timestamp = System.currentTimeMillis();
        player.connection.handleUseItem(packet);
        controlled.useCooldown = USE_COOLDOWN_TICKS;
        return Target.AIR;
    }

    private RayTraceResult target(ServerPlayer player) {
        Player bukkitPlayer = player.getBukkitEntity();
        Location eye = bukkitPlayer.getEyeLocation();
        Vector direction = eye.getDirection();
        double reach = Math.max(player.blockInteractionRange(), player.entityInteractionRange()) + 0.5D;
        return bukkitPlayer.getWorld().rayTrace(
                eye,
                direction,
                reach,
                FluidCollisionMode.NEVER,
                false,
                0.1D,
                entity -> validTarget(bukkitPlayer, entity)
        );
    }

    private boolean validTarget(Player player, Entity entity) {
        return entity != player
                && entity.isValid()
                && !entity.isDead()
                && !(entity instanceof Item)
                && !(entity instanceof ExperienceOrb)
                && !(entity instanceof AbstractArrow);
    }

    private void abortBlockAttack(ControlledPlayer controlled) {
        if (controlled.breakingBlock == null) {
            return;
        }
        blockAction(
                controlled.player,
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                controlled.breakingBlock,
                controlled.breakingDirection
        );
        controlled.breakingBlock = null;
        controlled.breakingTicks = 0;
    }

    private void releaseUse(ServerPlayer player) {
        if (player.isUsingItem()) {
            player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                    BlockPos.ZERO,
                    Direction.DOWN
            ));
        }
    }

    private void blockAction(
            ServerPlayer player,
            ServerboundPlayerActionPacket.Action action,
            BlockPos pos,
            Direction direction
    ) {
        player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(action, pos, direction));
    }

    private int nextSequence() {
        return ++sequence;
    }

    private BlockPos blockPos(RayTraceResult hit) {
        org.bukkit.block.Block block = hit.getHitBlock();
        return new BlockPos(block.getX(), block.getY(), block.getZ());
    }

    private Direction direction(BlockFace face) {
        return Direction.valueOf(face.name());
    }

    private Vec3 vec3(Vector vector) {
        return new Vec3(vector.getX(), vector.getY(), vector.getZ());
    }

    private static final class ControlledPlayer {
        private final ServerPlayer player;
        private final EnumSet<Action> actions = EnumSet.noneOf(Action.class);
        private BlockPos breakingBlock;
        private Direction breakingDirection = Direction.DOWN;
        private int breakingTicks;
        private int blockBreakCooldown;
        private int useCooldown;

        private ControlledPlayer(ServerPlayer player) {
            this.player = player;
        }
    }
}
