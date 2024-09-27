package vip.qoriginal.quantumplugin;

import kotlinx.coroutines.Dispatchers;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class Request {
    static CoroutineJava cj = new CoroutineJava();
    public static CompletableFuture<String> sendPostRequest(String targetUrl, String data) throws Exception {
        return sendPostRequest(targetUrl, data, Optional.empty());
    }

    public static CompletableFuture<String> sendPostRequest(String targetUrl, String data, Optional<Map<String, String>> headers) throws Exception {
        return cj.run(() -> {
            String result = "";
            HttpURLConnection connection = null;
            try {
                URL url = new URL(targetUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                HttpURLConnection finalConnection = connection;
                headers.ifPresent(h -> {
                    for (Map.Entry<String, String> header : h.entrySet()) {
                        finalConnection.setRequestProperty(header.getKey(), header.getValue());
                    }
                });
                connection.setDoOutput(true);
                connection.setDoInput(true);
                try (DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream())) {
                    outputStream.writeBytes(data);
                }
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            result += line;
                        }
                    }
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            return result;
        }, Dispatchers.getIO());
    }

    public static CompletableFuture<String> sendGetRequest(String targetUrl) throws Exception {
        return sendGetRequest(targetUrl, Optional.empty());
    }

    public static CompletableFuture<String> sendGetRequest(String targetUrl, Optional<Map<String, String>> headers) throws Exception {
        return cj.run(() -> {
            String result = "";
            HttpURLConnection connection = null;
            try {
                URL url = new URL(targetUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Content-Type", "application/json");
                HttpURLConnection finalConnection = connection;
                headers.ifPresent(h -> {
                    for (Map.Entry<String, String> header : h.entrySet()) {
                        finalConnection.setRequestProperty(header.getKey(), header.getValue());
                    }
                });
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            result += line;
                        }
                    }
                }
            } catch(Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            return result;
        }, Dispatchers.getIO());
    }
}
