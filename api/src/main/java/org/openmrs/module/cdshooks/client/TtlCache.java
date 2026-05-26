package org.openmrs.module.cdshooks.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Minimal time-to-live cache. Entries are evicted on read once their TTL has
 * elapsed. Suitable for terminology lookups whose source data rotates with
 * the SNOMED release cadence (months).
 */
final class TtlCache<K, V> {

    private static final class Entry<V> {
        final V value;
        final long expiresAt;
        Entry(V value, long expiresAt) { this.value = value; this.expiresAt = expiresAt; }
    }

    private final Map<K, Entry<V>> map = new ConcurrentHashMap<>();
    private final long ttlMillis;

    TtlCache(long ttlMillis) { this.ttlMillis = ttlMillis; }

    V get(K key, Function<K, V> loader) {
        long now = System.currentTimeMillis();
        Entry<V> existing = map.get(key);
        if (existing != null && existing.expiresAt > now) {
            return existing.value;
        }
        V loaded = loader.apply(key);
        map.put(key, new Entry<>(loaded, now + ttlMillis));
        return loaded;
    }

    void invalidate() {
        map.clear();
    }
}
