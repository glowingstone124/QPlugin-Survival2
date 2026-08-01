package vip.qoriginal.quantumplugin.servux;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

/**
 * Implements the shared part of Servux' entity_data protocol used by MiniHUD's
 * block inventory preview.
 */
public final class ServuxEntityDataBridge implements PluginMessageListener, AutoCloseable {
    public static final String CHANNEL = "servux:entity_data";

    private static final int PROTOCOL_VERSION = 1;
    private static final int PACKET_S2C_METADATA = 1;
    private static final int PACKET_C2S_METADATA_REQUEST = 2;
    private static final int PACKET_C2S_BLOCK_ENTITY_REQUEST = 3;
    private static final int PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE = 5;

    // Do not let a modified client use this endpoint as a remote container scanner.
    private static final double MAX_QUERY_DISTANCE_SQUARED = 64.0 * 64.0;

    private final Plugin plugin;

    public ServuxEntityDataBridge(Plugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    @Override
    public void onPluginMessageReceived(
            @NotNull String channel,
            @NotNull Player player,
            byte @NotNull [] message
    ) {
        if (!CHANNEL.equals(channel) || message.length == 0 || !player.isOnline()) {
            return;
        }

        FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.wrappedBuffer(message));
        try {
            int packetType = input.readVarInt();
            switch (packetType) {
                case PACKET_C2S_METADATA_REQUEST -> sendMetadata(player);
                case PACKET_C2S_BLOCK_ENTITY_REQUEST -> {
                    input.readVarInt(); // Legacy transaction id; Servux currently ignores it too.
                    handleBlockEntityRequest(player, input.readBlockPos());
                }
                default -> {
                    // This bridge deliberately implements only inventory-preview packets.
                }
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Ignoring malformed Servux entity_data packet from "
                    + player.getName() + ": " + exception.getMessage());
        }
    }

    private void sendMetadata(Player player) {
        CompoundTag metadata = new CompoundTag();
        metadata.putString("name", "entity_data");
        metadata.putString("id", CHANNEL);
        metadata.putInt("version", PROTOCOL_VERSION);
        metadata.putString("servux", "QuantumPlugin entity_data bridge");

        FriendlyByteBuf output = new FriendlyByteBuf(Unpooled.buffer());
        try {
            output.writeVarInt(PACKET_S2C_METADATA);
            output.writeNbt(metadata);
            send(player, output);
        } finally {
            output.release();
        }
    }

    private void handleBlockEntityRequest(Player player, BlockPos pos) {
        double dx = player.getLocation().getX() - (pos.getX() + 0.5);
        double dy = player.getLocation().getY() - (pos.getY() + 0.5);
        double dz = player.getLocation().getZ() - (pos.getZ() + 0.5);
        if (dx * dx + dy * dy + dz * dz > MAX_QUERY_DISTANCE_SQUARED) {
            return;
        }

        if (!player.getWorld().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return;
        }

        ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        CompoundTag nbt = blockEntity != null
                ? blockEntity.saveWithFullMetadata(level.registryAccess())
                : new CompoundTag();

        FriendlyByteBuf output = new FriendlyByteBuf(Unpooled.buffer());
        try {
            output.writeVarInt(PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE);
            output.writeBlockPos(pos);
            output.writeNbt(nbt);
            send(player, output);
        } finally {
            output.release();
        }
    }

    private void send(Player player, FriendlyByteBuf output) {
        byte[] payload = new byte[output.readableBytes()];
        output.getBytes(output.readerIndex(), payload);
        player.sendPluginMessage(plugin, CHANNEL, payload);
    }

    @Override
    public void close() {
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }
}
