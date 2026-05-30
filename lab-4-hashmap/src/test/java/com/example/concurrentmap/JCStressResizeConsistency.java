package com.example.concurrentmap;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.II_Result;

@JCStressTest
@Outcome(id = "200, 100", expect = Expect.ACCEPTABLE, desc = "size=200, все ключи 100–199 найдены.")
@Outcome(expect = Expect.FORBIDDEN, desc = "Потеря элементов (size < 200 или found < 100).")
@State
public class JCStressResizeConsistency {

    ConcurrentHashMap<Integer, Integer>
            map =
            new ConcurrentHashMap<>(1);

    @Actor
    public void actor1() {

        for (int i = 0; i < 100; i++) {
            map.put(i, i);
        }
    }

    @Actor
    public void actor2() {

        for (int i = 100; i < 200; i++) {
            map.put(i, i);
        }
    }

    @Arbiter
    public void arbiter( II_Result r) {
        r.r1 = map.size();
        int found = 0;

        for (int i = 100; i < 200; i++) {

            if (map.get(i) != null) {
                found++;
            }
        }

        r.r2 = found;
    }
}
