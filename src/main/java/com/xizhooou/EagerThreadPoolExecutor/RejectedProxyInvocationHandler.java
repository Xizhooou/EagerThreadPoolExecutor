package com.xizhooou.EagerThreadPoolExecutor;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.atomic.AtomicLong;

public class RejectedProxyInvocationHandler implements InvocationHandler {

    private final RejectedExecutionHandler target;
    private final AtomicLong rejectCount;
    private final EagerThreadPoolExecutor executor;

    public RejectedProxyInvocationHandler(RejectedExecutionHandler target,
                                          AtomicLong rejectCount,
                                          EagerThreadPoolExecutor executor) {
        this.target = target;
        this.rejectCount = rejectCount;
        this.executor = executor;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 非 rejectedExecution 方法：不要统计 rejectCount，直接透传
        if (!"rejectedExecution".equals(method.getName())) {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        }

        rejectCount.incrementAndGet();

        boolean handlerReturnedNormally = false;
        WorkQueue.enterRejectContext();
        try {
            Object res = method.invoke(target, args);
            handlerReturnedNormally = true;
            return res;
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        } finally {
            WorkQueue.exitRejectContext();
             // - 若拒绝策略抛异常（AbortPolicy），execute() 会 catch RejectedExecutionException 并走 retryOffer/补偿。
             // - 若拒绝策略不抛异常（CallerRuns/Discard/DiscardOldest/自定义），execute() 不会 catch，afterExecute 也不会跑，
             //   补回这次 execute() 的 +1。
            if (handlerReturnedNormally && executor != null) {
                executor.adjustSubmittedTaskCount(-1);
            }
        }
    }
}
