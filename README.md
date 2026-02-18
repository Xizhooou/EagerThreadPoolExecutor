# EagerThreadPoolExecutor

一个可工程化复用的 Eager 线程池工具库，推荐通过 `EagerThreadPoolBuilder` 对外构建线程池。

## Quick Start

### 1. 引入依赖

```xml
<dependency>
  <groupId>com.xizhooou</groupId>
  <artifactId>EagerThreadPoolExecutor</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

### 2. 通过 Builder 创建线程池

```java
import com.xizhooou.eagerthreadpool.EagerThreadPoolBuilder;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

AtomicLong rejectedCounter = new AtomicLong();

EagerThreadPoolExecutor executor = EagerThreadPoolBuilder.newBuilder()
        .name("biz-pool")
        .corePoolSize(8)
        .maximumPoolSize(32)
        .queueCapacity(1024)
        .keepAlive(60, TimeUnit.SECONDS)
        .rejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy())
        .rejectedCounter(rejectedCounter)
        .retryOfferTimeout(50, TimeUnit.MILLISECONDS)
        .prestartAllCoreThreads(true)
        .build();
```

### 3. 可选：开启拒绝告警

```java
EagerThreadPoolExecutor executor = EagerThreadPoolBuilder.newBuilder()
        .name("biz-pool")
        .corePoolSize(8)
        .maximumPoolSize(32)
        .queueCapacity(1024)
        .alertEnabled(true)
        .weComWebhookUrl("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=YOUR_KEY")
        .thresholdPerMinute(200)
        .cooldownSeconds(60)
        .build();
```
