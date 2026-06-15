package vip.qoriginal.quantumplugin;

import kotlinx.coroutines.Dispatchers;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class Request {
    private static final int TIMEOUT_MILLIS = 3000;
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final CoroutineJava cj = new CoroutineJava();

    private Request() {
    }

    public static class Response {
        public final int status;
        public final String body;
        public final Map<String, java.util.List<String>> headers;

        public Response(int status, String body, Map<String, java.util.List<String>> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }
    }

    public static CompletableFuture<String> sendPostRequest(String targetUrl, String data) throws Exception {
        return sendPostRequest(targetUrl, data, Optional.empty());
    }

    public static CompletableFuture<String> sendPostRequest(
            String targetUrl,
            String data,
            Optional<Map<String, String>> headers
    ) {
        return sendPostRequest(targetUrl, data, headers, TIMEOUT_MILLIS);
    }

    public static CompletableFuture<String> sendPostRequest(
            String targetUrl,
            String data,
            Optional<Map<String, String>> headers,
            int timeoutMillis
    ) {
        return cj.run(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URI(targetUrl).toURL();
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", CONTENT_TYPE_JSON);

                connection.setConnectTimeout(timeoutMillis);
                connection.setReadTimeout(timeoutMillis);

                if (headers.isPresent()) {
                    for (Map.Entry<String, String> header : headers.get().entrySet()) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }

                connection.setDoOutput(true);

                try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
                    out.write(data.getBytes(StandardCharsets.UTF_8));
                }

                int code = connection.getResponseCode();
                return readResponseBody(connection, code);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, Dispatchers.getIO());
    }

    private static String readResponseBody(HttpURLConnection connection, int code) throws Exception {
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(256);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }


    public static CompletableFuture<String> sendGetRequest(String targetUrl) {
        return sendGetRequest(targetUrl, Optional.empty());
    }

    public static CompletableFuture<String> sendGetRequest(String targetUrl, Optional<Map<String, String>> headers) {
        return cj.run(() -> {
            HttpURLConnection connection = null;
            StringBuilder result = new StringBuilder(256);
            try {
                URL url = new URI(targetUrl).toURL();
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Content-Type", CONTENT_TYPE_JSON);
                connection.setConnectTimeout(TIMEOUT_MILLIS);
                connection.setReadTimeout(TIMEOUT_MILLIS);

                if (headers.isPresent()) {
                    for (Map.Entry<String, String> header : headers.get().entrySet()) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
                    )) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            result.append(line);
                        }
                    }
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            return result.toString();
        }, Dispatchers.getIO());
    }

    public static CompletableFuture<Response> sendGetRequestWithStatus(String targetUrl, Optional<Map<String, String>> headers) {
        return cj.run(() -> {
            HttpURLConnection connection = null;
            StringBuilder result = new StringBuilder(256);
            try {
                URL url = new URI(targetUrl).toURL();
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Content-Type", CONTENT_TYPE_JSON);
                connection.setConnectTimeout(TIMEOUT_MILLIS);
                connection.setReadTimeout(TIMEOUT_MILLIS);

                if (headers.isPresent()) {
                    for (Map.Entry<String, String> header : headers.get().entrySet()) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
                    )) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            result.append(line);
                        }
                    }
                }
                return new Response(responseCode, result.toString(), connection.getHeaderFields());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, Dispatchers.getIO());
    }
}
