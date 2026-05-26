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
}
