package org.example;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 20, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class KDTreeBenchmark {

    @Param({"10000", "100000", "500000", "1000000"})
    private int size;

    private List<LocationObject> points;
    private KDTree index;
    private List<Point> queryCenters;
    private double radius = 0.01;
    private int k = 5;

    @Setup(Level.Trial)
    public void setup() {
        Random rand = new Random(42); // фиксированный seed для воспроизводимости
        points = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            double x = rand.nextDouble();
            double y = rand.nextDouble();
            points.add(new LocationObject(new Point(x, y), "point_" + i, i));
        }
        // 1000 случайных центров для поиска
        queryCenters = new ArrayList<>(1000);
        for (int i = 0; i < 1000; i++) {
            queryCenters.add(new Point(rand.nextDouble(), rand.nextDouble()));
        }
    }

    @Benchmark
    public void insertAll(Blackhole bh) {
        KDTree idx = new KDTree();
        for (LocationObject p : points) {
            idx.insert(p);
        }
        bh.consume(idx);
    }

    @Benchmark
    public void searchRadius(Blackhole bh) {
        KDTree idx = new KDTree();
        for (LocationObject p : points) {
            idx.insert(p);
        }
        for (Point center : queryCenters) {
            List<LocationObject> result = idx.searchRadius(center, radius);
            bh.consume(result);
        }
    }

    @Benchmark
    public void searchNearest(Blackhole bh) {
        KDTree idx = new KDTree();
        for (LocationObject p : points) {
            idx.insert(p);
        }
        for (Point center : queryCenters) {
            List<LocationObject> result = idx.searchNearest(center, k);
            bh.consume(result);
        }
    }

    public static void main(String[] args) throws RunnerException, IOException {
        Path resultsDir = Paths.get("target", "jmh-results");
        Files.createDirectories(resultsDir);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path resultFile = resultsDir.resolve("KDTreeBenchmark-" + timestamp + ".csv");

        Options opt = new OptionsBuilder()
                .include(KDTreeBenchmark.class.getSimpleName())
                .resultFormat(ResultFormatType.CSV)
                .output("kd_tree_benchmark.csv")
                .result(resultFile.toString())
                .build();
        new Runner(opt).run();
    }
}