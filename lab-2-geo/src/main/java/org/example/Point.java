package org.example;

/**
 * Точка на плоскости (широта, долгота).
 */
class Point {
    public final double x; // долгота (longitude)
    public final double y; // широта (latitude)

    public Point(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public double distanceTo(Point other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ")";
    }
}
