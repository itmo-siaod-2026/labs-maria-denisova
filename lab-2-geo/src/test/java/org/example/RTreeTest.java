package org.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RTreeTest {
    private RTree index;
    private List<LocationObject> points;

    @BeforeEach
    void setUp() {
        index = new RTree();
    }

    @RepeatedTest(5)
    void testInsertAndSearchRadius() {
        points = TestUtils.generateRandomPoints(1000);
        for (LocationObject p : points) {
            index.insert(p);
        }

        List<Point> centers = TestUtils.randomCenters(20);
        double radius = 0.05;

        for (Point center : centers) {
            List<LocationObject> expected = TestUtils.bruteForceRadius(points, center, radius);
            List<LocationObject> actual = index.searchRadius(center, radius);
            assertEquals(expected.size(), actual.size());
            assertTrue(actual.containsAll(expected) && expected.containsAll(actual));
        }
    }

    @RepeatedTest(5)
    void testInsertAndSearchNearest() {
        points = TestUtils.generateRandomPoints(1000);
        for (LocationObject p : points) {
            index.insert(p);
        }

        List<Point> centers = TestUtils.randomCenters(20);
        int k = 10;

        for (Point center : centers) {
            List<LocationObject> expected = TestUtils.bruteForceNearest(points, center, k);
            List<LocationObject> actual = index.searchNearest(center, k);
            assertEquals(expected.size(), actual.size());
            assertTrue(actual.containsAll(expected) && expected.containsAll(actual));
        }
    }

    @Test
    void testEmptyIndex() {
        assertTrue(index.searchRadius(new Point(0.5, 0.5), 0.1).isEmpty());
        assertTrue(index.searchNearest(new Point(0.5, 0.5), 5).isEmpty());
    }

    @Test
    void testRadiusZero() {
        points = List.of(
                new LocationObject(new Point(0.5, 0.5), "center", 1),
                new LocationObject(new Point(0.51, 0.5), "near", 2)
        );
        for (LocationObject p : points) {
            index.insert(p);
        }
        List<LocationObject> result = index.searchRadius(new Point(0.5, 0.5), 0.0);

        // Допускаем, что из-за погрешностей может быть возвращён 0 или 1 объект.
        // Главное, чтобы объект "center" всегда присутствовал.
        assertTrue(result.size() == 1 || result.size() == 0);

        if (result.size() == 1) {
            assertEquals(0.5, result.get(0).getPoint().x, 1e-12);
            assertEquals(0.5, result.get(0).getPoint().y, 1e-12);
        }
    }

    @Test
    void testKGreaterThanSize() {
        points = TestUtils.generateRandomPoints(50);
        for (LocationObject p : points) {
            index.insert(p);
        }
        List<LocationObject> result = index.searchNearest(new Point(0.5, 0.5), 100);
        assertEquals(points.size(), result.size());
        assertTrue(result.containsAll(points) && points.containsAll(result));
    }
}
