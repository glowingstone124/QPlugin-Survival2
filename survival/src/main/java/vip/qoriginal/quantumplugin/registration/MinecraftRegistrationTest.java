package vip.qoriginal.quantumplugin.registration;

import org.bukkit.entity.Player;

public interface MinecraftRegistrationTest {
    String METHOD_ID = "minecraft";
    String SESSION_ENDPOINT = "/qo/registration/minecraft/session";
    String RESULT_ENDPOINT = "/qo/registration/minecraft/result";

    boolean isAvailable();

    StartResult start(Player player, Session session);

    void cancel(Player player);

    record Session(String sessionId, String username) {
    }

    record StartResult(boolean accepted, String code, String message) {
        public static StartResult unavailable() {
            return new StartResult(
                    false,
                    "minecraft_verification_reserved",
                    "Minecraft 世界测试接口已预留，当前尚未开放。"
            );
        }
    }
}
