package vip.qoriginal.quantumplugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;

public final class LlmApiError {
    private final int status;
    private final String code;
    private final String message;
    private final Long retryAfterSeconds;
    private final Integer remaining;
    private final Long resetAtEpochSeconds;

    private LlmApiError(
            int status,
            String code,
            String message,
            Long retryAfterSeconds,
            Integer remaining,
            Long resetAtEpochSeconds
    ) {
        this.status = status;
        this.code = code;
        this.message = message;
        this.retryAfterSeconds = retryAfterSeconds;
        this.remaining = remaining;
        this.resetAtEpochSeconds = resetAtEpochSeconds;
    }

    public static LlmApiError parse(int status, String body, Map<String, List<String>> headers) {
        String code = null;
        String message = null;
        boolean containsError = false;
        if (body != null && !body.isBlank()) {
            try {
                JsonElement rootElement = JsonParser.parseString(body);
                if (rootElement.isJsonObject()) {
                    JsonElement errorElement = rootElement.getAsJsonObject().get("error");
                    if (errorElement != null && !errorElement.isJsonNull()) {
                        containsError = true;
                        if (errorElement.isJsonObject()) {
                            JsonObject error = errorElement.getAsJsonObject();
                            code = stringValue(error.get("code"));
                            if (code == null) code = stringValue(error.get("type"));
                            message = stringValue(error.get("message"));
                        } else {
                            message = stringValue(errorElement);
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // A non-JSON error body is handled by the HTTP status fallback below.
            }
        }
        if (status >= 200 && status < 300 && !containsError) {
            return null;
        }
        return new LlmApiError(
                status,
                code,
                message,
                longHeader(headers, "Retry-After"),
                intHeader(headers, "X-RateLimit-Remaining"),
                longHeader(headers, "X-RateLimit-Reset")
        );
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public Integer remaining() {
        return remaining;
    }

    public Long resetAtEpochSeconds() {
        return resetAtEpochSeconds;
    }

    public String userMessage() {
        String base = switch (code == null ? "" : code) {
            case "daily_quota_exceeded" -> fallback(message, "今天的对话额度已经用完。");
            case "rate_limited" -> fallback(message, "请求过于频繁，请稍后再试。");
            case "duplicate_request" -> fallback(message, "这个请求已经提交，请稍候。");
            case "quota_unavailable" -> fallback(message, "额度服务暂时不可用，请稍后再试。");
            case "invalid_token" -> "LLM 服务认证失败，请联系管理员。";
            default -> fallback(message, statusFallback(status));
        };
        if (retryAfterSeconds != null && retryAfterSeconds > 0 &&
                (status == 429 || "daily_quota_exceeded".equals(code) || "rate_limited".equals(code))) {
            return base + "（" + formatRetryAfter(retryAfterSeconds) + "后可重试）";
        }
        return base;
    }

    public String technicalSummary() {
        return "HTTP " + status + (code == null ? "" : " code=" + code) + " message=" + userMessage();
    }

    private static String statusFallback(int status) {
        if (status == 429) return "请求过于频繁或今天的额度已经用完。";
        if (status == 503) return "LLM 服务暂时不可用，请稍后再试。";
        if (status == 401 || status == 403) return "LLM 服务认证失败，请联系管理员。";
        return "LLM 请求失败（HTTP " + status + "）。";
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String formatRetryAfter(long seconds) {
        if (seconds < 60) return seconds + " 秒";
        long minutes = (seconds + 59) / 60;
        if (minutes < 120) return "约 " + minutes + " 分钟";
        long hours = (minutes + 59) / 60;
        return "约 " + hours + " 小时";
    }

    private static String stringValue(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        try {
            return element.getAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Long longHeader(Map<String, List<String>> headers, String name) {
        String value = firstHeader(headers, name);
        if (value == null) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer intHeader(Map<String, List<String>> headers, String name) {
        Long value = longHeader(headers, name);
        if (value == null || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) return null;
        return value.intValue();
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase(name)) continue;
            List<String> values = entry.getValue();
            if (values != null && !values.isEmpty()) return values.get(0);
        }
        return null;
    }
}
