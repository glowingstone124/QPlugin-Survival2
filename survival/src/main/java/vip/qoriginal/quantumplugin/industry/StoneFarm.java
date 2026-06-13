package vip.qoriginal.quantumplugin.industry;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Furnace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class StoneFarm implements Listener {
    private static int[] d49k = new int[]{0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};
    private static int[] d12w = new int[]{0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};

    public static int console_state = 0;
    @EventHandler
    public void onPickUpItem(InventoryPickupItemEvent event) {
        Location l = event.getInventory().getLocation();
        if(l.getWorld().getName().contentEquals("world")) {
            if(l.getBlockX()==-2392&&l.getBlockY()==72&&l.getBlockZ()>=1345&&l.getBlockZ()<=1354) {
                d49k[0] += event.getItem().getItemStack().getAmount();
            }
            if(l.getBlockX()==-2164&&l.getBlockY()==67&&l.getBlockZ()>=985&&l.getBlockZ()<=992) {
                d12w[0] += event.getItem().getItemStack().getAmount();
            }
        }
    }
    public static int[] move() {
        int[] result = new int[]{0,0};
        //anyway, this move will be approximately executed 7200 / h, so actual shall be sum(num) * 180
        for(int i=39;i>=1;i--) {
            result[0] += d49k[i] * (200 - i);
            result[1] += d12w[i] * (200 - i);
            d49k[i] = d49k[i-1];
            d12w[i] = d12w[i-1];
        }
        d49k[0] = 0;
        d12w[0] = 0;
        Block b = Bukkit.getWorld("world").getBlockAt(-2393,73,1354);
        if(b.getChunk().isLoaded()) {
            if (b.getType() == Material.DARK_OAK_WALL_SIGN) {
                Sign sign = (Sign) b.getState();
                sign.line(3, Component.text(result[0]+" bl / h"));
                sign.update();
            }
        }
        b = Bukkit.getWorld("world").getBlockAt(-2166,68,985);
        if(b.getChunk().isLoaded()) {
            if (b.getType() == Material.DARK_OAK_WALL_SIGN) {
                Sign sign = (Sign) b.getState();
                sign.line(3, Component.text(result[1]+" bl / h"));
                sign.update();
            }
        }

        b = Bukkit.getWorld("world").getBlockAt(-1782,68,720);
        if(b.getChunk().isLoaded()) {
            if(b.getType() == Material.LEVER) {
                BlockData data = b.getBlockData();
                if(data.getAsString().contains("powered=true")) StoneFarm.console_state = Math.min(10,StoneFarm.console_state+1);
                else StoneFarm.console_state = 0;
            }
        }

        if(console_state>=2) {
            BlanketStatus();
        } else {
            mergeSign(-1787, 70, 720, 1, Component.text("█").color(TextColor.color(64,64,64)));
            mergeSign(-1787, 70, 720, 2, Component.text("█").color(TextColor.color(64,64,64)));
            mergeSign(-1787, 70, 720, 3, Component.text("█").color(TextColor.color(64,64,64)));
        }
        for(int i=1;i<=3;i++) for(int j=0;j<=1;j++) updateFurnace(i,j);
        return result;
    }
    private static void mergeSign(int x, int y, int z, int index, Component text) {
        Block b = Bukkit.getWorld("world").getBlockAt(x,y,z);
        if(b.getChunk().isLoaded()) {
            if (b.getType() == Material.BIRCH_WALL_SIGN) {
                Sign sign = (Sign) b.getState();
                sign.line(index, text);
                sign.update();
            }
        }
    }
    private static void BlanketStatus() {
        Block b = Bukkit.getWorld("world").getBlockAt(-1779,76,718);
        if(b.getChunk().isLoaded()) {
            if(b.getType() == Material.STICKY_PISTON) {
                BlockData data = b.getBlockData();
                if(data.getAsString().contains("extended=true"))
                    mergeSign(-1787, 70, 720, 1, Component.text("█").color(TextColor.color(64,255,64)));
                else mergeSign(-1787, 70, 720, 1, Component.text("█").color(TextColor.color(64,64,64)));
            }
        }
        b = Bukkit.getWorld("world").getBlockAt(-1770,76,718);
        if(b.getChunk().isLoaded()) {
            if(b.getType() == Material.STICKY_PISTON) {
                BlockData data = b.getBlockData();
                if(data.getAsString().contains("extended=true"))
                    mergeSign(-1787, 70, 720, 2, Component.text("█").color(TextColor.color(64,255,64)));
                else mergeSign(-1787, 70, 720, 2, Component.text("█").color(TextColor.color(64,64,64)));
            }
        }
        b = Bukkit.getWorld("world").getBlockAt(-1761,76,718);
        if(b.getChunk().isLoaded()) {
            if(b.getType() == Material.STICKY_PISTON) {
                BlockData data = b.getBlockData();
                if(data.getAsString().contains("extended=true"))
                    mergeSign(-1787, 70, 720, 3, Component.text("█").color(TextColor.color(64,255,64)));
                else mergeSign(-1787, 70, 720, 3, Component.text("█").color(TextColor.color(64,64,64)));
            }
        }
        b = Bukkit.getWorld("world").getBlockAt(-1761,78,715);
        if(b.getChunk().isLoaded()) {
            if(b.getType() == Material.STICKY_PISTON) {
                BlockData data = b.getBlockData();
                if(data.getAsString().contains("extended=false")) {
                    mergeSign(-1787, 70, 720, 1, Component.text("█").color(TextColor.color(255,200,36)));
                    mergeSign(-1787, 70, 720, 2, Component.text("█").color(TextColor.color(255,200,36)));
                    mergeSign(-1787, 70, 720, 3, Component.text("█").color(TextColor.color(255,200,36)));
                }
            }
        }
    }
    private static void updateFurnace(int core, int height) {
        int x_pointer = -1782 + 9 * core;
        int y_pointer = 66 + 3 * height;
        Component text = Component.text(">> ");
        if(console_state>=3) {
            for (int z = 738; z >= 718; z--) {
                Block b = Bukkit.getWorld("world").getBlockAt(x_pointer, y_pointer, z);
                if (b.getChunk().isLoaded()) {
                    if (b.getType() == Material.FURNACE) {
                        BlockData data = b.getBlockData();
                        if (data.getAsString().contains("lit=true")) text = text.append(Component.text("|")
                                .color(TextColor.color(64, 255, 64)));
                        else {
                            if (((Furnace) b.getState()).getInventory().getFuel() == null)
                                text = text.append(Component.text("|").color(TextColor.color(255, 200, 36)));
                            else text = text.append(Component.text("|").color(TextColor.color(64, 64, 64)));
                        }
                    } else text = text.append(Component.text(" "));
                }
            }
        } else text = text.append(Component.text("|||||||||| ||||||||||").color(TextColor.color(64, 64, 64)));
        mergeSign(-1785-height, 70, 720, core, text);
    }
}
