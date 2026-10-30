package vip.qoriginal.quantumplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read-only selection capture. All world access stays on the server thread. */
public final class BuilderCommand implements CommandExecutor, Listener {
    private final JavaPlugin plugin;
    private final Map<UUID, Location[]> selections = new HashMap<>();
    private final Set<UUID> exporting = new HashSet<>();

    private BuilderCommand(JavaPlugin plugin) { this.plugin = plugin; }

    public static void register(JavaPlugin plugin) {
        var command = plugin.getCommand("builder");
        if (command == null) return;
        var handler = new BuilderCommand(plugin);
        command.setExecutor(handler);
        command.setTabCompleter((sender, cmd, alias, args) -> args.length == 1
                ? List.of("pos1", "pos2", "export", "clear").stream().filter(s -> s.startsWith(args[0])).toList() : List.of());
        plugin.getServer().getPluginManager().registerEvents(handler, plugin);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { selections.remove(event.getPlayer().getUniqueId()); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("请在游戏中选择建筑区域。"); return true; }
        if (player.getScoreboardTags().contains("visitor_login") || !player.hasPermission("quantum.builder")) {
            player.sendMessage("请先登录，并确认拥有 quantum.builder 权限。"); return true;
        }
        if (args.length != 1) { player.sendMessage("/builder pos1 | pos2 | export | clear"); return true; }
        UUID id = player.getUniqueId();
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "pos1", "pos2" -> {
                var target = player.getTargetBlockExact(64);
                if (target == null) { player.sendMessage("请瞄准 64 格内的角点方块。"); return true; }
                Location[] points = selections.computeIfAbsent(id, ignored -> new Location[2]);
                int index = args[0].equalsIgnoreCase("pos1") ? 0 : 1;
                points[index] = target.getLocation();
                player.sendMessage("角点 " + (index + 1) + "：" + target.getX() + ", " + target.getY() + ", " + target.getZ());
            }
            case "clear" -> { selections.remove(id); player.sendMessage("选区已清除。"); }
            case "export" -> export(player);
            default -> player.sendMessage("/builder pos1 | pos2 | export | clear");
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private void export(Player player) {
        UUID id = player.getUniqueId();
        Location[] points = selections.get(id);
        if (points == null || points[0] == null || points[1] == null || !points[0].getWorld().equals(points[1].getWorld())) {
            player.sendMessage("请先在同一世界设置两个角点。"); return;
        }
        if (exporting.contains(id) || exporting.size() >= 2) { player.sendMessage("选区导出正在进行，请稍后重试。"); return; }
        World world = points[0].getWorld();
        int minX = Math.min(points[0].getBlockX(), points[1].getBlockX());
        int minY = Math.min(points[0].getBlockY(), points[1].getBlockY());
        int minZ = Math.min(points[0].getBlockZ(), points[1].getBlockZ());
        long widthLong = Math.abs((long) points[0].getBlockX() - points[1].getBlockX()) + 1;
        long heightLong = Math.abs((long) points[0].getBlockY() - points[1].getBlockY()) + 1;
        long lengthLong = Math.abs((long) points[0].getBlockZ() - points[1].getBlockZ()) + 1;
        if (widthLong > 256 || heightLong > 256 || lengthLong > 256 || widthLong * heightLong * lengthLong > 262144) {
            player.sendMessage("选区每边最多 256 格，总体积最多 262144 格。"); return;
        }
        int width = (int) widthLong, height = (int) heightLong, length = (int) lengthLong;
        int volume = width * height * length;
        JsonObject doc = new JsonObject();
        doc.addProperty("version", 1); doc.addProperty("name", player.getName() + " 的选区");
        doc.addProperty("dataVersion", plugin.getServer().getUnsafe().getDataVersion());
        doc.addProperty("width", width); doc.addProperty("height", height); doc.addProperty("length", length);
        var palette = new LinkedHashMap<String, Integer>();
        var blocks = new JsonArray(volume);
        exporting.add(id);
        player.sendMessage("正在读取选区。首版仅导出方块状态，不包含实体、容器物品或告示牌文字。读取期间请勿修改选区。");
        new BukkitRunnable() {
            int cursor;
            @Override public void run() {
                if (!player.isOnline()) { cancel(); exporting.remove(id); return; }
                try {
                    // Two exports at most; never synchronously load chunks or scan an entire region in one tick.
                    int end = Math.min(volume, cursor + 2048);
                    while (cursor < end) {
                        int x = minX + cursor % width;
                        int z = minZ + (cursor / width) % length;
                        int y = minY + cursor / (width * length);
                        if (!world.isChunkLoaded(x >> 4, z >> 4)) throw new IllegalStateException("选区包含未加载区块，请靠近建筑后重试。");
                        String state = world.getBlockAt(x, y, z).getBlockData().getAsString();
                        int blockId = palette.computeIfAbsent(state, ignored -> palette.size());
                        if (palette.size() > 4096) throw new IllegalStateException("选区方块状态种类过多。");
                        blocks.add(blockId); cursor++;
                    }
                    if (cursor < volume) return;
                    cancel();
                    JsonArray states = new JsonArray(); palette.keySet().forEach(states::add);
                    doc.add("palette", states); doc.add("blocks", blocks);
                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> upload(player, doc));
                } catch (Exception error) {
                    cancel(); exporting.remove(id); player.sendMessage("导出失败：" + error.getMessage());
                }
            }
        }.runTaskTimer(plugin, 1, 1);
    }

    private void upload(Player player, JsonObject doc) {
        String body = doc.toString();
        Request.sendPostRequestWithStatus(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/builder/transfers", body,
                Optional.of(Map.of("Authorization", "Bearer " + Config.INSTANCE.getAPI_SECRET(), "X-Minecraft-Name", player.getName())), 30000)
            .whenComplete((response, error) -> {
                if (!plugin.isEnabled()) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    exporting.remove(player.getUniqueId());
                    if (!player.isOnline()) return;
                    if (error != null || response.status < 200 || response.status >= 300) {
                        player.sendMessage("上传选区失败，请稍后重试。"); return;
                    }
                    try {
                        String code = JsonParser.parseString(response.body).getAsJsonObject().get("code").getAsString();
                        UUID.fromString(code);
                        player.sendMessage(Component.text("选区已就绪。点击复制导入码（10 分钟有效）：" + code)
                                .clickEvent(ClickEvent.copyToClipboard(code)));
                        player.sendMessage("在 Kotshi 登录同一账号，进入 Builder 工作区，粘贴导入码加载。");
                    } catch (Exception invalid) { player.sendMessage("选区服务响应异常，请稍后重试。"); }
                });
            });
    }
}
