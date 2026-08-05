package vip.qoriginal.quantumplugin.fakeplayer;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.common.collect.HashMultimap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FakePlayerManager {
    public static final String SCOREBOARD_TAG = "quantum_fake_player";

    private static final Field PLAYERS_BY_NAME_FIELD = playerListField("playersByName");

    private final Map<String, ServerPlayer> players = new ConcurrentHashMap<>();
    private final FakePlayerActionController actions = new FakePlayerActionController();

    public FakePlayerManager() {
    }

    public void start(Plugin plugin) {
        actions.startTicker(plugin);
    }

    public ServerPlayer spawn(String requestedName, Location location, PropertyMap skinProperties) {
        String name = normalizeName(requestedName);
        String key = key(name);
        if (players.containsKey(key) || Bukkit.getPlayerExact(name) != null) {
            throw new IllegalArgumentException("玩家名已经在线或假人已经存在: " + name);
        }
        if (!(location.getWorld() instanceof CraftWorld craftWorld)) {
            throw new IllegalArgumentException("无法获取 NMS 世界: " + location.getWorld());
        }

        MinecraftServer server = server();
        ServerLevel level = craftWorld.getHandle();
        GameProfile profile = new GameProfile(fakeUuid(name), name, skinProperties);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        player.connection = new FakePlayerConnection(server, player, profile);
        player.snapTo(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        player.setGameMode(GameType.SURVIVAL);
        player.moonrise$setRealPlayer(true);
        player.getBukkitEntity().addScoreboardTag(SCOREBOARD_TAG);

        PlayerList playerList = server.getPlayerList();
        registerPlayer(playerList, player);
        playerList.broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));
        level.addNewPlayer(player);
        player.initInventoryMenu();
        players.put(key, player);
        broadcastJoin(player);

        return player;
    }

    public PlayerInventory inventory(String requestedName) {
        ServerPlayer player = find(requestedName);
        if (player == null) {
            return null;
        }
        return player.getBukkitEntity().getInventory();
    }

    public boolean remove(String requestedName) {
        String key = key(normalizeName(requestedName));
        ServerPlayer player = players.remove(key);
        if (player == null) {
            return false;
        }

        actions.clear(player);
        MinecraftServer server = server();
        ServerLevel level = player.level();
        broadcastLeave(player);
        unregisterPlayer(server.getPlayerList(), player);
        level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        player.connection.disconnect(new DisconnectionDetails(net.minecraft.network.chat.Component.literal("Fake player removed")));
        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
        return true;
    }

    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (ServerPlayer player : players.values()) {
            names.add(player.getGameProfile().name());
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public void removeAll() {
        for (String name : names()) {
            remove(name);
        }
    }

    public void shutdown() {
        actions.shutdown();
    }

    FakePlayerActionController.Target action(
            String requestedName,
            FakePlayerActionController.Action action,
            FakePlayerActionController.Mode mode
    ) {
        return actions.configure(requirePlayer(requestedName), action, mode);
    }

    Rotation look(String requestedName, double yaw, double pitch) {
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch)) {
            throw new IllegalArgumentException("yaw 和 pitch 必须是有限数字");
        }
        if (pitch < -90.0 || pitch > 90.0) {
            throw new IllegalArgumentException("pitch 必须在 -90 到 90 之间");
        }

        float normalizedYaw = normalizeYaw(yaw);
        float normalizedPitch = (float) pitch;
        ServerPlayer player = requirePlayer(requestedName);
        player.absSnapRotationTo(normalizedYaw, normalizedPitch);
        player.setYHeadRot(normalizedYaw);
        player.setYBodyRot(normalizedYaw);
        return new Rotation(normalizedYaw, normalizedPitch);
    }

    Rotation lookAt(String requestedName, double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("目标坐标必须是有限数字");
        }

        ServerPlayer player = requirePlayer(requestedName);
        Vec3 eye = player.getEyePosition();
        double deltaX = x - eye.x;
        double deltaY = y - eye.y;
        double deltaZ = z - eye.z;
        double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        if (horizontalDistance < 1.0E-7 && Math.abs(deltaY) < 1.0E-7) {
            throw new IllegalArgumentException("目标坐标不能与假人眼睛位置重合");
        }

        double yaw = Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0;
        double pitch = -Math.toDegrees(Math.atan2(deltaY, horizontalDistance));
        return look(requestedName, yaw, pitch);
    }

    record Rotation(float yaw, float pitch) {
    }

    public static boolean isFakePlayer(Player player) {
        return player.getScoreboardTags().contains(SCOREBOARD_TAG);
    }

    private ServerPlayer find(String requestedName) {
        return players.get(key(normalizeName(requestedName)));
    }

    private ServerPlayer requirePlayer(String requestedName) {
        ServerPlayer player = find(requestedName);
        if (player == null) {
            throw new IllegalArgumentException("找不到假人: " + requestedName);
        }
        return player;
    }

    private float normalizeYaw(double yaw) {
        double normalized = yaw % 360.0;
        if (normalized >= 180.0) {
            normalized -= 360.0;
        } else if (normalized < -180.0) {
            normalized += 360.0;
        }
        return (float) normalized;
    }

    private void registerPlayer(PlayerList playerList, ServerPlayer player) {
        playerList.getPlayers().add(player);
        playersByName(playerList).put(key(player.getScoreboardName()), player);
        playerList.getPlayersByUUID().put(player.getUUID(), player);
    }

    private void unregisterPlayer(PlayerList playerList, ServerPlayer player) {
        playerList.getPlayers().remove(player);
        playersByName(playerList).remove(key(player.getScoreboardName()));
        playerList.getPlayersByUUID().remove(player.getUUID());
    }

    private void broadcastJoin(ServerPlayer player) {
        Bukkit.broadcast(Component.translatable("multiplayer.player.joined", Component.text(player.getScoreboardName()))
                .color(NamedTextColor.YELLOW));
    }

    private void broadcastLeave(ServerPlayer player) {
        Bukkit.broadcast(Component.translatable("multiplayer.player.left", Component.text(player.getScoreboardName()))
                .color(NamedTextColor.YELLOW));
    }

    @SuppressWarnings("unchecked")
    private Map<String, ServerPlayer> playersByName(PlayerList playerList) {
        try {
            return (Map<String, ServerPlayer>) PLAYERS_BY_NAME_FIELD.get(playerList);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("无法访问 PlayerList.playersByName", exception);
        }
    }

    private static Field playerListField(String name) {
        try {
            Field field = PlayerList.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException("无法找到 PlayerList." + name, exception);
        }
    }

    private String normalizeName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("缺少假人名");
        }
        String normalized = name.trim();
        if (!normalized.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException("假人名只能包含 3-16 位英文字母、数字或下划线");
        }
        return normalized;
    }

    private String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private UUID fakeUuid(String name) {
        return UUID.nameUUIDFromBytes(("QuantumPlugin:fake-player:" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }

    public PropertyMap skinProperties(String skinName) {
        Player onlinePlayer = Bukkit.getPlayerExact(skinName);
        if (onlinePlayer != null) {
            return copySkinProperties(onlinePlayer);
        }

        PlayerProfile profile = ((Server) Bukkit.getServer()).createProfile(skinName);
        profile.complete(true, true);
        return copySkinProperties(profile);
    }

    private PropertyMap copySkinProperties(Player skinSource) {
        if (!(skinSource instanceof CraftPlayer craftPlayer)) {
            return new PropertyMap(HashMultimap.create());
        }
        return new PropertyMap(HashMultimap.create(craftPlayer.getHandle().getGameProfile().properties()));
    }

    private PropertyMap copySkinProperties(PlayerProfile profile) {
        HashMultimap<String, Property> properties = HashMultimap.create();
        for (ProfileProperty property : profile.getProperties()) {
            properties.put(property.getName(), new Property(property.getName(), property.getValue(), property.getSignature()));
        }
        return new PropertyMap(properties);
    }

    private MinecraftServer server() {
        return ((CraftServer) Bukkit.getServer()).getServer();
    }
}
