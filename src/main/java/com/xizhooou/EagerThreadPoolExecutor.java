package com.xizhooou;

import javax.xml.validation.TypeInfoProvider;
import java.awt.*;
import java.awt.image.CropImageFilter;
import java.net.CacheRequest;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 快速消费线程池
 * 如果发现当前线程池内核心线程数 小于 池内线程
 * 则立马创建非 核心线程运行，而不是写入工作队列
 */
public class EagerThreadPoolExecutor extends ThreadPoolExecutor {

    private final AtomicInteger coreThreadCount = new AtomicInteger(0);

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
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory,
                RejectedProxyUtil.createProxy(handler, rejectedNum));
        this.rejectedNum = rejectedNum;
        // 把当前线程池实例传给队列，让队列持有线程池的引用
        // 这样 TaskQueue 在执行 offer() 等方法时，可以通过 executor 字段访问线程池的状态
        // （如线程数、最大线程数等），实现更智能的任务调度。
        workQueue.setExecutor(this);
    }

    public int getCoreThreadCount(){
        return coreThreadCount.get();
    }

    public long getRejectedNum() {
        return rejectedNum.get();
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        coreThreadCount.decrementAndGet();
    }

    @Override
    public void execute(Runnable command){
        coreThreadCount.incrementAndGet();
        try {
            super.execute(command);
        }catch (RejectedExecutionException e){
            WorkQueue workQueue = (WorkQueue) getQueue();
            try {
                if (!workQueue.retryOffer(command, 0, TimeUnit.MILLISECONDS)){
                    coreThreadCount.decrementAndGet();
                    throw new RejectedExecutionException(e);
                }
            }catch (InterruptedException e1){
                coreThreadCount.decrementAndGet();
                throw new RejectedExecutionException(e1);
            }
        }catch (Exception ex){
            coreThreadCount.decrementAndGet();
            throw ex;
        }
    }
}
