package org.example;

/**
 * Реализация географического объекта.
 */
class LocationObject implements SpatialObject {
    private final Point point;
    private final String name;
    private final int id;

    public LocationObject(Point point, String name, int id) {
        this.point = point;
        this.name = name;
        this.id = id;
    }

    @Override
    public Point getPoint() {
        return point;
    }

    @Override
    public String toString() {
        return "org.example.LocationObject{id=" + id + ", name='" + name + "', point=" + point + "}";
    }
}
