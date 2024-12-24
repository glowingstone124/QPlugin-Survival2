package vip.qoriginal.quantumplugin;

import com.google.gson.*;
import io.papermc.paper.event.player.AsyncChatEvent;
import kotlin.Pair;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;

import java.util.*;

import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static vip.qoriginal.quantumplugin.QuantumPlugin.isShutup;

public class ChatSync implements Listener {
    private final static int QO_CODE = 1;
    private static Gson gson = new Gson();
    static ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public void init() {
        WebMsgGetter webMsgGetter = new WebMsgGetter();

        scheduler.scheduleAtFixedRate(webMsgGetter, 0, 500, TimeUnit.MILLISECONDS);
    }

    public static void exit() {
        scheduler.shutdown();
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!isShutup(event.getPlayer())) {
            Thread.startVirtualThread(() -> {
                try {
                    String playerName = event.getPlayer().getName();
                    String message = event.getMessage();
                    String encodedMessage = new String(message.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
                    MessageWrapper mw = new MessageWrapper(encodedMessage, ChatType.GAME_CHAT.getChatType(), AuthUtils.INSTANCE.getToken(), QO_CODE, System.currentTimeMillis(), playerName);
                    System.out.println(mw.getAsString());
                    Request.sendPostRequest("http://172.19.0.6:8080/qo/msglist/upload", mw.getAsString());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }

    public void sendChatMsg(String message) {
        Thread.startVirtualThread(() -> {
            try {
                String encodedMessage = new String(message.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
                Request.sendPostRequest("http://172.19.0.6:8080/qo/msglist/upload", new MessageWrapper(encodedMessage, ChatType.SYSTEM_CHAT.getChatType(), AuthUtils.INSTANCE.getToken(), QO_CODE, System.currentTimeMillis(), "QO").getAsString());
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public class WebMsgGetter implements Runnable {
        private String buffer = "";
        private long lastTimestamp = 0L;
        private final Object lock = new Object();

        @Override
        public void run() {
            try {
                String response = Request.sendGetRequest("http://172.19.0.6:8080/qo/msglist/download").get();
                JsonElement jsonElement = JsonParser.parseString(response);

                if (jsonElement.isJsonObject()) {
                    JsonObject msgObj = jsonElement.getAsJsonObject();
                    List<JsonObject> newMessages = parseMessages(msgObj.getAsJsonArray("messages"));

                    List<JsonObject> messagesToSend = new ArrayList<>();
                    synchronized (lock) {
                        for (JsonObject msg : newMessages) {
                            long messageTime = msg.get("time").getAsLong();

                            if (messageTime > lastTimestamp) {
                                messagesToSend.add(msg);
                                lastTimestamp = messageTime;
                            }
                        }

                        if (!messagesToSend.isEmpty()) {
                            for (JsonObject msg : messagesToSend) {
                                if(msg.get("from").getAsInt() == QO_CODE) return;
                                long sender = msg.get("from").getAsInt();
                                JsonObject resp = (JsonObject) JsonParser.parseString(Request.sendGetRequest("http://172.19.0.6:8080/qo/download/name?qq=" + sender).get());
                                String content;
                                if (resp.get("code").getAsInt() == 0) {
                                    content = "<" + resp.get("username") + ">" + parseCQ(msg.get("message").getAsString());
                                } else {
                                    content = "<未注册>" + parseCQ(msg.get("message").getAsString());
                                }
                                Component msgComponent = Component.text(content).color(TextColor.color(113, 159, 165)).hoverEvent(HoverEvent.showText(Component.text("Sender ID: " + sender)));
                                for (Player p : Bukkit.getOnlinePlayers()) {
                                    p.sendMessage(msgComponent);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private List<JsonObject> parseMessages(JsonArray messagesArray) {
            List<JsonObject> messages = new ArrayList<>();
            for (JsonElement msgElement : messagesArray) {
                if (msgElement.isJsonPrimitive()) {
                    String msgStr = msgElement.getAsString();
                    JsonObject msgObj = JsonParser.parseString(msgStr).getAsJsonObject();
                    messages.add(msgObj);
                }
            }
            return messages;
        }
    }


    public String parseCQ(String content) {
        content = content.replaceAll("\\[CQ:face,id=.*?\\]", "[表情]");
        content = content.replaceAll("\\[CQ:image,file=.*?\\]", "[图片]");
        content = content.replaceAll("\\[CQ:record,file=.*?\\]", "[语音]");
        content = content.replaceAll("\\[CQ:share,file=.*?\\]", "[链接]");
        content = content.replaceAll("\\[CQ:mface,.*?\\]", "[表情]");
        content = content.replace("CQ:at,qq=", "@");
        return content;
    }

    public String parseCQv2(String content) {
        String[] nodes = content.replaceAll("\\[", "").replaceAll("\\]", "").split(",");
        if (nodes.length == 0) return "INVALID";

        StringBuilder result = new StringBuilder();
        switch (nodes[0]) {
            case "CQ:face" -> {
                result.append("FACE");
                return result.toString();
            }
            case "CQ:image" -> {
                result.append("IMAGE");
                Optional<Pair<String, String>> filePair = parsePair(content).stream()
                        .filter(element -> "file".equals(element.getFirst()))
                        .findFirst();
                filePair.ifPresent(pair -> result.append(" - File: ").append(pair.getSecond()));
                return result.toString();
            }
            case "CQ:record" -> {
                result.append("RECORD");
                return result.toString();
            }
            case "CQ:share" -> {
                result.append("SHARE");
                return result.toString();
            }
            case "CQ:mface" -> {
                result.append("MFACE");
                return result.toString();
            }
            case "CQ:at" -> {
                result.append("AT");
                return result.toString();
            }
            default -> {
                return "UNKNOWN";
            }
        }
    }

    public List<Pair<String, String>> parsePair(String content) {
        List<Pair<String, String>> pairs = new ArrayList<>();
        Arrays.stream(content.split(",")).forEach(it -> {
            String[] nodes = it.split("=", 2); // 防止越界，最多分割两部分
            if (nodes.length == 2) {
                pairs.add(new Pair<>(nodes[0].trim(), nodes[1].trim()));
            }
        });
        return pairs;
    }


    public enum ChatType {
        GAME_CHAT("game_chat"),
        SYSTEM_CHAT("system_chat");
        private final String chatType;

        ChatType(String chatType) {
            this.chatType = chatType;
        }

        public String getChatType() {
            return chatType;
        }
    }

    public static class MessageWrapper {
        public String message;
        public String type;
        public String sender;
        public String token;
        public int from;
        public long time;

        public MessageWrapper(String message, String type, String token, int from, long time, String sender) {
            this.message = message;
            this.type = type;
            this.token = token;
            this.from = from;
            this.time = time;
            this.sender = sender;
        }

        public String getAsString() {
            return gson.toJson(this);
        }
    }
}
