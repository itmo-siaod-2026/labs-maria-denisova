package org.example;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

/**
 * Профайлер для сравнения производительности пространственных индексов.
 * Запуск: java IndexProfiler <index> [points] [queries] [radius] [k]
 * где index: kd, r, octree, all
 */
public class IndexProfiler {

    // Параметры по умолчанию
    private static int POINTS_COUNT = 500_000;
    private static int QUERIES_COUNT = 10_000;
    private static double RADIUS = 0.02;
    private static int K_NEAREST = 10;

    // Прогревочные параметры (10% от основных)
    private static final int WARMUP_POINTS = 50_000;
    private static final int WARMUP_QUERIES = 1_000;

    // Результаты
    private static final String CSV_FILE = "profiler_results.csv";

    public static void main(String[] args) {
        // Парсинг аргументов
        if (args.length < 1) {
            System.err.println("Usage: java IndexProfiler <index> [points] [queries] [radius] [k]");
            System.err.println("  index: kd, r, octree, all");
            return;
        }
        String indexType = args[0].toLowerCase();
        if (args.length > 1) POINTS_COUNT = Integer.parseInt(args[1]);
        if (args.length > 2) QUERIES_COUNT = Integer.parseInt(args[2]);
        if (args.length > 3) RADIUS = Double.parseDouble(args[3]);
        if (args.length > 4) K_NEAREST = Integer.parseInt(args[4]);

        // Проверка на разумные пределы
        if (WARMUP_POINTS > POINTS_COUNT) {
            System.err.println("Warmup points exceed main points, reducing warmup to main points");
        }

        try (PrintWriter csvWriter = new PrintWriter(new FileWriter(CSV_FILE, true))) {
            if (new java.io.File(CSV_FILE).length() == 0) {
                csvWriter.println("index,operation,points,queries,radius,k,time_ms");
            }

            if (indexType.equals("all")) {
                runForIndex("kd", csvWriter);
                runForIndex("r", csvWriter);
                runForIndex("octree", csvWriter);
            } else {
                runForIndex(indexType, csvWriter);
            }
        } catch (IOException e) {
            System.err.println("Ошибка записи CSV: " + e.getMessage());
        }
    }

    private static void runForIndex(String indexType, PrintWriter csvWriter) {
        System.out.println("\n========== " + indexType.toUpperCase() + " ==========");
        // Создаём индекс в зависимости от типа
        SpatialIndex<LocationObject> index;
        switch (indexType) {
            case "kd":
                index = new KDTree();
                break;
            case "r":
                index = new RTree();
                break;
            case "octree":
                index = new OctoTree(0, 0, 1, 1);
                break;
            default:
                throw new IllegalArgumentException("Неизвестный индекс: " + indexType);
        }

        // Генерируем данные
        System.out.println("Генерация данных...");
        List<LocationObject> allPoints = generatePoints(POINTS_COUNT, 42);
        List<Point> allQueries = generateQueryCenters(QUERIES_COUNT, 43);

        // Прогрев
        System.out.println("Прогрев JVM...");
        warmup(index, indexType);

        // Измерения
        System.out.println("Запуск измерений...");
        long insertTime = measureInsert(index, allPoints);
        long radiusTime = measureSearchRadius(index, allQueries);
        long nearestTime = measureSearchNearest(index, allQueries);

        // Вывод результатов
        System.out.printf("Вставка %d точек: %d мс%n", POINTS_COUNT, insertTime);
        System.out.printf("Поиск в радиусе %d запросов: %d мс%n", QUERIES_COUNT, radiusTime);
        System.out.printf("Поиск %d ближайших %d запросов: %d мс%n", K_NEAREST, QUERIES_COUNT, nearestTime);

        // Запись в CSV
        csvWriter.printf("%s,insert,%d,%d,%.4f,%d,%d%n",
                indexType, POINTS_COUNT, QUERIES_COUNT, RADIUS, K_NEAREST, insertTime);
        csvWriter.printf("%s,radius,%d,%d,%.4f,%d,%d%n",
                indexType, POINTS_COUNT, QUERIES_COUNT, RADIUS, K_NEAREST, radiusTime);
        csvWriter.printf("%s,nearest,%d,%d,%.4f,%d,%d%n",
                indexType, POINTS_COUNT, QUERIES_COUNT, RADIUS, K_NEAREST, nearestTime);
        csvWriter.flush();
    }

