package com.example.concurrentmap;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

//@BenchmarkMode(Mode.Throughput)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 3, warmups = 1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 10, time = 2)
public class ConcurrentHashMapBenchmark {

    @Param({"1000", "5000", "10000", "50000", "100000"})
    int size;

    ConcurrentHashMap<Integer, Integer> concurrentMap;
    Map<Integer, Integer> plainMap;
    Map<Integer, Integer> syncMap;

    @Setup(Level.Trial)
    public void setup() {
        concurrentMap = new ConcurrentHashMap<>();
        plainMap = new HashMap<>();
        syncMap = Collections.synchronizedMap(new HashMap<>());
        for (int i = 0; i < size; i++) {
            concurrentMap.put(i, i);
            plainMap.put(i, i);
            syncMap.put(i, i);
        }
    }

    @Benchmark
    @Group("concurrentGet")
    public void concurrentMapGet(Blackhole bh) {
        bh.consume(concurrentMap.get(ThreadLocalRandom.current().nextInt(size)));
    }

    @Benchmark
    @Group("syncMapGet")
    public void syncMapGet(Blackhole bh) {
        bh.consume(syncMap.get(ThreadLocalRandom.current().nextInt(size)));
    }

    @Benchmark
    @Group("plainMapGet")
    @GroupThreads(1) // plainMap не потокобезопасен, тестируем только в одном потоке
    public void plainMapGet(Blackhole bh) {
        bh.consume(plainMap.get(ThreadLocalRandom.current().nextInt(size)));
    }

    // Пример для записи
    @Benchmark
    @Group("concurrentPut")
    public void concurrentMapPut(Blackhole bh) {
        int key = ThreadLocalRandom.current().nextInt(size);
        concurrentMap.put(key, key);
    }

    @Benchmark
    @Group("syncMapPut")
    public void syncMapPut(Blackhole bh) {
        int key = ThreadLocalRandom.current().nextInt(size);
        syncMap.put(key, key);
    }

    public static void main(String[] args) throws Exception {
        if (args != null && args.length > 0) {
            org.openjdk.jmh.Main.main(args);
            return;
        }

        runWithDefaultReporting();
    }

    private static void runWithDefaultReporting() throws IOException, RunnerException {
        Path resultsDir = Paths.get("target", "jmh-results");
        Files.createDirectories(resultsDir);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path resultFile = resultsDir.resolve("ConcurrentHashMapBenchmark-" + timestamp + ".csv");

        Options opt = new OptionsBuilder()
                .include(ConcurrentHashMapBenchmark.class.getSimpleName())
                .resultFormat(ResultFormatType.CSV)
                .result(resultFile.toString())
                .build();

        new Runner(opt).run();
    }
}
