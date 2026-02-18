package com.xizhooou.eagerthreadpool.alert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WeComRobotAlerter {
    private final HttpClient client = HttpClient.newHttpClient();
    private final String webhookUrl;

    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wecom-alert-sender");
        t.setDaemon(true);
        return t;
    });

    public WeComRobotAlerter(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public void alertAsync(String title, String content) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            String text = "【" + title + "】\n" + content;
            String payload = "{\"msgtype\":\"text\",\"text\":{\"content\":" + jsonQuote(text) + "}}";
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        .build();
                client.send(req, HttpResponse.BodyHandlers.discarding());
            }catch (Exception ignored){
                // 告警不应影响业务线程
            }
        }, SENDER).exceptionally(ex -> null);

    }

    private static String jsonQuote(String s) {
        String escaped = s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n");
        return "\"" + escaped + "\"";
    }
}
