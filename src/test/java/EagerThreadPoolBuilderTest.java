import com.xizhooou.eagerthreadpool.EagerThreadPoolBuilder;
import com.xizhooou.eagerthreadpool.EagerThreadPoolExecutor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EagerThreadPoolBuilderTest {

    @Test
    void build_shouldCreateWorkingExecutor() throws Exception {
        EagerThreadPoolExecutor executor = EagerThreadPoolBuilder.newBuilder()
                .name("biz-pool")
                .corePoolSize(1)
                .maximumPoolSize(2)
                .queueCapacity(8)
                .prestartAllCoreThreads(true)
                .build();

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();

        executor.execute(() -> {
            threadName.set(Thread.currentThread().getName());
            done.countDown();
        });

        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertNotNull(threadName.get());
        assertTrue(threadName.get().startsWith("biz-pool-worker-"));

        executor.shutdownNow();
    }

    @Test
    void build_alertEnabledWithoutWebhook_shouldFailFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                EagerThreadPoolBuilder.newBuilder()
                        .alertEnabled(true)
                        .build()
        );

        assertTrue(ex.getMessage().contains("weComWebhookUrl"));
    }
}
