package com.dstcar.nlsql.dbmcp.tools;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 极简 TTL 缓存:缓存 list_tables/describe_table 等 schema 元数据,减少对只读库的重复
 * INFORMATION_SCHEMA 查询。库结构低频变化,可接受按 TTL 过期。
 *
 * 零依赖(不引入 Caffeine):ConcurrentHashMap + 过期时间戳;ttlSeconds <= 0 时直通不缓存。
 * 过期项惰性淘汰(下次 get 时重新加载)。非原子"缓存雪崩"风险可忽略(schema 查询廉价)。
 */
@Component
public final class SchemaCache {

    private final ConcurrentMap<String, Entry> store = new ConcurrentHashMap<>();

    /** ttlSeconds <= 0 表示关闭缓存:不读不写,直接执行 loader。 */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, long ttlSeconds, Supplier<T> loader) {
        if (ttlSeconds <= 0) {
            return loader.get();
        }
        long now = System.currentTimeMillis();
        Entry hit = store.get(key);
        if (hit != null && hit.expireAt > now) {
            return (T) hit.value;
        }
        T value = loader.get();
        store.put(key, new Entry(value, now + ttlSeconds * 1000L));
        return value;
    }

    private record Entry(Object value, long expireAt) {
    }
}
