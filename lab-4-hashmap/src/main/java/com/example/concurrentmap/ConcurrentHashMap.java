package com.example.concurrentmap;

import java.lang.invoke.VarHandle;
import java.util.AbstractMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

/**
 * Потокобезопасная хеш-таблица с закрытой адресацией (separate chaining).
 * Основана на сегментированных блокировках: операции чтения выполняются
 * без захвата блокировок, операции записи блокируют только один сегмент.
 *
 * @param <K> тип ключа
 * @param <V> тип значения
 */
public class ConcurrentHashMap<K, V> {

    private final int segmentMask;
    private final int segmentShift;
    private final Segment<K, V>[] segments;

    private static final int DEFAULT_CONCURRENCY_LEVEL = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;
    private static final float DEFAULT_LOAD_FACTOR = 0.75f;

    public ConcurrentHashMap() {
        this(DEFAULT_CONCURRENCY_LEVEL);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentHashMap(int concurrencyLevel) {
        int ssize = 1;
        while (ssize < concurrencyLevel) {
            ssize <<= 1;
        }
        this.segmentShift = 32 - Integer.numberOfTrailingZeros(ssize);
        this.segmentMask = ssize - 1;
        this.segments = (Segment<K, V>[]) new Segment[ssize];
        for (int i = 0; i < ssize; i++) {
            segments[i] = new Segment<>(DEFAULT_LOAD_FACTOR);
        }
    }

    static final class HashEntry<K, V> {
        final K key;
        final int hash;
        volatile V value;
        volatile HashEntry<K, V> next;

        HashEntry(K key, int hash, HashEntry<K, V> next, V value) {
            this.key = key;
            this.hash = hash;
            this.next = next;
            this.value = value;
        }
    }

    static final class Segment<K, V> extends ReentrantLock {
        transient volatile AtomicReferenceArray<HashEntry<K, V>> table;
        transient volatile int count;
        transient int threshold;
        final float loadFactor;

        Segment(float loadFactor) {
            this.loadFactor = loadFactor;
            this.table = new AtomicReferenceArray<>(16);
            this.threshold = (int) (16 * loadFactor);
        }

        V get(Object key, int hash) {
            AtomicReferenceArray<HashEntry<K, V>> tab = table;
            int index = (tab.length() - 1) & hash;
            HashEntry<K, V> e = tab.get(index);

            VarHandle.loadLoadFence();

            for (; e != null; e = e.next) {
                if (e.hash == hash && Objects.equals(key, e.key)) {
                    return e.value;
                }
            }
            return null;
        }

        V put(K key, int hash, V value, boolean onlyIfAbsent) {
            lock();
            try {
                int c = count;
                if (c > threshold) {
                    rehash();
                }
                AtomicReferenceArray<HashEntry<K, V>> tab = table;
                int index = (tab.length() - 1) & hash;
                HashEntry<K, V> first = tab.get(index);
                for (HashEntry<K, V> e = first; e != null; e = e.next) {
                    if (e.hash == hash && Objects.equals(key, e.key)) {
                        V oldValue = e.value;
                        if (!onlyIfAbsent) {
                            e.value = value;
                        }
                        return oldValue;
                    }
                }
                tab.set(index, new HashEntry<>(key, hash, first, value));

                VarHandle.storeStoreFence();

                count = c + 1;
                return null;
            } finally {
                unlock();
            }
        }

        V merge(K key, int hash, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
            lock();
            try {
                int c = count;
                if (c > threshold) {
                    rehash();
                }
                AtomicReferenceArray<HashEntry<K, V>> tab = table;
                int index = (tab.length() - 1) & hash;
                HashEntry<K, V> first = tab.get(index);
                for (HashEntry<K, V> e = first; e != null; e = e.next) {
                    if (e.hash == hash && Objects.equals(key, e.key)) {
                        V newValue = remappingFunction.apply(e.value, value);
                        if (newValue == null) {
                            removeFromBucket(tab, index, hash, key);
                            count = c - 1;
                            return null;
                        }
                        e.value = newValue;
                        return newValue;
                    }
                }
                tab.set(index, new HashEntry<>(key, hash, first, value));
                count = c + 1;
                return value;
            } finally {
                unlock();
            }
        }

        void clear() {
            lock();
            try {
                AtomicReferenceArray<HashEntry<K, V>> tab = table;
                /* Сначала обнуляем count: иначе lock-free get видит пустые корзины,
                   а size() по-прежнему суммирует старый count — наблюдаемая пара (get, size) неконсистентна. */
                count = 0;
                VarHandle.storeStoreFence();
                for (int i = 0; i < tab.length(); i++) {
                    tab.set(i, null);
                }
            } finally {
                unlock();
            }
        }

        private void removeFromBucket(AtomicReferenceArray<HashEntry<K, V>> tab, int index, int hash, Object key) {
            HashEntry<K, V> rebuilt = null;
            for (HashEntry<K, V> e = tab.get(index); e != null; e = e.next) {
                if (!(e.hash == hash && Objects.equals(e.key, key))) {
                    rebuilt = new HashEntry<>(e.key, e.hash, rebuilt, e.value);
                }
            }
            tab.set(index, rebuilt);
        }

        private void rehash() {
            AtomicReferenceArray<HashEntry<K, V>> oldTable = table;
            int oldCapacity = oldTable.length();
            if (oldCapacity >= MAXIMUM_CAPACITY) {
                threshold = Integer.MAX_VALUE;
                return;
            }
            int newCapacity = oldCapacity << 1;
            threshold = (int) (newCapacity * loadFactor);
            AtomicReferenceArray<HashEntry<K, V>> newTable = new AtomicReferenceArray<>(newCapacity);
            for (int i = 0; i < oldCapacity; i++) {
                for (HashEntry<K, V> e = oldTable.get(i); e != null; e = e.next) {
                    int index = e.hash & (newCapacity - 1);
                    HashEntry<K, V> head = newTable.get(index);
                    newTable.set(index, new HashEntry<>(e.key, e.hash, head, e.value));
                }
            }
            table = newTable;
        }
    }

    private int hash(Object key) {
        if (key == null) {
            throw new NullPointerException("key must not be null");
        }
        int h = key.hashCode();
        h ^= (h >>> 20) ^ (h >>> 12);
        return h ^ (h >>> 7) ^ (h >>> 4);
    }

    private Segment<K, V> segmentFor(int hash) {
        return segments[(hash >>> segmentShift) & segmentMask];
    }

    public V get(Object key) {
        int h = hash(key);
        return segmentFor(h).get(key, h);
    }

    public V put(K key, V value) {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        int h = hash(key);
        return segmentFor(h).put(key, h, value, false);
    }

    public V putIfAbsent(K key, V value) {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        int h = hash(key);
        return segmentFor(h).put(key, h, value, true);
    }

    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        Objects.requireNonNull(remappingFunction, "remappingFunction must not be null");
        int h = hash(key);
        return segmentFor(h).merge(key, h, value, remappingFunction);
    }

