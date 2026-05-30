package com.example.concurrentmap;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.I_Result;

@JCStressTest
@Outcome(id = "42", expect = Expect.ACCEPTABLE, desc = "Запись видна после resize.")
@Outcome(id = "-1", expect = Expect.FORBIDDEN, desc = "Элемент потерян.")
@State
public class JCStressResizeVisibility {
    ConcurrentHashMap<Integer, Integer>
            map =
            new ConcurrentHashMap<>(1);

    @Actor
    public void writer() {
        for (int i = 0; i < 500; i++) {
            map.put(i, i);
        }
    }

    @Actor
    public void reader() {
        map.put(999999, 42);
    }

    @Arbiter
    public void arbiter(I_Result r) {
        Integer v = map.get(999999);
        r.r1 = (v == null ? -1 : v);
    }
}
