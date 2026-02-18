package com.xizhooou.eagerthreadpool.reject;

import com.xizhooou.eagerthreadpool.EagerThreadPoolExecutor;
import com.xizhooou.eagerthreadpool.alert.RejectAlertConfig;
import com.xizhooou.eagerthreadpool.alert.RejectAlertState;

import java.lang.reflect.Proxy;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.atomic.AtomicLong;

public final class RejectedProxyUtil {

    private RejectedProxyUtil() {
    }

    public static RejectedExecutionHandler createProxy(RejectedExecutionHandler rejectedExecutionHandler,
                                                       AtomicLong rejectedNum,
                                                       EagerThreadPoolExecutor executor,
                                                       String poolName,
                                                       RejectAlertConfig alertConfig,
                                                       RejectAlertState alertState) {
        // 动态代理模式: 增强线程池拒绝策略，比如：拒绝任务报警或加入延迟队列重复放入等逻辑
        RejectedExecutionHandler target = rejectedExecutionHandler != null
                ? rejectedExecutionHandler
                : new java.util.concurrent.ThreadPoolExecutor.AbortPolicy();

        return (RejectedExecutionHandler) Proxy.newProxyInstance(
                target.getClass().getClassLoader(),
                new Class[]{RejectedExecutionHandler.class},
                new RejectedProxyInvocationHandler(target, rejectedNum, executor, poolName, alertConfig, alertState)
        );
    }

    public static RejectedExecutionHandler createProxy(RejectedExecutionHandler rejectedExecutionHandler,
                                                       AtomicLong rejectedNum,
                                                       EagerThreadPoolExecutor executor) {
        return createProxy(rejectedExecutionHandler, rejectedNum, executor, "eager", null, null);
    }
}
