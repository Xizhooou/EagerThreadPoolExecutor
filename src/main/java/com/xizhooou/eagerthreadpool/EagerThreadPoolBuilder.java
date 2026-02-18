package com.xizhooou.eagerthreadpool;

import com.xizhooou.eagerthreadpool.alert.RejectAlertConfig;
import com.xizhooou.eagerthreadpool.alert.RejectAlertState;
import com.xizhooou.eagerthreadpool.alert.RollingWindowCounter;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class EagerThreadPoolBuilder {

    private String poolName = "eagerThreadPool";
    private int corePoolSize = 8;
    private int maximumPoolSize = 64;
    private long keepAliveTime = 60;
    private TimeUnit keepAliveUnit = TimeUnit.SECONDS;
    private int queueCapacity = 1024;

    private ThreadFactory threadFactory;
    private RejectedExecutionHandler rejectedHandler = new ThreadPoolExecutor.AbortPolicy();
    private AtomicLong rejectedNum = new AtomicLong(0);

    // 重新入队时间
    private long retryOfferTimeout = 0;
    private TimeUnit retryOfferTimeoutUnit = TimeUnit.MILLISECONDS;

    // 允许核心线程也在空闲时回收
    private boolean allowCoreThreadTimeOut;
    // 预启动所有核心线程
    private boolean prestartAllCoreThreads;

    // 报警总开关
    private boolean alertEnabled;
    // 微信报警webhook uri
    private String weComWebhookUrl = "";
    // 报警阈值
    private long thresholdPerMinute = 200;
    // 冷却时间
    private long cooldownSeconds = 60;
    // 窗口大小
    private int windowSeconds = 60;
    // 桶大小
    private int bucketSeconds = 5;
    // 最大消息长度
    private int maxMessageChars = 1800;

    private EagerThreadPoolBuilder() {}

    public static EagerThreadPoolBuilder newBuilder() { return new EagerThreadPoolBuilder(); }

    public EagerThreadPoolBuilder name(String poolName) {
        this.poolName = Objects.requireNonNull(poolName, "poolName").trim();
        return this;
    }

    public EagerThreadPoolBuilder poolName(String poolName) {
        return name(poolName);
    }

    public EagerThreadPoolBuilder corePoolSize(int v) {
        this.corePoolSize = v;
        return this;
    }

    public EagerThreadPoolBuilder maximumPoolSize(int v) {
        this.maximumPoolSize = v;
        return this;
    }

    public EagerThreadPoolBuilder keepAlive(long time, TimeUnit unit) {
        this.keepAliveTime = time;
        this.keepAliveUnit = Objects.requireNonNull(unit, "keepAliveUnit");
        return this;
    }

    public EagerThreadPoolBuilder queueCapacity(int v) {
        this.queueCapacity = v;
        return this;
    }

    public EagerThreadPoolBuilder threadFactory(ThreadFactory tf) {
        this.threadFactory = Objects.requireNonNull(tf, "threadFactory");
        return this;
    }

    public EagerThreadPoolBuilder rejectedHandler(RejectedExecutionHandler handler) {
        this.rejectedHandler = Objects.requireNonNull(handler, "rejectedHandler");
        return this;
    }

    public EagerThreadPoolBuilder rejectedExecutionHandler(RejectedExecutionHandler handler) {
        return rejectedHandler(handler);
    }

    public EagerThreadPoolBuilder rejectedCounter(AtomicLong counter) {
        this.rejectedNum = Objects.requireNonNull(counter, "rejectedCounter");
        return this;
    }

    public EagerThreadPoolBuilder retryOfferTimeout(long timeout, TimeUnit unit) {
        this.retryOfferTimeout = timeout;
        this.retryOfferTimeoutUnit = Objects.requireNonNull(unit, "retryOfferTimeoutUnit");
        return this;
    }

    public EagerThreadPoolBuilder allowCoreThreadTimeOut(boolean on) {
        this.allowCoreThreadTimeOut = on;
        return this;
    }

    public EagerThreadPoolBuilder prestartAllCoreThreads(boolean on) {
        this.prestartAllCoreThreads = on;
        return this;
    }

    public EagerThreadPoolBuilder alertEnabled(boolean on) {
        this.alertEnabled = on;
        return this;
    }

    public EagerThreadPoolBuilder weComWebhookUrl(String url) {
        this.weComWebhookUrl = (url == null) ? "" : url.trim();
        return this;
    }

    public EagerThreadPoolBuilder thresholdPerMinute(long n) {
        this.thresholdPerMinute = n;
        return this;
    }

    public EagerThreadPoolBuilder cooldownSeconds(long s) {
        this.cooldownSeconds = s;
        return this;
    }

    public EagerThreadPoolBuilder windowSeconds(int s) {
        this.windowSeconds = s;
        return this;
    }

    public EagerThreadPoolBuilder bucketSeconds(int s) {
        this.bucketSeconds = s;
        return this;
    }

    public EagerThreadPoolBuilder maxMessageChars(int chars) {
        this.maxMessageChars = chars;
        return this;
    }

    public EagerThreadPoolExecutor build() {
        validate();

        String normalizedPoolName = poolName.isBlank() ? "eager" : poolName;
        WorkQueue<Runnable> queue = new WorkQueue<>(queueCapacity);

        ThreadFactory resolvedThreadFactory = threadFactory != null
                ? threadFactory
                : namedThreadFactory(normalizedPoolName);

        RejectAlertConfig cfg = new RejectAlertConfig(
                alertEnabled,
                weComWebhookUrl,
                thresholdPerMinute,
                TimeUnit.SECONDS.toMillis(cooldownSeconds),
                windowSeconds,
                bucketSeconds,
                maxMessageChars
        );

        RejectAlertState state = new RejectAlertState(
                alertEnabled,
                new RollingWindowCounter(cfg.windowSeconds(), cfg.bucketSeconds())
        );

        EagerThreadPoolExecutor executor = new EagerThreadPoolExecutor(
                corePoolSize,
                maximumPoolSize,
                keepAliveTime,
                keepAliveUnit,
                queue,
                resolvedThreadFactory,
                rejectedHandler,
                rejectedNum,
                normalizedPoolName,
                retryOfferTimeout,
                retryOfferTimeoutUnit,
                cfg,
                state
        );

        executor.allowCoreThreadTimeOut(allowCoreThreadTimeOut);
        if (prestartAllCoreThreads) {
            executor.prestartAllCoreThreads();
        }
        return executor;
    }

    private void validate() {
        if (corePoolSize <= 0) {
            throw new IllegalArgumentException("corePoolSize must be > 0");
        }
        if (maximumPoolSize < corePoolSize) {
            throw new IllegalArgumentException("maximumPoolSize must be >= corePoolSize");
        }
        if (keepAliveTime < 0) {
            throw new IllegalArgumentException("keepAliveTime must be >= 0");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be > 0");
        }
        if (retryOfferTimeout < 0) {
            throw new IllegalArgumentException("retryOfferTimeout must be >= 0");
        }
        if (alertEnabled && weComWebhookUrl.isBlank()) {
            throw new IllegalArgumentException("weComWebhookUrl must not be blank when alertEnabled=true");
        }
    }

    private static ThreadFactory namedThreadFactory(String poolName) {
        ThreadFactory base = Executors.defaultThreadFactory();
        AtomicLong seq = new AtomicLong(1);
        return runnable -> {
            Thread thread = base.newThread(runnable);
            thread.setName(poolName + "-worker-" + seq.getAndIncrement());
            return thread;
        };
    }
}
