package io.quarkiverse.onepassword;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CachingOnePasswordResolverTest {
    @Test void concurrentRequestsShareOneLookup() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var cache = new CachingOnePasswordResolver(ref -> "value-" + calls.incrementAndGet(), 256);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<Future<String>>();
            for (int i=0; i<32; i++) futures.add(pool.submit(() -> cache.resolve("op://v/i/p")));
            for (var f : futures) assertEquals("value-1", f.get(3, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally { pool.shutdownNow(); }
    }
    @Test void failuresAreRetried() {
        AtomicInteger calls = new AtomicInteger();
        var cache = new CachingOnePasswordResolver(ref -> {
            if (calls.incrementAndGet() == 1) throw new OnePasswordException("Denied");
            return "ok";
        }, 2);
        assertThrows(OnePasswordException.class, () -> cache.resolve("op://v/i/p"));
        assertEquals("ok", cache.resolve("op://v/i/p"));
        assertEquals(2, calls.get());
    }
    @Test void evictsLeastRecentlyUsedAndDoesNotShareAcrossInstances() {
        AtomicInteger calls = new AtomicInteger();
        OnePasswordResolver delegate = ref -> "v" + calls.incrementAndGet();
        var cache = new CachingOnePasswordResolver(delegate, 2);
        assertEquals("v1", cache.resolve("op://v/i/a"));
        cache.resolve("op://v/i/b");
        assertEquals("v1", cache.resolve("op://v/i/a"));
        cache.resolve("op://v/i/c");
        assertEquals("v4", cache.resolve("op://v/i/b"));
        assertEquals("v5", new CachingOnePasswordResolver(delegate, 2).resolve("op://v/i/a"));
    }
}
