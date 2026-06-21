package vip.qoriginal.quantumplugin.fakeplayer;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.common.collect.HashMultimap;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FakePlayerManager {
    private final Map<String, ServerPlayer> players = new ConcurrentHashMap<>();

    public FakePlayerManager() {
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

        server.getPlayerList().players.add(player);
        level.addNewPlayer(player);
        players.put(key, player);

        server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));
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

        MinecraftServer server = server();
        ServerLevel level = player.level();
        server.getPlayerList().players.remove(player);
        level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        player.connection.disconnect(net.minecraft.network.chat.Component.literal("Fake player removed"));
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

    private ServerPlayer find(String requestedName) {
        return players.get(key(normalizeName(requestedName)));
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
