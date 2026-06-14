/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */

package org.openmrs.module.cdshooks.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Bounded LRU cache with per-entry TTL.
 *
 * <ul>
 *   <li><b>TTL</b>: entries are evicted on read once their expiry has elapsed.</li>
 *   <li><b>LRU</b>: when the entry count exceeds {@code maxEntries}, the
 *       least-recently-used entry is evicted on insert.</li>
 * </ul>
 *
 * <p>Suitable for terminology lookups whose source data rotates with the
 * SNOMED release cadence (months). The LRU bound protects against unbounded
 * growth — without it, a long-running service eventually OOMs the JVM under
 * sustained traffic that touches a wide working set of concepts.
 *
 * <p>Thread-safety: all operations are guarded by {@code synchronized(this)}.
 * The cache is sized in terms of distinct keys, not bytes; sizing assumes
 * cached values are modest (e.g., a short list of {@code CodedConcept}
 * records, or a single {@code SubsumptionOutcome}).
 */
public final class TtlCache<K, V> {

    /** Default cap when no override is supplied. */
    public static final int DEFAULT_MAX_ENTRIES = 10_000;

    private static final class Entry<V> {
        final V value;
        final long expiresAt;
        Entry(V value, long expiresAt) { this.value = value; this.expiresAt = expiresAt; }
    }

    private final Map<K, Entry<V>> map;
    private final long ttlMillis;
    private final int maxEntries;

    public TtlCache(long ttlMillis) {
        this(ttlMillis, DEFAULT_MAX_ENTRIES);
    }

    public TtlCache(long ttlMillis, int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be > 0");
        }
        this.ttlMillis = ttlMillis;
        this.maxEntries = maxEntries;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) {
                return size() > TtlCache.this.maxEntries;
            }
        };
    }

    public synchronized V get(K key, Function<K, V> loader) {
        long now = System.currentTimeMillis();
        Entry<V> existing = map.get(key); // access-order: touches recency
        if (existing != null && existing.expiresAt > now) {
            return existing.value;
        }
        V loaded = loader.apply(key);
        map.put(key, new Entry<>(loaded, now + ttlMillis));
        return loaded;
    }

    synchronized void invalidate() {
        map.clear();
    }

    /** Diagnostic-only — current entry count. */
    synchronized int size() {
        return map.size();
    }
}
