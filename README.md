# EagerThreadPoolExecutor

Eager 线程池工具库，通过 `EagerThreadPoolBuilder` 对外构建线程池。

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

### 4. 使用企业微信webhook报警
```java
void test() throws Exception {
  String webhookUrl = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=" + "YOUR_KEY";
  AtomicLong rejectedNum = new AtomicLong(0);
  EagerThreadPoolExecutor ex = EagerThreadPoolBuilder.newBuilder()
          .name("alert-test")
          .corePoolSize(1)
          .maximumPoolSize(1)
          .queueCapacity(1)
          .keepAlive(30, TimeUnit.SECONDS)
          .threadFactory(namedFactory("alert"))
          .rejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy())
          .rejectedCounter(rejectedNum)
          .alertEnabled(true)
          .weComWebhookUrl(webhookUrl)
          .thresholdPerMinute(1)
          .cooldownSeconds(0)
          .build();

  CountDownLatch blocker = new CountDownLatch(1);
  try {
    ex.execute(() -> {
      try {
        blocker.await();
      } catch (InterruptedException ignored) {}
    });

    waitUntil(() -> ex.getPoolSize() == 1, 1500, "poolSize didn't reach 1");

    // 队列填满
    ex.execute(() -> {});
    // 触发拒绝，触发告警
    assertThrows(RejectedExecutionException.class, () -> ex.execute(() -> {}));

    waitUntil(() -> ex.getRejectedInLastWindow() >= 1, 1500, "alert counter didn't increase");
    Thread.sleep(1000);
    assertTrue(rejectedNum.get() >= 1, "rejectedNum should increase");
  } finally {
    shutdownAndAwait(ex, blocker);
  }
}
```
