import com.xizhooou.eagerthreadpool.EagerThreadPoolBuilder;
import com.xizhooou.eagerthreadpool.EagerThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

public class EagerThreadPoolExecutorTest {

    private static int submittedCount(EagerThreadPoolExecutor ex) {
        try {
            Method m = ex.getClass().getMethod("getSubmittedTaskCount");
            return (int) m.invoke(ex);
        } catch (NoSuchMethodException ignore) {
            try {
                Method m = ex.getClass().getMethod("getCoreThreadCount");
                return (int) m.invoke(ex);
            } catch (Exception e) {
                throw new RuntimeException("Cannot read submitted count", e);
            }
        } catch (Exception e) {
            throw new RuntimeException("Cannot read submitted count", e);
        }
    }

    private static ThreadFactory namedFactory(String prefix){
        AtomicInteger seq = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r);
            t.setName(prefix + "-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }


    private static void waitUntil(BooleanSupplier condition, long timeoutMs, String msg) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        }
        fail("Timeout: " + msg);
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }

    private static EagerThreadPoolExecutor newExecutor(int core, int max, int queueCap, RejectedExecutionHandler handler, AtomicLong rejectedNum) {
        return EagerThreadPoolBuilder.newBuilder()
                .name("eager-test")
                .corePoolSize(core)
                .maximumPoolSize(max)
                .queueCapacity(queueCap)
                .keepAlive(30, TimeUnit.SECONDS)
                .threadFactory(namedFactory("eager"))
                .rejectedExecutionHandler(handler)
                .rejectedCounter(rejectedNum)
                .build();
    }


    /** 占满 max 个 worker 线程：每个任务都阻塞在 latch 上 */
    private static void occupyMaxThreads(EagerThreadPoolExecutor ex, int maxThreads, CountDownLatch blocker, long timeoutMs) {
        CountDownLatch latch = new CountDownLatch(maxThreads);
        for (int i = 0; i < maxThreads; i++){
            ex.execute(() -> {
                latch.countDown();
                try {
                    blocker.await();
                } catch (InterruptedException e) {}
            });
        }
        try {
            assertTrue(latch.await(timeoutMs, TimeUnit.MILLISECONDS), "Executor didn't start all threads");
        }catch (InterruptedException e){
            throw new RuntimeException(e);
        }
        waitUntil(() -> ex.getPoolSize() == maxThreads, timeoutMs, "poolSize didn't reach max");
    }

    private static void shutdownAndAwait(EagerThreadPoolExecutor ex, CountDownLatch blocker) {
        blocker.countDown();
        ex.shutdown();
        try {
            if (!ex.awaitTermination(3, TimeUnit.SECONDS)) {
                ex.shutdownNow();
                assertTrue(ex.awaitTermination(3, TimeUnit.SECONDS), "Executor didn't terminate");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ex.shutdownNow();
        }
    }

    /**
     * AbortPolicy: 当 max 线程满 + 队列满, 再提交抛 RejectedExecutionException
     * etryOffer() 失败，submittedCount 正常--。
     */
    @Test
    @Timeout(8)
    void abortPolicy_reject_shouldNotLeakSubmittedCount() {
        AtomicLong rejectedNum = new AtomicLong(0);
        EagerThreadPoolExecutor ex = newExecutor(1, 2, 1, new ThreadPoolExecutor.AbortPolicy(), rejectedNum);

        CountDownLatch blocker = new CountDownLatch(1);
        try {
            occupyMaxThreads(ex, 2, blocker, 1500);

            // 填满队列(容量=1)
            ex.execute(() -> { /* queued */ });

            // 再提交一次：队列满 + max=2 -> 拒绝
            assertThrows(RejectedExecutionException.class, () -> ex.execute(() -> {}));

        } finally {
            shutdownAndAwait(ex, blocker);
        }

        assertEquals(0, submittedCount(ex), "submittedCount leaked after AbortPolicy rejection");
        assertTrue(rejectedNum.get() >= 1, "rejectedNum should increase");
    }

    /**
     * CallerRunsPolicy：不抛异常，任务会在提交线程执行。
     * submittedCount 正常--。
     */
    @Test
    @Timeout(8)
    void callerRunsPolicy_shouldNotLeakSubmittedCount_andRunsInCallerThread() {
        AtomicLong rejectedNum = new AtomicLong(0);
        EagerThreadPoolExecutor ex = newExecutor(1, 2, 1, new ThreadPoolExecutor.CallerRunsPolicy(), rejectedNum);

        CountDownLatch blocker = new CountDownLatch(1);
        AtomicBoolean ranInCaller = new AtomicBoolean(false);

        try {
            occupyMaxThreads(ex, 2, blocker, 1500);

            // 填满队列
            ex.execute(() -> { /* queued */ });

            // 触发拒绝：CallerRuns 会在当前测试线程执行
            String callerThreadName = Thread.currentThread().getName();
            ex.execute(() -> ranInCaller.set(Thread.currentThread().getName().equals(callerThreadName)));

            assertTrue(ranInCaller.get(), "CallerRunsPolicy should run task in caller thread");
        } finally {
            shutdownAndAwait(ex, blocker);
        }
        assertEquals(0, submittedCount(ex), "submittedCount leaked under CallerRunsPolicy (need route B compensation)");
    }


    @Test
    @Timeout(8)
    void discardPolicy_shouldNotLeakSubmittedCount_andTaskDiscarded(){
        AtomicLong rejectedNum = new AtomicLong(0);
        EagerThreadPoolExecutor ex = newExecutor(1, 2, 1, new ThreadPoolExecutor.DiscardPolicy(), rejectedNum);
        CountDownLatch blocker = new CountDownLatch(1);
        AtomicBoolean shouldNotRun = new AtomicBoolean(false);
        try {
            occupyMaxThreads(ex, 2, blocker, 1500);

            ex.execute(() -> { /* queued */ });

            ex.execute(() -> shouldNotRun.set(true));
        }finally {
            shutdownAndAwait(ex, blocker);
        }

        assertFalse(shouldNotRun.get(), "DiscardPolicy should discard task");
        assertEquals(0, submittedCount(ex), "submittedCount leaked under DiscardPolicy (need route B compensation)");
    }

    @Test
    @Timeout(10)
    void discardOldestPolicy_shouldDiscardOldest_andNotLeakSubmittedCount() {
        AtomicLong rejectedNum = new AtomicLong(0);
        EagerThreadPoolExecutor ex = newExecutor(1, 2, 1, new ThreadPoolExecutor.DiscardOldestPolicy(), rejectedNum);

        CountDownLatch blocker = new CountDownLatch(1);
        AtomicBoolean oldestRan = new AtomicBoolean(false);
        AtomicBoolean newestRan = new AtomicBoolean(false);

        try {
            occupyMaxThreads(ex, 2, blocker, 1500);

            Runnable oldest = () -> oldestRan.set(true);
            Runnable newest = () -> newestRan.set(true);

            // 先把 oldest 塞进队列（队列容量=1）
            ex.execute(oldest);

            // 再提交 newest：触发 DiscardOldest -> oldest 应被丢弃，newest 被重新提交并最终执行
            ex.execute(newest);

        } finally {
            shutdownAndAwait(ex, blocker);
        }

        assertFalse(oldestRan.get(), "Oldest queued task should be discarded");
        assertTrue(newestRan.get(), "Newest task should eventually run");
        assertEquals(0, submittedCount(ex), "submittedCount leaked under DiscardOldestPolicy (need poll/remove compensation)");
    }

    @Test
    @Timeout(10)
    void interruptedDuringRetryOffer_shouldPreserveInterrupt_andPoolStillWorks() {
        AtomicLong rejectedNum = new AtomicLong(0);
        EagerThreadPoolExecutor ex = newExecutor(1, 2, 1, new ThreadPoolExecutor.AbortPolicy(), rejectedNum);

        CountDownLatch blocker = new CountDownLatch(1);
        AtomicBoolean interruptedAfter = new AtomicBoolean(false);
        AtomicBoolean poolStillRuns = new AtomicBoolean(false);

        try {
            occupyMaxThreads(ex, 2, blocker, 1500);
            ex.execute(() -> { /* queued */ });

            // 用单独线程当“提交线程”，提前 interrupt 自己
            Thread submitter = new Thread(() -> {
                Thread.currentThread().interrupt();
                try {
                    // 这里会触发拒绝 -> retryOffer() -> 由于线程已中断，可能抛 InterruptedException 路径
                    ex.execute(() -> {});
                    fail("Expected RejectedExecutionException");
                } catch (RejectedExecutionException expected) {
                    interruptedAfter.set(Thread.currentThread().isInterrupted());
                }
            }, "submitter");
            submitter.start();
            submitter.join(2000);
            assertFalse(submitter.isAlive(), "submitter thread hung");

            // 放开 worker，让线程池继续工作，然后提交一个能跑的任务验证池没死
            blocker.countDown();
            CountDownLatch done = new CountDownLatch(1);
            ex.execute(() -> { poolStillRuns.set(true); done.countDown(); });
            assertTrue(done.await(2, TimeUnit.SECONDS), "pool didn't run task after interrupt scenario");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        } finally {
            ex.shutdownNow();
            try { ex.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }

        assertTrue(poolStillRuns.get(), "executor should still run tasks; interrupt should not kill the pool");
        // 验证恢复中断标记
        assertTrue(interruptedAfter.get(), "should preserve interrupt flag after catching InterruptedException (recommended)");
    }

    @Test
    @Timeout(10)
    void removeQueuedTask_shouldDecreaseSubmittedCount_ifYouImplementedQueueCompensation() {
        AtomicLong rejectedNum = new AtomicLong(0);
        // core=max=1，让后续任务必定排队
        EagerThreadPoolExecutor ex = newExecutor(1, 1, 10, new ThreadPoolExecutor.AbortPolicy(), rejectedNum);

        CountDownLatch blocker = new CountDownLatch(1);
        AtomicBoolean r1Ran = new AtomicBoolean(false);
        AtomicBoolean r2Ran = new AtomicBoolean(false);

        Runnable blockerTask = () -> {
            try { blocker.await(); } catch (InterruptedException ignored) {}
        };
        Runnable r1 = () -> r1Ran.set(true);
        Runnable r2 = () -> r2Ran.set(true);

        try {
            ex.execute(blockerTask);

            // 这两个会进队列
            ex.execute(r1);
            ex.execute(r2);

            // 等待 r1 真的进队列
            waitUntil(() -> ex.getQueue().contains(r1), 1500, "r1 not in queue");

            int before = submittedCount(ex);
            boolean removed = ex.remove(r1);
            assertTrue(removed, "executor.remove(r1) should succeed");

            int after = submittedCount(ex);

            // 应当 after == before - 1
            assertEquals(before - 1, after, "submittedCount should decrease when queued task removed (route B queue compensation)");

        } finally {
            shutdownAndAwait(ex, blocker);
        }

        assertFalse(r1Ran.get(), "removed queued task should not run");
        assertTrue(r2Ran.get(), "other queued task should run");
        assertEquals(0, submittedCount(ex), "submittedCount should end at 0");
    }

    @Test
    @Timeout(10)
    void webhookAlert_shouldSendToWeComWebhook_whenRejectThresholdReached() throws Exception {
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

}
