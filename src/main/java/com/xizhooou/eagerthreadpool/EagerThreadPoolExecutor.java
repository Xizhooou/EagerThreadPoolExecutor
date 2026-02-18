package com.xizhooou.eagerthreadpool;

import com.xizhooou.eagerthreadpool.alert.RejectAlertConfig;
import com.xizhooou.eagerthreadpool.alert.RejectAlertState;
import com.xizhooou.eagerthreadpool.reject.RejectedProxyUtil;
import lombok.Getter;

import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 快速消费线程池
 * 如果发现当前线程池内核心线程数 小于 池内线程
 * 则立马创建非 核心线程运行，而不是写入工作队列
 */
public class EagerThreadPoolExecutor extends ThreadPoolExecutor {

    // 用于区分 拒绝策略内部入队 vs 和execute入队
    static final ThreadLocal<Boolean> IN_EXECUTE_CONTEXT = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final AtomicInteger submittedTaskCount = new AtomicInteger(0);
    private final AtomicLong rejectedNum;

    @Getter
    private final String poolName;

    private final long retryOfferTimeout;
    private final TimeUnit retryOfferTimeoutUnit;

    private final RejectAlertConfig alertConfig;
    private final RejectAlertState alertState;

    public EagerThreadPoolExecutor(int corePoolSize,
                                   int maximumPoolSize,
                                   long keepAliveTime,
                                   TimeUnit unit,
                                   WorkQueue<Runnable> workQueue,
                                   ThreadFactory threadFactory,
                                   RejectedExecutionHandler handler,
                                   AtomicLong rejectedNum,
                                   String poolName,
                                   long retryOfferTimeout,
                                   TimeUnit retryOfferTimeoutUnit,
                                   RejectAlertConfig alertConfig,
                                   RejectAlertState alertState) {
        super(corePoolSize,
                maximumPoolSize,
                keepAliveTime,
                unit,
                workQueue,
                threadFactory,
                handler);

        this.poolName = Optional.ofNullable(poolName).filter(v -> !v.isBlank()).orElse("eagerThreadPool");
        this.rejectedNum = (rejectedNum != null) ? rejectedNum : new AtomicLong(0);
        this.retryOfferTimeout = Math.max(0, retryOfferTimeout);
        this.retryOfferTimeoutUnit = (retryOfferTimeoutUnit == null) ? TimeUnit.MILLISECONDS : retryOfferTimeoutUnit;
        this.alertConfig = alertConfig;
        this.alertState = alertState;

        workQueue.setExecutor(this);

        setRejectedExecutionHandler(
                RejectedProxyUtil.createProxy(handler, this.rejectedNum, this, this.poolName, this.alertConfig, this.alertState)
        );
    }

    public EagerThreadPoolExecutor(int corePoolSize,
                                   int maximumPoolSize,
                                   long keepAliveTime,
                                   TimeUnit unit,
                                   WorkQueue<Runnable> workQueue,
                                   ThreadFactory threadFactory,
                                   RejectedExecutionHandler handler,
                                   AtomicLong rejectedNum) {
        this(corePoolSize,
                maximumPoolSize,
                keepAliveTime,
                unit,
                workQueue,
                threadFactory,
                handler,
                rejectedNum,
                "eager",
                0,
                TimeUnit.MILLISECONDS,
                null,
                null);
    }

    public static EagerThreadPoolBuilder newBuilder() {
        return EagerThreadPoolBuilder.newBuilder();
    }

    public int getSubmittedTaskCount() {
        return submittedTaskCount.get();
    }

    public long getRejectedNum() {
        return rejectedNum.get();
    }

    public void setAlertEnabled(boolean enabled) {
        if (alertState != null) {
            alertState.setEnabled(enabled);
        }
    }

    public boolean isAlertEnabled() {
        return alertState != null && alertState.isEnabled();
    }

    static boolean isInExecuteContext() {
        return Boolean.TRUE.equals(IN_EXECUTE_CONTEXT.get());
    }

    // 最近60秒拒绝的总数
    public long getRejectedInLastWindow() {
        if (alertState == null) {
            return 0;
        }
        return alertState.getRollingCounter().sumLastWindow();
    }

    /**
     * 调整 submittedTaskCount
     */
    public void adjustSubmittedTaskCount(int delta) {
        if (delta == 0) return;
        for (;;) {
            int cur = submittedTaskCount.get();
            int next = Math.max(cur + delta, 0);
            if (submittedTaskCount.compareAndSet(cur, next)) return;
        }
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        try {
            super.afterExecute(r, t);
        } finally {
            adjustSubmittedTaskCount(-1);
        }
    }

    @Override
    public void execute(Runnable command) {
        IN_EXECUTE_CONTEXT.set(Boolean.TRUE);
        adjustSubmittedTaskCount(1);

        try {
            super.execute(command);
        } catch (RejectedExecutionException e) {
            WorkQueue<Runnable> workQueue = (WorkQueue<Runnable>) getQueue();
            try {
                // 未成功入队 -1
                if (!workQueue.retryOffer(command, retryOfferTimeout, retryOfferTimeoutUnit)) {
                    adjustSubmittedTaskCount(-1);
                    throw new RejectedExecutionException(e);
                }
            }catch (InterruptedException e1){
                adjustSubmittedTaskCount(-1);
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException(e1);
            }
        } catch (RuntimeException | Error ex) {
            adjustSubmittedTaskCount(-1);
            throw ex;
        } finally {
            IN_EXECUTE_CONTEXT.remove();
        }
    }
}
