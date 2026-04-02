package org.example;

import java.util.*;

/**
 * Интерфейс для пространственного индекса.
 * @param <T> тип объектов, хранящихся в индексе (должен иметь координаты)
 */
interface SpatialIndex<T extends SpatialObject> {
    void insert(T object);
    List<T> searchRadius(Point center, double radius);
    List<T> searchNearest(Point center, int k);
}
