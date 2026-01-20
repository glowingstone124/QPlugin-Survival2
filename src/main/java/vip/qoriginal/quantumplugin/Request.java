package vip.qoriginal.quantumplugin;

import kotlinx.coroutines.Dispatchers;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class Request {
    static CoroutineJava cj = new CoroutineJava();

    public static CompletableFuture<String> sendPostRequest(String targetUrl, String data) throws Exception {
        return sendPostRequest(targetUrl, data, Optional.empty());
    }

    public static CompletableFuture<String> sendPostRequest(
            String targetUrl,
            String data,
            Optional<Map<String, String>> headers
    ) {
        return cj.run(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URI(targetUrl).toURL();
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");

                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);

                if (headers.isPresent()) {
                    for (Map.Entry<String, String> header : headers.get().entrySet()) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }

                connection.setDoOutput(true);

                try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
                    out.writeBytes(data);
                }

                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    return "";
                }

                StringBuilder sb = new StringBuilder(256);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)
                )) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }

                return sb.toString();
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, Dispatchers.getIO());
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
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);

                if (headers.isPresent()) {
                    for (Map.Entry<String, String> header : headers.get().entrySet()) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream())
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
}
