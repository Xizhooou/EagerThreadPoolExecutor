package com.xizhooou;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.lang.reflect.Proxy;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.atomic.AtomicLong;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RejectedProxyUtil {

    public static RejectedExecutionHandler createProxy(RejectedExecutionHandler rejectedExecutionHandler, AtomicLong rejectedNum) {
        // 动态代理模式: 增强线程池拒绝策略，比如：拒绝任务报警或加入延迟队列重复放入等逻辑
        return (RejectedExecutionHandler) Proxy
                .newProxyInstance(
                        rejectedExecutionHandler.getClass().getClassLoader(),
                        new Class[]{RejectedExecutionHandler.class},
                        new RejectedProxyInvocationHandler(rejectedExecutionHandler, rejectedNum));
    }


}
