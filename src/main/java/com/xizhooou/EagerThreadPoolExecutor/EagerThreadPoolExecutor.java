package com.xizhooou.EagerThreadPoolExecutor;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 快速消费线程池
 * 如果发现当前线程池内核心线程数 小于 池内线程
 * 则立马创建非 核心线程运行，而不是写入工作队列
 */
public class EagerThreadPoolExecutor extends ThreadPoolExecutor {

    // 用于区分 拒绝策略内部入队 vs 和execute入队
    static final ThreadLocal<Boolean> IN_EXECUTE_CONTEXT =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    static boolean isInExecuteContext(){
        return Boolean.TRUE.equals(IN_EXECUTE_CONTEXT.get());
    }

    private final AtomicInteger submittedTaskCount = new AtomicInteger(0);

    // 线程池拒绝次数
    private final AtomicLong rejectedNum;

    public EagerThreadPoolExecutor(int corePoolSize,
                                   int maximumPoolSize,
                                   long keepAliveTime,
                                   TimeUnit unit,
                                   WorkQueue<Runnable> workQueue,
                                   ThreadFactory threadFactory,
                                   RejectedExecutionHandler handler,
                                   AtomicLong rejectedNum) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, handler);

        this.rejectedNum = rejectedNum;
        // 让队列拿到 executor 引用
        workQueue.setExecutor(this);

        setRejectedExecutionHandler(RejectedProxyUtil.createProxy(handler, rejectedNum, this));
    }

    public int getSubmittedTaskCount(){
        return submittedTaskCount.get();
    }

    public long getRejectedNum() {
        return rejectedNum.get();
    }

    /**
     * 调整 submittedTaskCount
     */
    void adjustSubmittedTaskCount(int delta){
        if (delta == 0) return;
        for (;;){
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
    public void execute(Runnable command){
        IN_EXECUTE_CONTEXT.set(Boolean.TRUE);
        adjustSubmittedTaskCount(1);

        try {
            super.execute(command);
        }catch (RejectedExecutionException e){

            WorkQueue workQueue = (WorkQueue) getQueue();
            try {
                // 未成功入队 -1
                if (!workQueue.retryOffer(command, 0, TimeUnit.MILLISECONDS)){
                    adjustSubmittedTaskCount(-1);
                    throw new RejectedExecutionException(e);
                }
            }catch (InterruptedException e1){
                adjustSubmittedTaskCount(-1);
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException(e1);
            }
        }catch (RuntimeException | Error ex) {
            adjustSubmittedTaskCount(-1);
            throw ex;
        } finally {
            IN_EXECUTE_CONTEXT.remove();
        }
    }
}
