package com.dstcar.nlsql.dbmcp.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 覆盖 SchemaCache 的 TTL 命中/过期/关闭/隔离(纯逻辑,不连库)。 */
class SchemaCacheTest {

    @Test
    void ttlZero_bypassesCache_everyCallLoads() {
        SchemaCache cache = new SchemaCache();
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            cache.get("k", 0, () -> { calls.incrementAndGet(); return List.of("v"); });
        }
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void negativeTtl_bypassesCache_likeZero() {
        SchemaCache cache = new SchemaCache();
        AtomicInteger calls = new AtomicInteger();
        cache.get("k", -5, () -> { calls.incrementAndGet(); return "v"; });
        cache.get("k", -5, () -> { calls.incrementAndGet(); return "v"; });
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void hitWithinTtl_returnsCached_loadsOnce() {
        SchemaCache cache = new SchemaCache();
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            cache.get("k", 60, () -> { calls.incrementAndGet(); return List.of("v"); });
        }
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void differentKeys_areIsolated() {
        SchemaCache cache = new SchemaCache();
        AtomicInteger calls = new AtomicInteger();
        String a = cache.get("a", 60, () -> { calls.incrementAndGet(); return "A"; });
        String b = cache.get("b", 60, () -> { calls.incrementAndGet(); return "B"; });
        // 命中缓存不参与,仅两个不同 key 各加载一次
        cache.get("a", 60, () -> { calls.incrementAndGet(); return "A2"; });
        assertThat(a).isEqualTo("A");
        assertThat(b).isEqualTo("B");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void expired_reloadsAfterTtl() throws InterruptedException {
        SchemaCache cache = new SchemaCache();
        AtomicInteger calls = new AtomicInteger();
        Integer first = cache.get("k", 1, () -> { calls.incrementAndGet(); return calls.get(); });
        Thread.sleep(1100); // 等待 TTL(1s) 过期
        Integer second = cache.get("k", 1, () -> { calls.incrementAndGet(); return calls.get(); });
        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2); // 过期后重新加载
        assertThat(calls.get()).isEqualTo(2);
    }
}
