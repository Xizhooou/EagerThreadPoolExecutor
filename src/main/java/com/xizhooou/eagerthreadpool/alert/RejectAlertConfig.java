package com.xizhooou.eagerthreadpool.alert;

/**
 * @param thresholdPerMinute 1分钟内拒绝 >= N 报警
 * @param cooldownMillis     防刷屏：两次报警最短间隔
 * @param windowSeconds      ring buffer 参数 默认 60
 * @param bucketSeconds      默认 5（12桶）
 */
public record RejectAlertConfig(boolean weComEnabled, String weComWebhookUrl, long thresholdPerMinute,
                                long cooldownMillis, int windowSeconds, int bucketSeconds, int maxMessageChars) {
    public RejectAlertConfig(boolean weComEnabled,
                             String weComWebhookUrl,
                             long thresholdPerMinute,
                             long cooldownMillis,
                             int windowSeconds,
                             int bucketSeconds,
                             int maxMessageChars) {
        this.weComEnabled = weComEnabled;
        this.weComWebhookUrl = weComWebhookUrl == null ? "" : weComWebhookUrl;
        this.thresholdPerMinute = Math.max(1, thresholdPerMinute);
        this.cooldownMillis = Math.max(0, cooldownMillis);
        this.windowSeconds = windowSeconds <= 0 ? 60 : windowSeconds;
        this.bucketSeconds = bucketSeconds <= 0 ? 5 : bucketSeconds;
        this.maxMessageChars = Math.max(200, maxMessageChars);
    }

    public static RejectAlertConfig disabled() {
        return new RejectAlertConfig(false, "", 200, 60_000, 60, 5, 1800);
    }
}
