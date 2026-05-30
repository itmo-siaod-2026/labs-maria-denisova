package com.example.concurrentmap;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.II_Result;

@JCStressTest
@Outcome(id = "101, 1", expect = Expect.ACCEPTABLE, desc = "Оба актора завершились: ключ найден, size=101.")
@Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "Ключ 10000 найден (actor2), actor1 мог не успеть.")
@Outcome(id = "0, -1", expect = Expect.FORBIDDEN, desc = "Запись потерялась.")
@Outcome(expect = Expect.FORBIDDEN, desc = "get(10000) вернул null при ненулевом size.")
@State
public class JCStressResizeLostUpdate {
    ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>(1);

    @Actor
    public void actor1() {
        /* Форсируем рост сегментов */
        for (int i = 0; i < 100; i++) {
            map.put(i, i);
        }
    }

    @Actor
    public void actor2() {
        map.put(10000, 1);
    }

    @Arbiter
    public void arbiter(II_Result r) {
        Integer value = map.get(10000);
        r.r1 = map.size();
        r.r2 = (value == null ? -1 : value);
    }
}
