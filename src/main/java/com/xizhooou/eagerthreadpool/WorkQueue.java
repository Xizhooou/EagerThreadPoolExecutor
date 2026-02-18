package com.xizhooou.eagerthreadpool;

import java.util.Collection;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class WorkQueue<R extends Runnable> extends LinkedBlockingQueue<Runnable> {

    static final ThreadLocal<Boolean> IN_REJECT_CONTEXT =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void enterRejectContext() {
        IN_REJECT_CONTEXT.set(Boolean.TRUE);
    }

    public static void exitRejectContext() {
        IN_REJECT_CONTEXT.remove();
    }

    public static boolean isInRejectContext() {
        return Boolean.TRUE.equals(IN_REJECT_CONTEXT.get());
    }

    private EagerThreadPoolExecutor executor;

    public void setExecutor(EagerThreadPoolExecutor executor) {
        this.executor = executor;
    }

    public WorkQueue(int capacity) {
        super(capacity);
    }

    @Override
    public boolean offer(Runnable task) {

        if (executor == null) return super.offer(task);

        if (isInRejectContext() && !EagerThreadPoolExecutor.isInExecuteContext()){
            boolean ok = super.offer(task);
            if (ok) executor.adjustSubmittedTaskCount(1);
            return ok;
        }

        int poolSize = executor.getPoolSize();

        if (poolSize > executor.getSubmittedTaskCount()){
            return super.offer(task);
        }

        if (poolSize < executor.getMaximumPoolSize()){
            return false;
        }
        return super.offer(task);
    }

    @Override
    public Runnable poll(){
        Runnable r = super.poll();
        if (r != null && executor != null && isInRejectContext()){
            executor.adjustSubmittedTaskCount(-1);
        }
        return r;
    }

    @Override
    public boolean remove(Object o) {
        boolean removed = super.remove(o);
        if (removed && executor != null) {
            executor.adjustSubmittedTaskCount(-1);
        }
        return removed;
    }

    @Override
    public void clear() {
        int n = size();
        super.clear();
        if (n > 0 && executor != null) {
            executor.adjustSubmittedTaskCount(-n);
        }
    }

    @Override
    public int drainTo(Collection<? super Runnable> c) {
        int n = super.drainTo(c);
        if (n > 0 && executor != null) {
            executor.adjustSubmittedTaskCount(-n);
        }
        return n;
    }

    @Override
    public int drainTo(Collection<? super Runnable> c, int maxElements) {
        int n = super.drainTo(c, maxElements);
        if (n > 0 && executor != null) {
            executor.adjustSubmittedTaskCount(-n);
        }
        return n;
    }

    public boolean retryOffer(Runnable task, long timeout, TimeUnit unit) throws InterruptedException {
        if (executor != null && executor.isShutdown()) {
            throw new RejectedExecutionException("executor is shutdown");
        }
        return super.offer(task, timeout, unit);
    }
}
