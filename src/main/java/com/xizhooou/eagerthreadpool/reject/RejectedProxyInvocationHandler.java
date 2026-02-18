package com.xizhooou.eagerthreadpool.reject;

import com.xizhooou.eagerthreadpool.EagerThreadPoolExecutor;
import com.xizhooou.eagerthreadpool.WorkQueue;
import com.xizhooou.eagerthreadpool.alert.RejectAlertConfig;
import com.xizhooou.eagerthreadpool.alert.RejectAlertState;
import com.xizhooou.eagerthreadpool.alert.WeComRobotAlerter;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

public class RejectedProxyInvocationHandler implements InvocationHandler {

    private final RejectedExecutionHandler target;
    private final AtomicLong rejectCount;
    private final EagerThreadPoolExecutor executor;
    private final String poolName;

    private final RejectAlertConfig alertConfig; // nullable
    private final RejectAlertState alertState;   // nullable
    private final WeComRobotAlerter alerter;     // nullable

    public RejectedProxyInvocationHandler(RejectedExecutionHandler target,
                                          AtomicLong rejectCount,
                                          EagerThreadPoolExecutor executor,
                                          String poolName,
                                          RejectAlertConfig alertConfig,
                                          RejectAlertState alertState) {
        this.target = (target != null) ? target : new ThreadPoolExecutor.AbortPolicy();
        this.rejectCount = (rejectCount != null) ? rejectCount : new AtomicLong(0);
        this.executor = executor;
        this.poolName = (poolName == null || poolName.isBlank()) ? "eager" : poolName;

        this.alertConfig = alertConfig;
        this.alertState = alertState;
        this.alerter = (alertConfig != null && alertConfig.weComEnabled())
                ? new WeComRobotAlerter(alertConfig.weComWebhookUrl())
                : null;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (!"rejectedExecution".equals(method.getName())) {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        }

        long totalRejected = rejectCount.incrementAndGet();

        ThreadPoolExecutor tpe = (args != null && args.length >= 2 && args[1] instanceof ThreadPoolExecutor)
                ? (ThreadPoolExecutor) args[1]
                : null;

        alert(totalRejected, tpe);

        boolean handlerReturnedNormally = false;
        WorkQueue.enterRejectContext();
        try {
            Object result = method.invoke(target, args);
            handlerReturnedNormally = true;
            return result;
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        } finally {
            WorkQueue.exitRejectContext();
            // AbortPolicy throws -> execute() catch path handles decrement.
            // CallerRuns/Discard/DiscardOldest returns normally -> compensate +1 from execute().
            if (handlerReturnedNormally && executor != null) {
                executor.adjustSubmittedTaskCount(-1);
            }
        }
    }

    private void alert(long totalRejected, ThreadPoolExecutor tpe) {
        if (alertConfig == null || alertState == null || alerter == null) {
            return;
        }
        if (!alertState.isEnabled()) {
            return;
        }

        alertState.getRollingCounter().increment();

        long lastWindow = alertState.getRollingCounter().sumLastWindow();
        if (lastWindow < alertConfig.thresholdPerMinute()) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = alertState.getLastAlertAtMs().get();
        if (now - last < alertConfig.cooldownMillis()) {
            return;
        }
        if (!alertState.getLastAlertAtMs().compareAndSet(last, now)) {
            return;
        }

        String msg = buildAlertMessage(totalRejected, lastWindow, tpe);
        alerter.alertAsync("线程池拒绝告警", msg);
    }

    private String buildAlertMessage(long totalRejected, long rejectedLastMinute, ThreadPoolExecutor tpe) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("pool=").append(poolName).append('\n');
        sb.append("rejected(last60s)=").append(rejectedLastMinute)
                .append(", threshold=").append(alertConfig.thresholdPerMinute()).append('\n');
        sb.append("rejected(total)=").append(totalRejected).append('\n');

        if (tpe != null) {
            sb.append("core=").append(tpe.getCorePoolSize())
                    .append(", max=").append(tpe.getMaximumPoolSize())
                    .append(", poolSize=").append(tpe.getPoolSize())
                    .append(", active=").append(tpe.getActiveCount()).append('\n');
            if (tpe.getQueue() != null) {
                sb.append("queueSize=").append(tpe.getQueue().size()).append('\n');
            }
            sb.append("submitted=").append(executor != null ? executor.getSubmittedTaskCount() : -1).append('\n');
            sb.append("completed=").append(tpe.getCompletedTaskCount()).append('\n');
        }

        String text = sb.toString();
        if (text.length() > alertConfig.maxMessageChars()) {
            text = text.substring(0, alertConfig.maxMessageChars()) + "\n...truncated...";
        }
        return text;
    }
}
