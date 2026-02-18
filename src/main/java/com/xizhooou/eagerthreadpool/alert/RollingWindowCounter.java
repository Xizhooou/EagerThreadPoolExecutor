package com.xizhooou.eagerthreadpool.alert;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 分桶 ring buffer 的滑动窗口计数器
 * - windowSeconds=60, bucketSeconds=5 => 12 buckets
 * - increment(): 当前桶 +1
 * - sumLastWindow(): 求最近窗口内的和
 */
public class RollingWindowCounter {
    private final int buckets;
    private final int bucketSeconds;
    private final long windowSlots;

    private final AtomicLongArray slot;   // 每个桶对应的 timeSlot
    private final AtomicLongArray count;  // 每个桶的计数

    public RollingWindowCounter(int windowSeconds, int bucketSeconds) {
        if (windowSeconds <= 0) {
            windowSeconds = 60;
        }
        if (bucketSeconds <= 0) {
            bucketSeconds = 5;
        }
        if (windowSeconds % bucketSeconds != 0) {
            // 简化：不整除就默认回退
            windowSeconds = 60;
            bucketSeconds = 5;
        }

        this.buckets = windowSeconds / bucketSeconds;
        this.bucketSeconds = bucketSeconds;
        this.windowSlots = buckets;

        this.slot = new AtomicLongArray(buckets);
        this.count = new AtomicLongArray(buckets);
        for (int i = 0; i < buckets; i++) {
            slot.set(i, -1);
            count.set(i, 0);
        }
    }

    public void increment() {
        long nowSec = System.currentTimeMillis() / 1000L;
        long nowSlot = nowSec / bucketSeconds;
        int idx = (int) (nowSlot % buckets);

        long prev = slot.get(idx);
        if (prev != nowSlot && slot.compareAndSet(idx, prev, nowSlot)) {
            count.set(idx, 0);
        }
        count.incrementAndGet(idx);
    }

    public long sumLastWindow() {
        long nowSec = System.currentTimeMillis() / 1000L;
        long nowSlot = nowSec / bucketSeconds;

        long sum = 0;
        for (int i = 0; i < buckets; i++) {
            long s = slot.get(i);
            if (s < 0) {
                continue;
            }
            long age = nowSlot - s;
            if (age >= 0 && age < windowSlots) {
                sum += count.get(i);
            }
        }
        return sum;
    }
}
