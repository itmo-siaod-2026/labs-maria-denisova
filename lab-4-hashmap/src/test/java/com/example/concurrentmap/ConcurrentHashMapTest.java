package com.example.concurrentmap;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;

class ConcurrentHashMapTest {

    @Test
    void basicPutGet() {
        ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
        assertNull(map.put("one", 1));
        assertEquals(1, map.get("one"));
        assertEquals(1, map.put("one", 2)); // обновление
        assertEquals(2, map.get("one"));
    }

    @Test
    void putIfAbsent() {
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
        assertNull(map.putIfAbsent("key", "first"));
        assertEquals("first", map.putIfAbsent("key", "second"));
        assertEquals("first", map.get("key"));
    }

    @Test
    void merge() {
        ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
        map.put("a", 10);
        BiFunction<Integer, Integer, Integer> sum = Integer::sum;
        assertEquals(25, map.merge("a", 15, sum));
        assertEquals(25, map.get("a"));
        // ключ отсутствует
        assertEquals(7, map.merge("b", 7, sum));
        assertEquals(7, map.get("b"));
    }

    @Test
    void mergeRemovesWhenRemapperReturnsNull() {
        ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
        map.put("a", 10);
        assertNull(map.merge("a", 1, (oldValue, newValue) -> null));
        assertNull(map.get("a"));
        assertEquals(0, map.size());
    }

    @Test
    void rejectsNullKeyOrValueLikeJdkConcurrentHashMap() {
        ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
        assertThrows(NullPointerException.class, () -> map.get(null));
        assertThrows(NullPointerException.class, () -> map.put(null, 1));
        assertThrows(NullPointerException.class, () -> map.put("a", null));
        assertThrows(NullPointerException.class, () -> map.putIfAbsent("a", null));
        assertThrows(NullPointerException.class, () -> map.merge("a", null, Integer::sum));
    }

    @Test
    void sizeAndClear() {
        ConcurrentHashMap<Integer, String> map = new ConcurrentHashMap<>();
        for (int i = 0; i < 100; i++) {
            map.put(i, "v" + i);
        }
        assertEquals(100, map.size());
        map.clear();
        assertEquals(0, map.size());
    }

    @Test
    void iterator() {
        ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
        map.put("x", 1);
        map.put("y", 2);
        map.put("z", 3);
        Map<String, Integer> collected = new HashMap<>();
        for (Iterator<Map.Entry<String, Integer>> it = map.iterator(); it.hasNext(); ) {
            Map.Entry<String, Integer> e = it.next();
            collected.put(e.getKey(), e.getValue());
        }
        assertEquals(Map.of("x",1,"y",2,"z",3), collected);
    }

    @Test
    void concurrentPuts() throws Exception {
        ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();
        int threads = 4;
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int start = t * 1000;
            futures.add(pool.submit(() -> {
                try { latch.await(); } catch (InterruptedException ignored) {}
                for (int i = 0; i < 1000; i++) {
                    map.put(start + i, i);
                }
            }));
        }
        latch.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        assertEquals(4000, map.size());
        for (int i = 0; i < 4000; i++) {
            assertNotNull(map.get(i));
        }
    }
}
