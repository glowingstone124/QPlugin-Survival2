package vip.qoriginal.quantumplugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class PlayerInventoryViewer implements Listener {
    private static final Map<UUID, UUID> openInventories = new HashMap<>();
    private static final Map<String,String> keyRing = new HashMap<>();

    private void openInventoryForPlayer(Player viewer, Player target) {
        Inventory targetInventory = target.getInventory();
        Inventory gui = Bukkit.createInventory(viewer, targetInventory.getSize(), target.getName() + "的背包");
        for (int i = 0; i < targetInventory.getSize(); i++) {
            ItemStack item = targetInventory.getItem(i);
            if (item != null) {
                gui.setItem(i, item.clone());
            }
        }

        openInventories.put(viewer.getUniqueId(), target.getUniqueId());

        viewer.openInventory(gui);
    }

    public void init() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(QuantumPlugin.getInstance(), () -> {
            keyRing.forEach((username,key) -> {
                try {
                    JsonObject activity = JsonParser.parseString(Request.sendGetRequest("http://172.19.0.6:8080/qo/inventory/query?secrets=" + key).get()).getAsJsonObject();
                    int operation = activity.get("approved").getAsInt();
                    String viewer = activity.get("viewer").getAsString();
                    if (operation == 0){
                        openInventoryForPlayer(getPlayer(viewer), Objects.requireNonNull(getPlayer(username)));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            for (UUID viewerUUID : openInventories.keySet()) {
                Player viewer = Bukkit.getPlayer(viewerUUID);
                UUID ownerUUID = openInventories.get(viewerUUID);
                Player owner = Bukkit.getPlayer(ownerUUID);

                if (viewer != null && owner != null) {
                    Inventory viewerInventory = viewer.getOpenInventory().getTopInventory();
                    Inventory ownerInventory = owner.getInventory();

                    synchronizeInventories(viewerInventory, ownerInventory);
                }
            }
        }, 0L, 3L);

    }
    public void insertKey(String playername, String key){
        keyRing.put(playername,key);
    }
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        Inventory clickedInventory = event.getClickedInventory();

        if (clickedInventory == null) return;

        if (openInventories.containsKey(player.getUniqueId())) {
            UUID targetUUID = openInventories.get(player.getUniqueId());
            Player targetPlayer = Bukkit.getPlayer(targetUUID);

            if (targetPlayer != null) {
                Inventory targetInventory = targetPlayer.getInventory();
                synchronizeInventories(clickedInventory, targetInventory);
                targetPlayer.updateInventory();
            }
        } else if (openInventories.containsValue(player.getUniqueId())) {
            UUID viewerUUID = getKeyByValue(openInventories, player.getUniqueId());
            assert viewerUUID != null;
            Player viewerPlayer = Bukkit.getPlayer(viewerUUID);

            if (viewerPlayer != null) {
                Inventory viewerInventory = viewerPlayer.getOpenInventory().getTopInventory();
                synchronizeInventories(clickedInventory, viewerInventory);
                viewerPlayer.updateInventory();
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        UUID targetUUID = openInventories.remove(player.getUniqueId());
        if (targetUUID != null) {
            Player targetPlayer = Bukkit.getPlayer(targetUUID);
            if (targetPlayer != null) {
                Inventory targetInventory = targetPlayer.getInventory();
                synchronizeInventories(event.getInventory(), targetInventory);
                targetPlayer.updateInventory();
            }
        }
    }

    private void synchronizeInventories(Inventory source, Inventory target) {
        for (int i = 0; i < source.getSize(); i++) {
            ItemStack item = source.getItem(i);
            target.setItem(i, item != null ? item.clone() : null);
        }
    }

    private UUID getKeyByValue(Map<UUID, UUID> map, UUID value) {
        for (Map.Entry<UUID, UUID> entry : map.entrySet()) {
            if (value.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }
    private Player getPlayer(String username) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(username)) {
                return player;
            }
        }
        return null;
    }
}
