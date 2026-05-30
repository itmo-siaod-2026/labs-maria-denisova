package com.example.concurrentmap;

import java.lang.invoke.VarHandle;
import java.util.AbstractMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicInteger;
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

    private volatile SegmentState<K, V> state;
    /** Флаг ресайза: 0 - нет ресайза, 1 - ресайз выполняется. */
    private final AtomicInteger resizeInProgress = new AtomicInteger(0);
    /** Счётчик завершённых resize — операции перепроверяют его до/после доступа к данным. */
    private final AtomicInteger resizeStamp = new AtomicInteger(0);

    private static final int DEFAULT_CONCURRENCY_LEVEL = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;
    private static final float DEFAULT_LOAD_FACTOR = 0.75f;
    private static final int MAX_SEGMENTS = 1 << 16;

    public ConcurrentHashMap() {
        this(DEFAULT_CONCURRENCY_LEVEL);
    }

    /**
     * @param concurrencyLevel желаемое число сегментов (округляется вверх до степени двойки)
     */
    @SuppressWarnings("unchecked")
    public ConcurrentHashMap(int concurrencyLevel) {
        int ssize = 1;
        while (ssize < concurrencyLevel) {
            ssize <<= 1;
        }
        int shift = 32 - Integer.numberOfTrailingZeros(ssize);
        int mask = ssize - 1;

        Segment<K,V>[] segments =(Segment<K,V>[]) new Segment[ssize];

        for (int i = 0; i < ssize; i++) {
            segments[i] = new Segment<>(DEFAULT_LOAD_FACTOR);
        }

        this.state = new SegmentState<>(segments, shift, mask);
    }

    /** Узел цепочки коллизий в одной корзине. */
    static final class HashEntry<K, V> {
        final K key;
        /** Кэш {@code key.hashCode()} после перемешивания — сравнение без повторного hashCode. */
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

    /** Один сегмент карты: корзины, счётчик элементов и блокировка на запись. */
    static final class Segment<K, V> extends ReentrantLock {
        /** Таблица корзин: в ячейке — начало списка коллизий; номер ячейки — остаток хэша по размеру таблицы. */
        transient volatile AtomicReferenceArray<HashEntry<K, V>> table;
        /** Число пар в сегменте; читается без блокировки в {@code get} и {@code size}. */
        transient volatile int count;
        /** Порог перехэширования: при {@code count > threshold} удваивается {@code table}. */
        transient int threshold;
        final float loadFactor;

        Segment(float loadFactor) {
            this.loadFactor = loadFactor;
            this.table = new AtomicReferenceArray<>(16);
            this.threshold = (int) (16 * loadFactor);
        }

        /** Чтение без блокировки: снимок первого узла корзины, затем обход цепочки. */
        V get(Object key, int hash) {
            AtomicReferenceArray<HashEntry<K, V>> tab = table;
            int index = (tab.length() - 1) & hash;
            HashEntry<K, V> e = tab.get(index);

            // Упорядочивает чтения узла и полей value/next относительно друг друга.
            VarHandle.loadLoadFence();

            for (; e != null; e = e.next) {
                if (e.hash == hash && Objects.equals(key, e.key)) {
                    return e.value;
                }
            }
            return null;
        }

        /**
         * Вставка или обновление под блокировкой.
         * @param onlyIfAbsent если {@code true} — не перезаписывать существующий ключ ({@code putIfAbsent})
         * @return предыдущее значение или {@code null}, если ключ был новым
         */
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
                // Новый узел в начало цепочки (открытая адресация внутри корзины).
                tab.set(index, new HashEntry<>(key, hash, first, value));

                // Публикация узла в корзине до увеличения count для lock-free читателей.
                VarHandle.storeStoreFence();

                count = c + 1;
                return null;
            } finally {
                unlock();
            }
        }

        /** Слияние под блокировкой; {@code null} из remapper удаляет ключ из корзины. */
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
                // Ключа не было — вставка с переданным value.
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

        /** Пересборка цепочки без удаляемого ключа (новые узлы — обратный порядок). */
        private void removeFromBucket(AtomicReferenceArray<HashEntry<K, V>> tab, int index, int hash, Object key) {
            HashEntry<K, V> rebuilt = null;
            for (HashEntry<K, V> e = tab.get(index); e != null; e = e.next) {
                if (!(e.hash == hash && Objects.equals(e.key, key))) {
                    rebuilt = new HashEntry<>(e.key, e.hash, rebuilt, e.value);
                }
            }
            tab.set(index, rebuilt);
        }

        /** Удвоение {@code table} и переразмещение узлов по новым индексам корзин. */
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

    private static final class SegmentState<K, V> {
        final Segment<K, V>[] segments;
        final int segmentShift;
        final int segmentMask;

        SegmentState(
                Segment<K, V>[] segments,
                int segmentShift,
                int segmentMask) {

            this.segments = segments;
            this.segmentShift = segmentShift;
            this.segmentMask = segmentMask;
        }
    }

    /** Дополнительное перемешивание hashCode — меньше скоплений в корзинах. */
    private int hash(Object key) {
        if (key == null) {
            throw new NullPointerException("key must not be null");
        }
        int h = key.hashCode();
        h ^= (h >>> 20) ^ (h >>> 12);
        return h ^ (h >>> 7) ^ (h >>> 4);
    }

    private void awaitNotResizing() {
        while (resizeInProgress.get() != 0) {
            Thread.onSpinWait();
        }
    }

    public V get(Object key) {
        int h = hash(key);
        while (true) {
            awaitNotResizing();
            int stamp = resizeStamp.get();
            SegmentState<K, V> s = state;
            int segIndex = (h >>> s.segmentShift) & s.segmentMask;
            V result = s.segments[segIndex].get(key, h);
            if (stamp == resizeStamp.get() && state == s) {
                return result;
            }
        }
    }

    public V put(K key, V value) {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        int h = hash(key);
        while (true) {
            awaitNotResizing();
            int stamp = resizeStamp.get();
            SegmentState<K,V> s = state;
            tryResize();

            if (state != s || stamp != resizeStamp.get()) {
                continue;
            }
            int segIndex = (h >>> s.segmentShift) & s.segmentMask;
            V result = s.segments[segIndex].put(key, h, value, false);
            if (state == s && stamp == resizeStamp.get()) {
                return result;
            }
        }
    }

    public V putIfAbsent(K key,V value){
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        int h=hash(key);

        while (true) {
            awaitNotResizing();
            int stamp = resizeStamp.get();
            SegmentState<K,V> s = state;
            tryResize();

            if (state != s || stamp != resizeStamp.get()) {
                continue;
            }
            int segIndex = (h >>> s.segmentShift) & s.segmentMask;
            V result = s.segments[segIndex].put(key, h, value, true);
            if (state == s && stamp == resizeStamp.get()) {
                return result;
            }
        }
    }

    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        Objects.requireNonNull(remappingFunction, "remappingFunction must not be null");
        int h = hash(key);
        while (true) {
            awaitNotResizing();
            int stamp = resizeStamp.get();
            SegmentState<K, V> s = state;
            tryResize();

            if (state != s || stamp != resizeStamp.get()) {
                continue;
            }
            int segIndex = (h >>> s.segmentShift) & s.segmentMask;
            V result = s.segments[segIndex].merge(key, h, value, remappingFunction);
            if (state == s && stamp == resizeStamp.get()) {
                return result;
            }
        }
    }

    /**
     * Размер карты: два прохода по {@code count} без блокировок; если сумма совпала —
     * возвращаем её. Иначе блокируем все сегменты и считаем снова (точный, но дорогой путь).
     */
    public int size() {
        awaitNotResizing();
        SegmentState<K,V> s = state;
        long sum = 0;
        for (Segment<K,V> seg : s.segments) {
            sum += seg.count;
        }
        return (int) Math.min(sum, Integer.MAX_VALUE);
    }
    /*public int size() {
        final Segment<K, V>[] segs = state.segments;
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
    }*/

    /** Очистка всей карты: сначала захват всех сегментов, затем {@code clear} в каждом. */
    public void clear() {
        final Segment<K, V>[] segs = state.segments;
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

    /**
     * Проверка необходимости ресайза.
     * Только один поток выигрывает CAS и выполняет ресайз.
     */
    private void tryResize() {
        awaitNotResizing();
        if (!shouldResize()) {
            return;
        }

        if (resizeInProgress.compareAndSet(0, 1)) {
            try {
                resizeSegments();
            } finally {
                resizeInProgress.set(0);
                resizeStamp.incrementAndGet();
            }
        } else {
            awaitNotResizing();
        }
    }

    /**
     * Проверка, нужно ли увеличивать количество сегментов.
     */
    private boolean shouldResize() {
        SegmentState<K,V> s=state;
        Segment<K,V>[] segs=s.segments;

        int segCount = segs.length;

        if (segCount >= MAX_SEGMENTS) {
            return false;
        }

        long totalCount = 0;
        for (Segment<K, V> seg : segs) {
            totalCount += seg.count;
        }
        long avgLoad = totalCount / segCount;
        // Если средняя загрузка сегмента превышает половину порога рехэширования
        return state == s && avgLoad > segs[0].threshold / 2;
    }

    /**
     * Динамическое увеличение количества сегментов.
     */
    @SuppressWarnings("unchecked")
    private void resizeSegments() {
        SegmentState<K,V> oldState = state;
        Segment<K,V>[] oldSegments = oldState.segments;
        int oldSize = oldSegments.length;

        if (oldSize >= MAX_SEGMENTS) {
            return;
        }

        int newSize = Math.min(oldSize << 1, MAX_SEGMENTS);
        int newShift = 32 - Integer.numberOfTrailingZeros(newSize);
        int newMask = newSize - 1;

        Segment<K,V>[] newSegments = (Segment<K,V>[]) new Segment[newSize];
        for (int i = 0; i < newSize; i++) {
            newSegments[i] = new Segment<>(DEFAULT_LOAD_FACTOR);
        }

        for (Segment<K,V> seg : oldSegments) {
            seg.lock();
        }

        try {
            if (state != oldState) {
                return;
            }

            for (Segment<K,V> oldSeg : oldSegments) {
                AtomicReferenceArray<HashEntry<K,V>> table = oldSeg.table;
                for (int bucket = 0; bucket < table.length(); bucket++) {
                    HashEntry<K,V> e = table.get(bucket);
                    while (e != null) {
                        int hash = e.hash;
                        int newSegmentIndex = (hash >>> newShift) & newMask;
                        Segment<K,V> newSeg = newSegments[newSegmentIndex];
                        AtomicReferenceArray<HashEntry<K,V>> newTable = newSeg.table;
                        int newBucket = hash & (newTable.length() - 1);
                        HashEntry<K,V> first = newTable.get(newBucket);
                        newTable.set(newBucket, new HashEntry<>(e.key, e.hash, first, e.value));
                        newSeg.count++;
                        e = e.next;
                    }
                }
            }
        } finally {
            if (state == oldState) {
                SegmentState<K,V> newState = new SegmentState<>(newSegments, newShift, newMask);
                VarHandle.fullFence();
                state = newState;
            }
            for (Segment<K,V> seg : oldSegments) {
                seg.unlock();
            }
        }
    }

    public Iterator<Map.Entry<K, V>> iterator() {
        return new EntryIterator();
    }

    /** Обход сегментов и корзин; снимок на момент итерации, без блокировок. */
    private class EntryIterator implements Iterator<Map.Entry<K, V>> {
        private int segmentIndex;
        private AtomicReferenceArray<HashEntry<K, V>> currentTable;
        /** Следующий узел для {@code next()} или {@code null}, если обход завершён. */
        private HashEntry<K, V> nextEntry;
        private int bucketIndex;
        private final Segment<K,V>[] snapshotSegments;

        EntryIterator(){
            SegmentState<K,V> snapshot = state;
            this.snapshotSegments = snapshot.segments;
            this.segmentIndex = 0;
            this.currentTable = snapshotSegments[0].table;
            this.bucketIndex = 0;
            advance();
        }

        /** Ищет следующий непустой узел, переходя к следующей корзине или сегменту. */
        private void advance() {
            while (segmentIndex < snapshotSegments.length) {
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
                if (segmentIndex < snapshotSegments.length) {
                    currentTable = snapshotSegments[segmentIndex].table;
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