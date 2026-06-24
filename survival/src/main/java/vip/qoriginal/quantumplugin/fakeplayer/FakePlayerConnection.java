package vip.qoriginal.quantumplugin.fakeplayer;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

final class FakePlayerConnection extends ServerGamePacketListenerImpl {
    private boolean disconnected;

    FakePlayerConnection(MinecraftServer server, ServerPlayer player, GameProfile profile) {
        super(server, new Connection(PacketFlow.SERVERBOUND), player, CommonListenerCookie.createInitial(profile, false));
    }

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, io.netty.channel.ChannelFutureListener listener) {
    }

    @Override
    public boolean isAcceptingMessages() {
        return !disconnected;
    }

    @Override
    public void disconnect(DisconnectionDetails disconnectionInfo) {
        disconnected = true;
    }

    @Override
    public void disconnectAsync(DisconnectionDetails disconnectionInfo) {
        disconnected = true;
    }
}