    public int size() {
        final Segment<K, V>[] segs = this.segments;
        long last = -1;
        boolean stable = false;
        int retries = 2;
        for (int i = 0; i < retries; i++) {
            long sum = 0;
            for (Segment<K, V> seg : segs) {
                sum += seg.count;
            }
            if (sum == last) {
                stable = true;
                last = sum;
                break;
            }
            last = sum;
        }
        if (!stable) {
            long sum = 0;
            for (Segment<K, V> seg : segs) {
                seg.lock();
            }
            try {
                for (Segment<K, V> seg : segs) {
                    sum += seg.count;
                }
            } finally {
                for (Segment<K, V> seg : segs) {
                    seg.unlock();
                }
            }
            last = sum;
        }
        return (int) Math.min(last, Integer.MAX_VALUE);
    }

    public void clear() {
        final Segment<K, V>[] segs = this.segments;
        for (Segment<K, V> seg : segs) {
            seg.lock();
        }
        try {
            for (Segment<K, V> seg : segs) {
                seg.clear();
            }
        } finally {
            for (Segment<K, V> seg : segs) {
                seg.unlock();
            }
        }
    }

    public Iterator<Map.Entry<K, V>> iterator() {
        return new EntryIterator();
    }

    private class EntryIterator implements Iterator<Map.Entry<K, V>> {
        private int segmentIndex;
        private AtomicReferenceArray<HashEntry<K, V>> currentTable;
        private HashEntry<K, V> nextEntry;
        private int bucketIndex;

        EntryIterator() {
            this.segmentIndex = 0;
            this.currentTable = segments[0].table;
            this.nextEntry = null;
            this.bucketIndex = 0;
            advance();
        }

        private void advance() {
            while (segmentIndex < segments.length) {
                if (currentTable != null) {
                    while (bucketIndex < currentTable.length()) {
                        HashEntry<K, V> e = currentTable.get(bucketIndex++);
                        if (e != null) {
                            nextEntry = e;
                            return;
                        }
                    }
                }
                segmentIndex++;
                if (segmentIndex < segments.length) {
                    currentTable = segments[segmentIndex].table;
                    bucketIndex = 0;
                }
            }
            nextEntry = null;
        }

        @Override
        public boolean hasNext() {
            return nextEntry != null;
        }

        @Override
        public Map.Entry<K, V> next() {
            if (nextEntry == null) {
                throw new NoSuchElementException();
            }
            HashEntry<K, V> e = nextEntry;
            nextEntry = e.next;
            if (nextEntry == null) {
                advance();
            }
            return new AbstractMap.SimpleImmutableEntry<>(e.key, e.value);
        }
    }
}