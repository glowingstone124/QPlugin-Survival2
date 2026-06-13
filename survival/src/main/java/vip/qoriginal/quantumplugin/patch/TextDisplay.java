package vip.qoriginal.quantumplugin.patch;

import net.kyori.adventure.text.Component;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

public class TextDisplay {
    private static final int MAX_DISTANCE = 50;
    private static final int LINE_WIDTH = 200;
    private static final byte TEXT_OPACITY = (byte) 255;
    private static final double SURFACE_OFFSET = 0.02D;

    public void exec(Player player, String text) {
        String displayText = text.trim();
        if (displayText.isEmpty()) {
            player.sendMessage("请提供要显示的文本。");
            return;
        }

        RayTraceResult target = player.rayTraceBlocks(MAX_DISTANCE, FluidCollisionMode.NEVER);
        if (target == null || target.getHitPosition() == null) {
            player.sendMessage("No target block in sight.");
            return;
        }

        BlockFace hitFace = target.getHitBlockFace();
        Location targetLocation = target.getHitPosition().toLocation(player.getWorld());
        if (hitFace != null) {
            targetLocation.add(hitFace.getDirection().multiply(SURFACE_OFFSET));
        }

        Direction direction = getDisplayDirection(player, hitFace);
        boolean wallMounted = isWallFace(hitFace);
        createTextDisplay(targetLocation, displayText, direction, wallMounted);
        player.sendMessage("Text display created at: " + targetLocation + " facing " + direction);
    }

    private enum Direction {
        NORTH, SOUTH, EAST, WEST
    }

    private Direction getDisplayDirection(Player player, BlockFace hitFace) {
        if (hitFace == BlockFace.NORTH) return Direction.NORTH;
        if (hitFace == BlockFace.SOUTH) return Direction.SOUTH;
        if (hitFace == BlockFace.EAST) return Direction.EAST;
        if (hitFace == BlockFace.WEST) return Direction.WEST;

        Vector playerDirection = player.getLocation().getDirection();
        double northAngle = playerDirection.angle(new Vector(0, 0, -1));
        double southAngle = playerDirection.angle(new Vector(0, 0, 1));
        double eastAngle = playerDirection.angle(new Vector(1, 0, 0));
        double westAngle = playerDirection.angle(new Vector(-1, 0, 0));

        Direction bestDirection = Direction.NORTH;
        double minAngle = northAngle;

        if (southAngle < minAngle) {
            bestDirection = Direction.SOUTH;
            minAngle = southAngle;
        }
        if (eastAngle < minAngle) {
            bestDirection = Direction.EAST;
            minAngle = eastAngle;
        }
        if (westAngle < minAngle) {
            bestDirection = Direction.WEST;
            minAngle = westAngle;
        }

        return bestDirection;
    }

    private boolean isWallFace(BlockFace face) {
        return face == BlockFace.NORTH || face == BlockFace.SOUTH || face == BlockFace.EAST || face == BlockFace.WEST;
    }

    private void createTextDisplay(Location location, String text, Direction direction, boolean wallMounted) {
        org.bukkit.entity.TextDisplay display = location.getWorld().spawn(location, org.bukkit.entity.TextDisplay.class);
        display.text(Component.text(text));
        display.setLineWidth(LINE_WIDTH);
        display.setTextOpacity(TEXT_OPACITY);
        display.setShadowed(false);
        display.setSeeThrough(false);
        display.setAlignment(org.bukkit.entity.TextDisplay.TextAlignment.CENTER);
        display.setTransformationMatrix(createTransformation(direction, wallMounted, location.getYaw()));
    }

    private Matrix4f createTransformation(Direction direction, boolean wallMounted, float yaw) {
        if (wallMounted) {
            return switch (direction) {
                case NORTH -> matrix(4, 0, 0, 0, 0, 0, 4, 0, 0, 4, 0, 0, 0, 0, 0, 1);
                case SOUTH -> matrix(-4, 0, 0, 0, 0, 0, 4, 0, 0, -4, 0, 0, 0, 0, 0, 1);
                case EAST -> matrix(0, -4, 0, 0, 0, 0, 4, 0, 4, 0, 0, 0, 0, 0, 0, 1);
                case WEST -> matrix(0, 4, 0, 0, 0, 0, 4, 0, -4, 0, 0, 0, 0, 0, 0, 1);
            };
        }

        double normalizedYaw = (yaw - 90) % 360;
        if (normalizedYaw < 0) {
            normalizedYaw += 360.0;
        }

        if (normalizedYaw >= 45 && normalizedYaw < 135) {
            return matrix(0, 0, 4, 0, 0, 4, 0, 0, -4, 0, 0, 0, 0, 0, 0, 1);
        } else if (normalizedYaw >= 135 && normalizedYaw < 225) {
            return matrix(4, 0, 0, 0, 0, 4, 0, 0, 0, 0, 4, 0, 0, 0, 0, 1);
        } else if (normalizedYaw >= 225 && normalizedYaw < 315) {
            return matrix(0, 0, -4, 0, 0, 4, 0, 0, 4, 0, 0, 0, 0, 0, 0, 1);
        } else {
            return matrix(-4, 0, 0, 0, 0, 4, 0, 0, 0, 0, -4, 0, 0, 0, 0, 1);
        }
    }

    private Matrix4f matrix(float m00, float m01, float m02, float m03,
                            float m10, float m11, float m12, float m13,
                            float m20, float m21, float m22, float m23,
                            float m30, float m31, float m32, float m33) {
        return new Matrix4f(
                m00, m01, m02, m03,
                m10, m11, m12, m13,
                m20, m21, m22, m23,
                m30, m31, m32, m33
        );
    }
}
