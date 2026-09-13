package vip.qoriginal.quantumplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Minecraft chat selects a capability; QAPI owns provider routing and quota settlement. */
public final class MinecraftLlmClient {
    public static final String MODEL = "fast";

    private MinecraftLlmClient() {}

    public static String requestBody(String prompt) {
        JsonObject request = new JsonObject();
        request.addProperty("model", MODEL);
        request.addProperty("stream", false);
        request.addProperty("reasoning_effort", "none");
        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        messages.add(message);
        request.add("messages", messages);
        return request.toString();
    }

    public static Map<String, String> requestHeaders(String secret, String playerName,
            String coordinate, String hp, String requestId) {
        return Map.of(
                "Authorization", "Bearer " + secret,
                "X-Minecraft-Name", playerName,
                "X-Minecraft-Coordinate", coordinate,
                "X-Minecraft-HP", hp,
                "X-Request-ID", requestId
        );
    }

    public record Reply(String answer, Integer chargedUnits) {}

    public static final class ApiException extends IOException {
        public ApiException(String message) { super(message); }
    }

    public static Reply parseResponse(Request.Response response) throws ApiException {
        JsonObject root = null;
        try {
            JsonElement parsed = JsonParser.parseString(response.body == null ? "" : response.body);
            if (parsed.isJsonObject()) root = parsed.getAsJsonObject();
        } catch (RuntimeException ignored) {
            // An HTTP failure may return HTML or plain text rather than QAPI JSON.
        }
        if (response.status < 200 || response.status >= 300 ||
                (root != null && root.has("error") && !root.get("error").isJsonNull())) {
            throw new ApiException(errorMessage(response.status, root, response.headers));
        }
        if (root == null) throw new ApiException("模型回复格式异常，请稍后重试。");

        try {
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) throw new ApiException("模型没有返回内容。");
            JsonElement message = choices.get(0).getAsJsonObject().get("message");
            String answer = message != null && message.isJsonPrimitive() ? message.getAsString() :
                    message != null && message.isJsonObject() ? string(message.getAsJsonObject(), "content") : null;
            if (answer == null || answer.isBlank()) throw new ApiException("模型没有返回内容。");
            Integer units = null;
            JsonElement quota = root.get("quota");
            if (quota != null && quota.isJsonObject()) {
                String value = string(quota.getAsJsonObject(), "charged_units");
                try { if (value != null) units = Integer.valueOf(value); }
                catch (NumberFormatException ignored) {}
            }
            return new Reply(answer.trim(), units != null && units > 0 ? units : null);
        } catch (RuntimeException error) {
            throw new ApiException("模型回复格式异常，请稍后重试。");
        }
    }

    private static String errorMessage(int status, JsonObject root, Map<String, List<String>> headers) {
        JsonElement error = root == null ? null : root.get("error");
        JsonObject detail = error != null && error.isJsonObject() ? error.getAsJsonObject() : null;
        String code = string(detail, "code");
        if (code == null) code = string(detail, "type");
        String message = string(detail, "message");
        String fallback = switch (code == null ? "" : code) {
            case "weekly_quota_exceeded", "daily_quota_exceeded" -> "本周额度与 Paid Credits 不足，可购买 Credits 继续使用。";
            case "rate_limited" -> "请求过于频繁或达到并发限制，请稍后重试。";
            case "duplicate_request" -> "这个请求已经提交，请稍候。";
            case "quota_unavailable" -> "额度服务暂时不可用，请稍后重试。";
            case "user_not_found" -> "玩家尚未绑定 QO/QQ 账号。";
            case "invalid_token" -> "AI 服务认证失败，请联系管理员。";
            default -> switch (status) {
                case 401, 403 -> "AI 服务认证失败或账号不可用，请联系管理员。";
                case 429 -> "请求过于频繁，或本周额度与 Paid Credits 不足。";
                case 503 -> "AI 服务暂时不可用，请稍后重试。";
                default -> "AI 请求失败（HTTP " + status + "）。";
            };
        };
        String result = message == null || message.isBlank() ? fallback : message.trim();
        String retry = header(headers, "Retry-After");
        if (status == 429 && retry != null) {
            try {
                long seconds = Long.parseLong(retry);
                if (seconds > 0) {
                    String duration = seconds < 60 ? seconds + " 秒" : seconds < 7200 ?
                            "约 " + (long) Math.ceil(seconds / 60.0) + " 分钟" :
                            "约 " + (long) Math.ceil(seconds / 3600.0) + " 小时";
                    result += "（" + duration + "后可重试）";
                }
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static String header(Map<String, List<String>> headers, String name) {
        if (headers == null) return null;
        for (var entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) &&
                    entry.getValue() != null && !entry.getValue().isEmpty()) return entry.getValue().get(0);
        }
        return null;
    }
}
