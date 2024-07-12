package vip.qoriginal.quantumplugin.patch;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public class TextDisplay {
    public void exec(Player player, String text) {
        Location targetLocation = getTargetLocation(player, 50);
        if (targetLocation != null) {
            Direction bestDirection = getBestDirection(player);
            boolean vertical = shouldDisplayVertical(player);
            createTextDisplay(targetLocation, text.replace("\"", ""), bestDirection, vertical);
            player.sendMessage("Text display created at: " + targetLocation + " facing " + bestDirection);
        } else {
            player.sendMessage("No target block in sight.");
        }
    }

    public enum Direction {
        NORTH, SOUTH, EAST, WEST
    }

    public Location getTargetLocation(Player player, int range) {
        Location eyeLocation = player.getEyeLocation();
        Vector direction = eyeLocation.getDirection();
        for (int i = 0; i < range; i++) {
            Location checkLocation = eyeLocation.clone().add(direction.clone().multiply(i));
            if (!checkLocation.getBlock().isPassable()) {
                return checkLocation;
            }
        }
        return null;
    }

    public Direction getBestDirection(Player player) {
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

    public void createTextDisplay(Location location, String text, Direction direction, boolean vertical) {
        String rotationCommand;

        if (vertical) {
            switch (direction) {
                case NORTH:
                    rotationCommand = "{axis:[0.0,1.0,0.0],angle:0.0}";
                    break;
                case SOUTH:
                    rotationCommand = "{axis:[0.0,1.0,0.0],angle:3.1415926535897932}";
                    break;
                case EAST:
                    rotationCommand = "{axis:[0.0,1.0,0.0],angle:1.5707963267948965}";
                    break;
                case WEST:
                    rotationCommand = "{axis:[0.0,1.0,0.0],angle:-1.5707963267948965}";
                    break;
                default:
                    rotationCommand = "{axis:[0.0,1.0,0.0],angle:0.0}";
            }
        } else {
            double yaw = (location.getYaw() - 90) % 360;
            if (yaw < 0) {
                yaw += 360.0;
            }

            if (yaw >= 45 && yaw < 135) {
                rotationCommand = "{axis:[0.0,1.0,0.0],angle:1.57}";
            } else if (yaw >= 135 && yaw < 225) {
                rotationCommand = "{axis:[0.0,1.0,0.0],angle:3.14}";
            } else if (yaw >= 225 && yaw < 315) {
                rotationCommand = "{axis:[0.0,1.0,0.0],angle:-1.57}";
            } else {
                rotationCommand = "{axis:[0.0,1.0,0.0],angle:0.0}";
            }
        }

        String command = String.format(
                "minecraft:summon text_display %.2f %.2f %.2f {text:'{\"text\":\"%s\"}',line_width:200,text_opacity:255,shadow:false,see_through:false,alignment:\"center\",transformation:{left_rotation:{axis:[0.0,1.0,0.0],angle:0.0},right_rotation:%s,scale:[4.0,4.0,4.0],translation:[0,0,0]}}",
                location.getX(), location.getY(), location.getZ(), text, rotationCommand
        );
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    public boolean shouldDisplayVertical(Player player) {
        Vector playerDirection = player.getLocation().getDirection();
        Vector groundNormal = new Vector(0, 1, 0);
        double angle = playerDirection.angle(groundNormal);
        return Math.abs(angle - Math.PI / 2) < Math.PI / 4;
    }
}
