package vip.qoriginal.quantumplugin;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.Optional;

public class Request {

    public static String sendPostRequest(String targetUrl, String data) throws Exception {
        return sendPostRequest(targetUrl, data, Optional.empty());
    }

    public static String sendPostRequest(String targetUrl, String data, Optional<Map<String, String>> headers) throws Exception {
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
    }

    public static String sendGetRequest(String targetUrl) throws Exception {
        return sendGetRequest(targetUrl, Optional.empty());
    }

    public static String sendGetRequest(String targetUrl, Optional<Map<String, String>> headers) throws Exception {
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
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return result;
    }
}
