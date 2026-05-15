package com.example.concurrentmap;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Гонка {@code clear()} и чтения в одном потоке: сначала {@code get(k)}, затем {@code size()}.
 * В таблице ровно одна пара {@code (1,1)}, поэтому исходы кодируют согласованность «ключ есть / карта непуста».
 * <p>
 * Инвариант: при единственном ключе нельзя наблюдать «ключа нет», но {@code size() &gt; 0} — это логически
 * противоречиво и не соответствует слабой, но разумной модели из {@link java.util.concurrent.ConcurrentHashMap}.
 */
@JCStressTest
@Outcome(id = "0, 0", expect = Expect.ACCEPTABLE, desc = "Согласованное «пусто»: get(1) == null и size() == 0.")
@Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "До clear: ключ и ненулевой size.")
@Outcome(id = "1, 0", expect = Expect.ACCEPTABLE, desc = "Допустимое перекрытие: size уже 0, а get ещё видит старое значение (отставание size при записи).")
@Outcome(id = "0, 1", expect = Expect.FORBIDDEN, desc = "Неконсистентно: ключа нет, но size сообщает непустую карту.")
@State
public class JCStressTestClearGetRace {

    private final ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();

    public JCStressTestClearGetRace() {
        map.put(1, 1);
    }

    @Actor
    public void clearer() {
        map.clear();
    }

    @Actor
    public void reader(II_Result r) {
        Integer v = map.get(1);
        r.r1 = v == null ? 0 : 1;
        r.r2 = map.size() > 0 ? 1 : 0;
    }
}
