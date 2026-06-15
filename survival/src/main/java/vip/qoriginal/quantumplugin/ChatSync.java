package vip.qoriginal.quantumplugin;

import com.google.gson.*;
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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.net.HttpURLConnection;

import static vip.qoriginal.quantumplugin.QuantumPlugin.isShutup;

public class ChatSync implements Listener {
    private final static int QO_CREATIVE_CODE = 4;
    private final static int WEB_CODE = 3;
    private final static int SYSTEM_CODE = 2;
    private final static int QO_CODE = 1;
    private final static int QQ_CODE = 0;
    private static Gson gson = new Gson();
    private static final long QQ_CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final Map<Long, CachedName> qqNameCache = new ConcurrentHashMap<>();

    private static class CachedName {
        final String name;
        final long expiresAt;
        CachedName(String name, long expiresAt) {
            this.name = name;
            this.expiresAt = expiresAt;
        }
    }

    public Runnable createWebMsgGetter() {
        return new WebMsgGetter();
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!isShutup(event.getPlayer())) {
            Thread.startVirtualThread(() -> {
                try {

                    String playerName = event.getPlayer().getName();
                    String message = event.getMessage();
                    MessageWrapper mw = new MessageWrapper(message, ChatType.GAME_CHAT.getChatType(), Config.INSTANCE.getAPI_SECRET(), QO_CODE, System.currentTimeMillis(), playerName);
                    String llmPrompt = extractLlmPrompt(message);
                    if (llmPrompt != null) {
                        handleLlmPrompt(event.getPlayer(), llmPrompt);
                        return;
                    }
                    System.out.println(mw.getAsString());
                    Request.sendPostRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/msglist/upload", mw.getAsString());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }

    public void sendChatMsg(String message) {
        Thread.startVirtualThread(() -> {
            try {
                Request.sendPostRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/msglist/upload", new MessageWrapper(message, ChatType.SYSTEM_CHAT.getChatType(), Config.INSTANCE.getAPI_SECRET(), QO_CODE, System.currentTimeMillis(), "QO").getAsString());
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private String extractLlmPrompt(String message) {
        for (String alias : Config.INSTANCE.llmMentionAliases()) {
            if (message.startsWith(alias)) {
                return message.substring(alias.length()).trim();
            }
        }
        return null;
    }

    private void handleLlmPrompt(Player player, String prompt) {
        if (prompt.isBlank()) {
            Bukkit.getScheduler().runTask(QuantumPlugin.getInstance(), () ->
                    player.sendMessage(Component.text("用法：@恋恋 <content>").color(TextColor.color(180, 180, 180)))
            );
            return;
        }

        try {
            String response = Request.sendPostRequest(
                    Config.INSTANCE.getAPI_ENDPOINT() + "/qo/asking/v1/chat/completions/minecraft",
                    buildLlmRequest(prompt),
                    Optional.of(Map.of(
                            "Authorization", "Bearer " + Config.INSTANCE.getAPI_SECRET(),
                            "X-Minecraft-Name", player.getName()
                    )),
                    Config.INSTANCE.llmRequestTimeoutMillis()
            ).get(Config.INSTANCE.llmRequestTimeoutMillis() + 1000L, TimeUnit.MILLISECONDS);
            String answer = extractLlmAnswer(response);
            String clippedAnswer = answer.length() > 1800 ? answer.substring(0, 1800) : answer;
            Bukkit.getScheduler().runTask(QuantumPlugin.getInstance(), () -> {
                Component component = Component.text("<恋恋> " + clippedAnswer)
                        .color(TextColor.color(113, 159, 165));
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    onlinePlayer.sendMessage(component);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            Bukkit.getScheduler().runTask(QuantumPlugin.getInstance(), () ->
                    player.sendMessage(Component.text("LLM请求失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()))
                            .color(TextColor.color(220, 80, 80)))
            );
        }
    }

    private String buildLlmRequest(String prompt) {
        JsonObject request = new JsonObject();
        request.addProperty("stream", false);
        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", prompt);
        messages.add(userMessage);
        request.add("messages", messages);
        return request.toString();
    }

    private String extractLlmAnswer(String response) {
        if (response == null || response.isBlank()) {
            return "LLM 没有返回内容。";
        }
        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(response);
        } catch (JsonSyntaxException e) {
            return response.trim();
        }
        if (!rootElement.isJsonObject()) {
            return rootElement.toString();
        }
        JsonObject root = rootElement.getAsJsonObject();
        if (root.has("error")) {
            JsonElement errorElement = root.get("error");
            if (errorElement != null && errorElement.isJsonObject()) {
                JsonObject error = errorElement.getAsJsonObject();
                JsonElement message = error.get("message");
                if (message != null && !message.isJsonNull()) {
                    return message.getAsString();
                }
            } else if (errorElement != null && !errorElement.isJsonNull()) {
                return errorElement.getAsString();
            }
            return "LLM 返回错误。";
        }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return "LLM 没有返回内容。";
        }
        JsonElement messageElement = choices.get(0).getAsJsonObject().get("message");
        if (messageElement == null || messageElement.isJsonNull()) {
            return "LLM 没有返回内容。";
        }
        if (messageElement.isJsonPrimitive()) {
            String answer = messageElement.getAsString().trim();
            return answer.isBlank() ? "LLM 没有返回内容。" : answer;
        }
        if (!messageElement.isJsonObject()) {
            return "LLM 没有返回内容。";
        }
        JsonObject message = messageElement.getAsJsonObject();
        if (!message.has("content") || message.get("content").isJsonNull()) {
            return "LLM 没有返回内容。";
        }
        String answer = message.get("content").getAsString().trim();
        return answer.isBlank() ? "LLM 没有返回内容。" : answer;
    }

    public class WebMsgGetter implements Runnable {
        private String buffer = "";
        private long lastTimestamp = 0L;
        private final Object lock = new Object();
        private String lastResponse = null;
        private String lastEtag = null;

        @Override
        public void run() {
            try {
                Map<String, String> headers = new HashMap<>();
                if (lastEtag != null && !lastEtag.isBlank()) {
                    headers.put("If-None-Match", lastEtag);
                }
                Request.Response resp = Request.sendGetRequestWithStatus(
                        Config.INSTANCE.getAPI_ENDPOINT() + "/qo/msglist/download",
                        Optional.of(headers)
                ).get(5, TimeUnit.SECONDS);
                if (resp.status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    return;
                }
                String response = resp.body;
                if (response != null && response.equals(lastResponse)) {
                    return;
                }
                lastResponse = response;
                String etag = null;
                List<String> etagHeaders = resp.headers.get("ETag");
                if (etagHeaders != null && !etagHeaders.isEmpty()) {
                    etag = etagHeaders.get(0);
                }
                if (etag != null && !etag.isBlank()) {
                    lastEtag = etag;
                }
                JsonElement jsonElement = JsonParser.parseString(response);

                if (jsonElement.isJsonObject()) {
                    JsonObject msgObj = jsonElement.getAsJsonObject();
                    List<JsonObject> newMessages = parseMessages(msgObj.getAsJsonArray("messages"));

                    List<JsonObject> messagesToSend = new ArrayList<>();
                    synchronized (lock) {
                        long maxSeenTimestamp = lastTimestamp;
                        for (JsonObject msg : newMessages) {
                            long messageTime = msg.get("time").getAsLong();

                            if (messageTime > lastTimestamp) {
                                messagesToSend.add(msg);
                                maxSeenTimestamp = Math.max(maxSeenTimestamp, messageTime);
                            }
                        }

                        if (!messagesToSend.isEmpty()) {
                            for (JsonObject msg : messagesToSend) {
                                int from = msg.get("from").getAsInt();
                                if (from == QO_CODE) continue;

                                Component msgComponent = buildMessageComponent(from, msg);

                                for (Player p : Bukkit.getOnlinePlayers()) {
                                    p.sendMessage(msgComponent);
                                }
                            }
                        }
                        lastTimestamp = maxSeenTimestamp;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private List<JsonObject> parseMessages(JsonArray messagesArray) {
            List<JsonObject> messages = new ArrayList<>();
            if (messagesArray == null) {
                return messages;
            }
            for (JsonElement msgElement : messagesArray) {
                if (msgElement.isJsonObject()) {
                    messages.add(msgElement.getAsJsonObject());
                } else if (msgElement.isJsonPrimitive()) {
                    String msgStr = msgElement.getAsString();
                    JsonObject msgObj = JsonParser.parseString(msgStr).getAsJsonObject();
                    messages.add(msgObj);
                }
            }
            return messages;
        }
        private Component buildMessageComponent(int from, JsonObject msg) {
            String content;
            String message = msg.get("message").getAsString();

            switch (from) {
                case WEB_CODE -> {
                    String sender = msg.get("sender").getAsString();
                    content = "<" + sender + ">" + message;
                    return Component.text(content)
                            .color(TextColor.color(113, 159, 165));
                }

                case QQ_CODE -> {
                    long sender = msg.get("sender").getAsLong();
                    String username = getQQUsername(sender);
                    if (username == null) {
                        content = "<未注册>" + parseCQ(message);
                    } else {
                        content = "<" + username + ">" + parseCQ(message);
                    }
                    return Component.text(content)
                            .color(TextColor.color(33, 95, 105))
                            .hoverEvent(HoverEvent.showText(Component.text("Sender ID: " + sender)));
                }

                case SYSTEM_CODE -> {
                    content = "<系统>" + message;
                    return Component.text(content)
                            .color(TextColor.color(33, 95, 105))
                            .hoverEvent(HoverEvent.showText(Component.text("这是Quantum Original官方消息")));
                }

                case QO_CREATIVE_CODE -> {
                    content = "[QO_Creative]<" + msg.get("sender").getAsString() + ">" + message;
                    return Component.text(content)
                            .color(TextColor.color(33, 95, 105));
                }
                default -> {
                    return Component.text("<unknown source>" + message);
                }
            }
        }

        private String getQQUsername(long qq) {
            try {
                CachedName cached = qqNameCache.get(qq);
                if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
                    return cached.name;
                }
                String url = Config.INSTANCE.getAPI_ENDPOINT() + "/qo/download/name?qq=" + qq;
                JsonObject resp = (JsonObject) JsonParser.parseString(Request.sendGetRequest(url).get());
                String result = resp.get("code").getAsInt() == 0 ? resp.get("username").getAsString() : null;
                qqNameCache.put(qq, new CachedName(result, System.currentTimeMillis() + QQ_CACHE_TTL_MS));
                return result;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
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
