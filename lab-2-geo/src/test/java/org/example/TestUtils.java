package org.example;

import java.util.*;
import java.util.stream.Collectors;

public class TestUtils {
    private static final Random RAND = new Random(42);

    public static List<LocationObject> generateRandomPoints(int count) {
        List<LocationObject> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double x = RAND.nextDouble();
            double y = RAND.nextDouble();
            list.add(new LocationObject(new Point(x, y), "point_" + i, i));
        }
        return list;
    }

    public static Point randomPoint() {
        return new Point(RAND.nextDouble(), RAND.nextDouble());
    }

    public static List<Point> randomCenters(int count) {
        List<Point> centers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            centers.add(randomPoint());
        }
        return centers;
    }

    public static List<LocationObject> bruteForceRadius(List<LocationObject> points, Point center, double radius) {
        return points.stream()
                .filter(p -> center.distanceTo(p.getPoint()) <= radius)
                .collect(Collectors.toList());
    }

    public static List<LocationObject> bruteForceNearest(List<LocationObject> points, Point center, int k) {
        return points.stream()
                .sorted(Comparator.comparingDouble(p -> center.distanceTo(p.getPoint())))
                .limit(k)
                .collect(Collectors.toList());
    }
}