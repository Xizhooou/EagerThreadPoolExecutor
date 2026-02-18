package com.xizhooou;

import lombok.Data;
import lombok.Setter;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class WorkQueue<R extends Runnable> extends LinkedBlockingQueue<Runnable> {

    @Setter
    private EagerThreadPoolExecutor executor;

    public WorkQueue(int capacity) {
        super(capacity);
    }

    @Override
    public boolean offer(Runnable task) {
        int currentPoolThreadSize = executor.getPoolSize();
        // 线程池内有空闲的线程, 交给空闲线程处理
        if (executor.getCoreThreadCount() < currentPoolThreadSize){
            return super.offer(task);
        }
        // 当前线程池内无空闲线程 且 小于最大线程数, 返回 false, 让线程池创建线程
        if (currentPoolThreadSize < executor.getMaximumPoolSize()){
            return false;
        }
        // 直接加入阻塞队列
        return super.offer(task);
    }

    public boolean retryOffer(Runnable task, long timeout, TimeUnit unit) throws InterruptedException{
        if (executor.isShutdown()){
            throw new RejectedExecutionException("executor is shutdown");
        }
        return super.offer(task, timeout, unit);
    }
}
