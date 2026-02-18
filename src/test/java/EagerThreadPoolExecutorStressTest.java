import com.xizhooou.eagerthreadpool.EagerThreadPoolBuilder;
import com.xizhooou.eagerthreadpool.EagerThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EagerThreadPoolExecutorStressTest {

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r);
            t.setName(prefix + "-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }

    @Test
    @Timeout(45)
    void brutalStress_abortPolicy_shouldKeepPoolStateConsistent() throws Exception {
        int producerThreads = Math.max(16, Runtime.getRuntime().availableProcessors() * 2);
        int tasksPerProducer = 4_000;
        int totalSubmitAttempts = producerThreads * tasksPerProducer;

        AtomicLong rejectedNum = new AtomicLong(0);
        EagerThreadPoolExecutor executor = EagerThreadPoolBuilder.newBuilder()
                .name("stress-pool")
                .corePoolSize(4)
                .maximumPoolSize(8)
                .queueCapacity(32)
                .keepAlive(30, TimeUnit.SECONDS)
                .retryOfferTimeout(0, TimeUnit.MILLISECONDS)
                .threadFactory(namedFactory("stress-worker"))
                .rejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy())
                .rejectedCounter(rejectedNum)
                .build();

        ExecutorService submitters = Executors.newFixedThreadPool(producerThreads, namedFactory("stress-submitter"));
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch producerDone = new CountDownLatch(producerThreads);

        AtomicInteger accepted = new AtomicInteger(0);
        AtomicInteger rejectedByCaller = new AtomicInteger(0);
        AtomicInteger executed = new AtomicInteger(0);

        Runnable task = () -> {
            executed.incrementAndGet();
            LockSupport.parkNanos(200_000L);
        };

        try {
            for (int i = 0; i < producerThreads; i++) {
                submitters.execute(() -> {
                    try {
                        startGate.await();
                        for (int j = 0; j < tasksPerProducer; j++) {
                            try {
                                executor.execute(task);
                                accepted.incrementAndGet();
                            } catch (RejectedExecutionException ex) {
                                rejectedByCaller.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        producerDone.countDown();
                    }
                });
            }

            startGate.countDown();
            assertTrue(producerDone.await(25, TimeUnit.SECONDS), "submitters did not finish in time");

            submitters.shutdown();
            assertTrue(submitters.awaitTermination(5, TimeUnit.SECONDS), "submitter pool did not terminate");

            executor.shutdown();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS), "worker pool did not terminate");
        } finally {
            submitters.shutdownNow();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertEquals(totalSubmitAttempts, accepted.get() + rejectedByCaller.get(), "submit result accounting mismatch");
        assertEquals(accepted.get(), executed.get(), "accepted tasks should all execute");
        assertEquals(executed.get(), executor.getCompletedTaskCount(), "completedTaskCount mismatch");
        assertEquals(0, executor.getSubmittedTaskCount(), "submittedTaskCount leaked after stress run");
        assertTrue(rejectedNum.get() >= rejectedByCaller.get(), "internal reject count should cover thrown rejects");
    }
}
