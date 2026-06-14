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

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class TtlCacheTest {

    @Test
    public void loaderInvokedOnceWhenWarm() {
        TtlCache<String, String> cache = new TtlCache<>(60_000);
        AtomicInteger calls = new AtomicInteger();

        cache.get("k", key -> { calls.incrementAndGet(); return "v"; });
        cache.get("k", key -> { calls.incrementAndGet(); return "v"; });
        cache.get("k", key -> { calls.incrementAndGet(); return "v"; });

        assertThat(calls.get(), is(1));
    }

    @Test
    public void loaderInvokedAgainAfterTtlExpires() throws InterruptedException {
        TtlCache<String, String> cache = new TtlCache<>(10); // 10 ms
        AtomicInteger calls = new AtomicInteger();

        cache.get("k", key -> { calls.incrementAndGet(); return "v"; });
        Thread.sleep(30);
        cache.get("k", key -> { calls.incrementAndGet(); return "v"; });

        assertThat(calls.get(), is(2));
    }

    @Test
    public void differentKeysAreSeparate() {
        TtlCache<String, String> cache = new TtlCache<>(60_000);
        AtomicInteger calls = new AtomicInteger();

        cache.get("a", k -> { calls.incrementAndGet(); return "alpha"; });
        cache.get("b", k -> { calls.incrementAndGet(); return "beta"; });
        cache.get("a", k -> { calls.incrementAndGet(); return "alpha"; });

        assertThat(calls.get(), is(2));
    }

    @Test
    public void invalidateRemovesAllEntries() {
        TtlCache<String, String> cache = new TtlCache<>(60_000);
        AtomicInteger calls = new AtomicInteger();

        cache.get("k", key -> { calls.incrementAndGet(); return "v"; });
        cache.invalidate();
        cache.get("k", key -> { calls.incrementAndGet(); return "v"; });

        assertThat(calls.get(), is(2));
    }

    @Test
    public void cachesNullValues() {
        // A loader that returned null shouldn't get re-invoked on every call —
        // null is a legitimate cached result for "no SNOMED mapping".
        TtlCache<String, String> cache = new TtlCache<>(60_000);
        AtomicInteger calls = new AtomicInteger();

        cache.get("k", key -> { calls.incrementAndGet(); return null; });
        cache.get("k", key -> { calls.incrementAndGet(); return null; });

        assertThat(calls.get(), is(1));
    }

    @Test
    public void respectsMaxEntriesBound() {
        // Cap at 3 entries; insert 5 distinct keys.
        TtlCache<String, String> cache = new TtlCache<>(60_000, 3);
        for (int i = 0; i < 5; i++) {
            final int v = i;
            cache.get("k" + v, k -> "v" + v);
        }
        assertThat(cache.size(), is(3));
    }

    @Test
    public void lruEvictionRetainsRecentlyAccessed() {
        TtlCache<String, String> cache = new TtlCache<>(60_000, 2);
        cache.get("a", k -> "alpha");
        cache.get("b", k -> "beta");
        // Touch a to make it most recently used. Insert c — b is the LRU and
        // should be evicted, not a.
        cache.get("a", k -> { throw new AssertionError("should be cached"); });
        cache.get("c", k -> "charlie");

        AtomicInteger calls = new AtomicInteger();
        // a still cached
        cache.get("a", k -> { calls.incrementAndGet(); return "alpha"; });
        // c still cached
        cache.get("c", k -> { calls.incrementAndGet(); return "charlie"; });
        // b evicted — loader fires
        cache.get("b", k -> { calls.incrementAndGet(); return "beta"; });

        assertThat(calls.get(), is(1));
        assertThat(cache.size(), is(2));
    }

    @Test
    public void invalidMaxEntries_rejected() {
        try {
            new TtlCache<String, String>(60_000, 0);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ignored) { /* ok */ }
        try {
            new TtlCache<String, String>(60_000, -1);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ignored) { /* ok */ }
    }
}