    private static void warmup(SpatialIndex<LocationObject> index, String indexType) {
        // Генерируем уменьшенный набор данных для прогрева
        List<LocationObject> warmupPoints = generatePoints(WARMUP_POINTS, 100);
        List<Point> warmupQueries = generateQueryCenters(WARMUP_QUERIES, 101);

        // Прогрев вставки
        for (int i = 0; i < 3; i++) {
            // Создаём отдельный индекс для прогрева
            SpatialIndex<LocationObject> warmIndex;
            if (indexType.equals("kd")) warmIndex = new KDTree();
            else if (indexType.equals("r")) warmIndex = new RTree();
            else warmIndex = new OctoTree(0, 0, 1, 1);

            for (LocationObject p : warmupPoints) {
                warmIndex.insert(p);
            }
            for (Point center : warmupQueries) {
                warmIndex.searchRadius(center, RADIUS);
                warmIndex.searchNearest(center, K_NEAREST);
            }
        }
        System.gc(); // попытка очистить память после прогрева
    }

    private static long measureInsert(SpatialIndex<LocationObject> index, List<LocationObject> points) {
        // Создаём индекс
        SpatialIndex<LocationObject> freshIndex;
        if (index instanceof KDTree) freshIndex = new KDTree();
        else if (index instanceof RTree) freshIndex = new RTree();
        else freshIndex = new OctoTree(0, 0, 1, 1);

        long start = System.nanoTime();
        for (LocationObject p : points) {
            freshIndex.insert(p);
        }
        long end = System.nanoTime();
        return (end - start) / 1_000_000;
    }

    private static long measureSearchRadius(SpatialIndex<LocationObject> index, List<Point> queries) {
        // Создаём индекс и заполняем
        SpatialIndex<LocationObject> freshIndex;
        if (index instanceof KDTree) freshIndex = new KDTree();
        else if (index instanceof RTree) freshIndex = new RTree();
        else freshIndex = new OctoTree(0, 0, 1, 1);

        // Заполняем основными точками
        List<LocationObject> points = generatePoints(POINTS_COUNT, 42);
        for (LocationObject p : points) {
            freshIndex.insert(p);
        }

        long start = System.nanoTime();
        for (Point center : queries) {
            freshIndex.searchRadius(center, RADIUS);
        }
        long end = System.nanoTime();
        return (end - start) / 1_000_000;
    }

    private static long measureSearchNearest(SpatialIndex<LocationObject> index, List<Point> queries) {
        // Аналогично, создаём новый индекс
        SpatialIndex<LocationObject> freshIndex;
        if (index instanceof KDTree) freshIndex = new KDTree();
        else if (index instanceof RTree) freshIndex = new RTree();
        else freshIndex = new OctoTree(0, 0, 1, 1);

        List<LocationObject> points = generatePoints(POINTS_COUNT, 42);
        for (LocationObject p : points) {
            freshIndex.insert(p);
        }

        long start = System.nanoTime();
        for (Point center : queries) {
            freshIndex.searchNearest(center, K_NEAREST);
        }
        long end = System.nanoTime();
        return (end - start) / 1_000_000;
    }

    private static List<LocationObject> generatePoints(int count, int seed) {
        Random rand = new Random(seed);
        List<LocationObject> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double x = rand.nextDouble();
            double y = rand.nextDouble();
            list.add(new LocationObject(new Point(x, y), "p" + i, i));
        }
        return list;
    }

    private static List<Point> generateQueryCenters(int count, int seed) {
        Random rand = new Random(seed);
        List<Point> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new Point(rand.nextDouble(), rand.nextDouble()));
        }
        return list;
    }
}
