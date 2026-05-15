package com.example.concurrentmap;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.II_Result;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Видимость после {@code put}: писатель кладёт пару и выставляет флаг {@code putCompleted},
 * читатели ждут флага и вызывают {@code get(1)}.
 * <p>
 * Инвариант: после завершения put (по флагу) значение должно быть видно читателям — исходы
 * {@code (0, 0)} и {@code (0, 1)} логически невозможны; допустимы {@code (1, 0)} (отставание второго)
 * и {@code (1, 1)} (оба увидели запись).
 */
@JCStressTest
@Outcome(id = "0, 0", expect = Expect.FORBIDDEN, desc = "Невозможно: put завершился, но никто не увидел")
@Outcome(id = "0, 1", expect = Expect.FORBIDDEN, desc = "Нарушение порядка: первый не увидел завершённый put")
@Outcome(id = "1, 0", expect = Expect.ACCEPTABLE, desc = "Первый увидел, второй ещё не начал/не завершил чтение")
@Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "Оба увидели завершённый put")
@State
public class JCStressTestPutGetVisibility {

    private final ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();
    private final AtomicInteger putCompleted = new AtomicInteger(0);

    @Actor
    public void writer() {
        map.put(1, 1);
        putCompleted.set(1);
    }

    @Actor
    public void reader1(II_Result r) {
        while (putCompleted.get() == 0) {
            Thread.onSpinWait();
        }
        r.r1 = map.get(1) == null ? 0 : 1;
    }

    @Actor
    public void reader2(II_Result r) {
        while (putCompleted.get() == 0) {
            Thread.onSpinWait();
        }
        r.r2 = map.get(1) == null ? 0 : 1;
    }
}
