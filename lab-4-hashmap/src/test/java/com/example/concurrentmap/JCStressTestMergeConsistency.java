package com.example.concurrentmap;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * Два потока параллельно вызывают {@code merge(1, 1, Integer::sum)} при начальном значении 0.
 * Каждый актор записывает {@code get(1)} сразу после своего merge; арбитр — итог после обоих.
 * <p>
 * Инвариант: оба merge добавляют по 1, финальное {@code r3} всегда 2; промежуточные {@code r1}/{@code r2}
 * могут быть 1 или 2 в зависимости от перекрытия, другие значения недопустимы.
 */
@JCStressTest
@Outcome(id = "1, 1, 2", expect = Expect.ACCEPTABLE, desc = "Оба увидели промежуточное значение 1")
@Outcome(id = "1, 2, 2", expect = Expect.ACCEPTABLE, desc = "actor1 увидел 1, actor2 увидел 2")
@Outcome(id = "2, 1, 2", expect = Expect.ACCEPTABLE, desc = "actor1 увидел 2, actor2 увидел 1")
@Outcome(id = "2, 2, 2", expect = Expect.ACCEPTABLE, desc = "Оба увидели финальное значение 2")
@Outcome(id = "[^12], .*, .*", expect = Expect.FORBIDDEN, desc = "r1 должно быть 1 или 2")
@Outcome(id = ".*, [^12], .*", expect = Expect.FORBIDDEN, desc = "r2 должно быть 1 или 2")
@Outcome(id = ".*, .*, [^2]", expect = Expect.FORBIDDEN, desc = "Финальное r3 должно быть 2")
@State
public class JCStressTestMergeConsistency {

    private final ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();

    public JCStressTestMergeConsistency() {
        map.put(1, 0);
    }

    @Actor
    public void actor1(III_Result r) {
        map.merge(1, 1, Integer::sum);
        r.r1 = map.get(1);
    }

    @Actor
    public void actor2(III_Result r) {
        map.merge(1, 1, Integer::sum);
        r.r2 = map.get(1);
    }

    @Arbiter
    public void arbiter(III_Result r) {
        r.r3 = map.get(1);
    }
}
