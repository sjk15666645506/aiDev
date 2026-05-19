package com.deepseek.demo.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地缓存，支持 TTL 过期和容量上限逐出。
 * 用于 Redis 降级场景，防止本地内存无限增长。
 */
public class LocalCache<K, V> {

    private static final Logger log = LoggerFactory.getLogger(LocalCache.class);

    private final int maxCapacity;
    private final long ttlMillis;
    private final ConcurrentHashMap<K, Entry<V>> cache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;
    private final AtomicInteger evictionCounter = new AtomicInteger();

    private static class Entry<V> {
        final V value;
        final long createdAt;

        Entry(V value) {
            this.value = value;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isExpired(long ttlMillis) {
            return System.currentTimeMillis() - createdAt > ttlMillis;
        }
    }

    /**
     * @param maxCapacity 最大条目数，超过时触发逐出
     * @param ttl         条目存活时间
     * @param ttlUnit     时间单位
     */
    public LocalCache(int maxCapacity, long ttl, TimeUnit ttlUnit) {
        this.maxCapacity = maxCapacity;
        this.ttlMillis = ttlUnit.toMillis(ttl);
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "local-cache-cleaner");
            t.setDaemon(true);
            return t;
        });
        long cleanupInterval = Math.max(ttlMillis / 4, 30_000);
        this.cleaner.scheduleAtFixedRate(this::cleanup, cleanupInterval, cleanupInterval, TimeUnit.MILLISECONDS);
    }

    public V get(K key) {
        Entry<V> entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(ttlMillis)) {
            cache.remove(key);
            return null;
        }
        return entry.value;
    }

    public void put(K key, V value) {
        if (cache.size() >= maxCapacity && !cache.containsKey(key)) {
            evictOne();
        }
        cache.put(key, new Entry<>(value));
    }

    public void remove(K key) {
        cache.remove(key);
    }

    public int size() {
        return cache.size();
    }

    private void evictOne() {
        for (K key : cache.keySet()) {
            if (cache.remove(key) != null) {
                int count = evictionCounter.incrementAndGet();
                log.info("本地缓存达到上限({})，已逐出条目: {}, 累计逐出: {}", maxCapacity, key, count);
                return;
            }
        }
    }

    private void cleanup() {
        int before = cache.size();
        cache.keySet().removeIf(key -> {
            Entry<V> entry = cache.get(key);
            return entry != null && entry.isExpired(ttlMillis);
        });
        int removed = before - cache.size();
        if (removed > 0) {
            log.debug("本地缓存清理: 移除 {} 个过期条目, 当前大小 {}", removed, cache.size());
        }
    }

    public void destroy() {
        cleaner.shutdown();
    }
}
