package vip.qoriginal.quantumplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class SurvivalTickWork implements Runnable {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Override
    public void run() {
        World world = Bukkit.getWorld("world");
        if (world == null) return;
        updateClock(world);
        updateBoneMeal(world);
    }

    private static void updateClock(World world) {
        Block block = world.getBlockAt(-2039, 67, 811);
        if (!block.getChunk().isLoaded() || block.getType() != Material.DARK_OAK_WALL_SIGN) return;
        Sign sign = (Sign) block.getState();
        sign.getSide(Side.FRONT).line(1, Component.text(LocalTime.now().format(CLOCK))
                .decorate(TextDecoration.BOLD).append(Component.text(" UTC+8")));
        sign.update();
    }

    private static void updateBoneMeal(World world) {
        Block block = world.getBlockAt(-1696, 66, 687);
        if (!block.getChunk().isLoaded() || block.getType() != Material.DARK_OAK_WALL_SIGN) return;
        Inventory first = ((Chest) world.getBlockAt(-1702, 70, 720).getState()).getBlockInventory();
        Inventory second = ((Chest) world.getBlockAt(-1702, 70, 719).getState()).getBlockInventory();
        int amount = countBoneMeal(first) + countBoneMeal(second);
        Sign sign = (Sign) block.getState();
        sign.getSide(Side.FRONT).line(1, Component.text(amount + " / 3456"));
        sign.update();
    }

    private static int countBoneMeal(Inventory inventory) {
        int amount = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() == Material.BONE_MEAL) amount += stack.getAmount();
        }
        return amount;
    }
}
