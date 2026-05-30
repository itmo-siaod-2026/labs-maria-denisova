package org.example;

import java.util.*;

/**
 * R-дерево с квадратичным разделением узлов.
 * Оптимизировано для точечных данных.
 * Структура группирует объекты в минимальные ограничивающие прямоугольники (MBR),
 * что позволяет эффективно отсекать узлы при поиске.
 */
public class RTree implements SpatialIndex<LocationObject> {
    private static final int MAX_ENTRIES = 50;      // максимальное количество записей в узле
    private static final int MIN_ENTRIES = 20;      // минимальное (для корректного разделения)

    private Node root;

    /**
     * Базовый класс для узла R-дерева.
     */
    private abstract static class Node {
        MBR mbr;  // минимальный ограничивающий прямоугольник узла

        abstract boolean isLeaf();
        abstract List<LocationObject> getPoints();
        abstract List<Node> getChildren();
    }

    /**
     * Листовой узел, хранящий точки (географические объекты).
     */
    private static class LeafNode extends Node {
        List<LocationObject> points = new ArrayList<>();

        @Override
        boolean isLeaf() {
            return true;
        }

        @Override
        List<LocationObject> getPoints() {
            return points;
        }

        @Override
        List<Node> getChildren() {
            return Collections.emptyList();
        }

        /**
         * Добавляет точку в лист и обновляет MBR.
         */
        void addPoint(LocationObject p) {
            points.add(p);
            updateMBR();
        }

        /**
         * Пересчитывает MBR листа по всем его точкам.
         */
        void updateMBR() {
            if (points.isEmpty()) {
                mbr = null;
                return;
            }
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (LocationObject p : points) {
                Point pt = p.getPoint();
                minX = Math.min(minX, pt.x);
                minY = Math.min(minY, pt.y);
                maxX = Math.max(maxX, pt.x);
                maxY = Math.max(maxY, pt.y);
            }
            mbr = new MBR(minX, minY, maxX, maxY);
        }
    }

    /**
     * Внутренний узел, хранящий ссылки на дочерние узлы.
     */
    private static class InternalNode extends Node {
        List<Node> children = new ArrayList<>();

        @Override
        boolean isLeaf() {
            return false;
        }

        @Override
        List<LocationObject> getPoints() {
            return Collections.emptyList();
        }

        @Override
        List<Node> getChildren() {
            return children;
        }

        /**
         * Добавляет дочерний узел и обновляет MBR.
         */
        void addChild(Node child) {
            children.add(child);
            updateMBR();
        }

        /**
         * Пересчитывает MBR узла как объединение MBR всех детей.
         */
        void updateMBR() {
            if (children.isEmpty()) {
                mbr = null;
                return;
            }
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (Node child : children) {
                if (child.mbr != null) {
                    minX = Math.min(minX, child.mbr.minX);
                    minY = Math.min(minY, child.mbr.minY);
                    maxX = Math.max(maxX, child.mbr.maxX);
                    maxY = Math.max(maxY, child.mbr.maxY);
                }
            }
            mbr = new MBR(minX, minY, maxX, maxY);
        }
    }

    /**
     * Минимальный ограничивающий прямоугольник.
     */
    private static class MBR {
        double minX, minY, maxX, maxY;

        MBR(double minX, double minY, double maxX, double maxY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }

        /** Площадь прямоугольника. */
        double area() {
            return (maxX - minX) * (maxY - minY);
        }

        /** Объединение текущего прямоугольника с другим. */
        MBR union(MBR other) {
            return new MBR(
                    Math.min(minX, other.minX),
                    Math.min(minY, other.minY),
                    Math.max(maxX, other.maxX),
                    Math.max(maxY, other.maxY)
            );
        }

        /**
         * Проверяет, пересекается ли прямоугольник с кругом.
         * @param center центр круга
         * @param radius радиус круга
         * @return true, если пересекаются
         */
        boolean intersectsCircle(Point center, double radius) {
            double closestX = Math.max(minX, Math.min(center.x, maxX));
            double closestY = Math.max(minY, Math.min(center.y, maxY));
            double dx = center.x - closestX;
            double dy = center.y - closestY;
            return dx * dx + dy * dy <= radius * radius;
        }

        /**
         * Минимальное расстояние от точки до прямоугольника.
         * Если точка внутри, возвращается 0.
         */
        double distanceToPoint(Point p) {
            double dx = 0, dy = 0;
            if (p.x < minX) dx = minX - p.x;
            else if (p.x > maxX) dx = p.x - maxX;
            if (p.y < minY) dy = minY - p.y;
            else if (p.y > maxY) dy = p.y - maxY;
            return Math.sqrt(dx * dx + dy * dy);
        }
    }

    /** Конструктор: создаёт пустое R-дерево. */
    public RTree() {
        root = new LeafNode();
    }

    /**
     * Вставляет географический объект в дерево.
     * @param object вставляемый объект
     */
    @Override
    public void insert(LocationObject object) {
        LeafNode leaf = chooseLeaf(root, object);
        leaf.addPoint(object);
        if (leaf.points.size() > MAX_ENTRIES) {
            splitLeaf(leaf);
        }
        adjustTree(leaf);
    }

    /**
     * Поиск всех объектов в заданном радиусе от центра.
     * @param center центр круга
     * @param radius радиус
     * @return список объектов, попавших в круг
     */
    @Override
    public List<LocationObject> searchRadius(Point center, double radius) {
        List<LocationObject> result = new ArrayList<>();
        searchRadiusRec(root, center, radius, result);
        return result;
    }

    /**
     * Поиск k ближайших объектов к заданной точке.
     * @param center точка отсчёта
     * @param k количество ближайших объектов
     * @return список объектов от ближайшего к дальнему
     */
    @Override
    public List<LocationObject> searchNearest(Point center, int k) {
        if (k <= 0) return Collections.emptyList();
        // Max-куча: хранит кандидатов, дальний в начале
        PriorityQueue<LocationObject> pq = new PriorityQueue<>((a, b) ->
                Double.compare(center.distanceTo(b.getPoint()), center.distanceTo(a.getPoint())));
        searchNearestRec(root, center, k, pq);
        List<LocationObject> result = new ArrayList<>();
        while (!pq.isEmpty()) result.add(pq.poll());
        Collections.reverse(result);
        return result;
    }

    // ====================== Вспомогательные методы ======================

    /**
     * Выбирает листовой узел для вставки объекта, спускаясь от корня.
     * Используется эвристика: выбирается дочерний узел с наименьшим увеличением площади MBR.
     */
    private LeafNode chooseLeaf(Node node, LocationObject obj) {
        if (node.isLeaf()) return (LeafNode) node;
        InternalNode internal = (InternalNode) node;
        Node best = null;
        double minAreaIncrease = Double.MAX_VALUE;
        for (Node child : internal.children) {
            MBR union = child.mbr.union(new MBR(obj.getPoint().x, obj.getPoint().y, obj.getPoint().x, obj.getPoint().y));
            double areaIncrease = union.area() - child.mbr.area();
            if (areaIncrease < minAreaIncrease - 1e-9) {
                minAreaIncrease = areaIncrease;
                best = child;
            } else if (Math.abs(areaIncrease - minAreaIncrease) < 1e-9) {
                if (child.mbr.area() < best.mbr.area()) best = child;
            }
        }
        return chooseLeaf(best, obj);
    }

    /**
     * Корректирует MBR узлов на пути от изменённого узла до корня.
     * @param node узел, с которого начинается корректировка
     */
    private void adjustTree(Node node) {
        while (node != null) {
            if (node.isLeaf()) ((LeafNode) node).updateMBR();
            else ((InternalNode) node).updateMBR();
            node = findParent(root, node);
        }
    }

    /**
     * Находит родителя узла в дереве (линейный поиск, дорого).
     * @param root корень дерева
     * @param target искомый узел
     * @return родительский узел или null, если target — корень
     */
    private InternalNode findParent(Node root, Node target) {
        if (root == target || root.isLeaf()) return null;
        InternalNode internal = (InternalNode) root;
        for (Node child : internal.children) {
            if (child == target) return internal;
            InternalNode res = findParent(child, target);
            if (res != null) return res;
        }
        return null;
    }

    // ====================== Разделение узлов ======================

    /** Разделяет переполненный лист. */
    private void splitLeaf(LeafNode leaf) {
        InternalNode parent = findParent(root, leaf);
        if (parent == null) {
            InternalNode newRoot = new InternalNode();
            newRoot.addChild(leaf);
            splitLeafQuadratic(leaf, newRoot);
            root = newRoot;
        } else {
            splitLeafQuadratic(leaf, parent);
        }
    }

    /** Разделяет переполненный внутренний узел. */
    private void splitInternal(InternalNode node) {
        InternalNode parent = findParent(root, node);
        if (parent == null) {
            InternalNode newRoot = new InternalNode();
            newRoot.addChild(node);
            splitInternalQuadratic(node, newRoot);
            root = newRoot;
        } else {
            splitInternalQuadratic(node, parent);
        }
    }

    /**
     * Квадратичное разделение листа.
     * Выбирает два наиболее удалённых объекта (семена), затем распределяет остальные,
     * минимизируя увеличение площади.
     */
    private void splitLeafQuadratic(LeafNode leaf, InternalNode parent) {
        List<LocationObject> points = leaf.points;
        int[] seeds = pickSeedsPoints(points);
        LocationObject seed1 = points.get(seeds[0]);
        LocationObject seed2 = points.get(seeds[1]);

        LeafNode group1 = new LeafNode();
        LeafNode group2 = new LeafNode();
        group1.addPoint(seed1);
        group2.addPoint(seed2);

        List<LocationObject> remaining = new ArrayList<>(points);
        remaining.remove(seed1);
        remaining.remove(seed2);

        while (!remaining.isEmpty()) {
            // Если одна группа достигла минимума, остальные отдаём другой
            if (group1.points.size() + remaining.size() == MIN_ENTRIES) {
                for (LocationObject p : remaining) group1.addPoint(p);
                break;
            }
            if (group2.points.size() + remaining.size() == MIN_ENTRIES) {
                for (LocationObject p : remaining) group2.addPoint(p);
                break;
            }

            // Выбираем точку с максимальной разницей приростов площадей
            LocationObject best = null;
            double maxDiff = -Double.MAX_VALUE;
            boolean addToFirst = true;

            for (LocationObject p : remaining) {
                MBR union1 = group1.mbr.union(new MBR(p.getPoint().x, p.getPoint().y, p.getPoint().x, p.getPoint().y));
                double inc1 = union1.area() - group1.mbr.area();
                MBR union2 = group2.mbr.union(new MBR(p.getPoint().x, p.getPoint().y, p.getPoint().x, p.getPoint().y));
                double inc2 = union2.area() - group2.mbr.area();
                double diff = Math.abs(inc1 - inc2);
                if (diff > maxDiff) {
                    maxDiff = diff;
                    best = p;
                    addToFirst = inc1 < inc2;
                } else if (diff == maxDiff) {
                    // При равенстве выбираем группу с меньшей площадью, затем с меньшим количеством элементов
                    if (group1.mbr.area() < group2.mbr.area()) addToFirst = true;
                    else if (group1.mbr.area() > group2.mbr.area()) addToFirst = false;
                    else addToFirst = group1.points.size() <= group2.points.size();
                }
            }

            if (addToFirst) group1.addPoint(best);
            else group2.addPoint(best);
            remaining.remove(best);
        }

        parent.children.remove(leaf);
        parent.addChild(group1);
        parent.addChild(group2);

        if (parent.children.size() > MAX_ENTRIES) splitInternal(parent);
    }

    /**
     * Квадратичное разделение внутреннего узла.
     * Аналогично splitLeafQuadratic, но оперирует дочерними узлами.
     */
    private void splitInternalQuadratic(InternalNode node, InternalNode parent) {
        List<Node> children = node.children;
        int[] seeds = pickSeedsNodes(children);
        Node seed1 = children.get(seeds[0]);
        Node seed2 = children.get(seeds[1]);

        InternalNode group1 = new InternalNode();
        InternalNode group2 = new InternalNode();
        group1.addChild(seed1);
        group2.addChild(seed2);

        List<Node> remaining = new ArrayList<>(children);
        remaining.remove(seed1);
        remaining.remove(seed2);

        while (!remaining.isEmpty()) {
            if (group1.children.size() + remaining.size() == MIN_ENTRIES) {
                for (Node n : remaining) group1.addChild(n);
                break;
            }
            if (group2.children.size() + remaining.size() == MIN_ENTRIES) {
                for (Node n : remaining) group2.addChild(n);
                break;
            }

            Node best = null;
            double maxDiff = -Double.MAX_VALUE;
            boolean addToFirst = true;

            for (Node n : remaining) {
                MBR union1 = group1.mbr.union(n.mbr);
                double inc1 = union1.area() - group1.mbr.area();
                MBR union2 = group2.mbr.union(n.mbr);
                double inc2 = union2.area() - group2.mbr.area();
                double diff = Math.abs(inc1 - inc2);
                if (diff > maxDiff) {
                    maxDiff = diff;
                    best = n;
                    addToFirst = inc1 < inc2;
                } else if (diff == maxDiff) {
                    if (group1.mbr.area() < group2.mbr.area()) addToFirst = true;
                    else if (group1.mbr.area() > group2.mbr.area()) addToFirst = false;
                    else addToFirst = group1.children.size() <= group2.children.size();
                }
            }

            if (addToFirst) group1.addChild(best);
            else group2.addChild(best);
            remaining.remove(best);
        }

        parent.children.remove(node);
        parent.addChild(group1);
        parent.addChild(group2);

        if (parent.children.size() > MAX_ENTRIES) splitInternal(parent);
    }

    /**
     * Выбор семян для листа: пара точек, чей объединённый MBR имеет максимальную площадь.
     */
    private int[] pickSeedsPoints(List<LocationObject> points) {
        int seed1 = 0, seed2 = 0;
        double maxArea = -1;
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                Point p1 = points.get(i).getPoint();
                Point p2 = points.get(j).getPoint();
                MBR mbr = new MBR(
                        Math.min(p1.x, p2.x), Math.min(p1.y, p2.y),
                        Math.max(p1.x, p2.x), Math.max(p1.y, p2.y)
                );
                double area = mbr.area();
                if (area > maxArea) {
                    maxArea = area;
                    seed1 = i;
                    seed2 = j;
                }
            }
        }
        return new int[]{seed1, seed2};
    }

    /**
     * Выбор семян для внутреннего узла: пара узлов, чей объединённый MBR имеет максимальную площадь.
     */
    private int[] pickSeedsNodes(List<Node> nodes) {
        int seed1 = 0, seed2 = 0;
        double maxArea = -1;
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                MBR union = nodes.get(i).mbr.union(nodes.get(j).mbr);
                double area = union.area();
                if (area > maxArea) {
                    maxArea = area;
                    seed1 = i;
                    seed2 = j;
                }
            }
        }
        return new int[]{seed1, seed2};
    }

    // ====================== Рекурсивные методы поиска ======================

    /**
     * Рекурсивный обход для поиска в радиусе.
     * @param node текущий узел
     * @param center центр круга
     * @param radius радиус
     * @param result накопитель результатов
     */
    private void searchRadiusRec(Node node, Point center, double radius, List<LocationObject> result) {
        if (node.mbr == null) return;
        if (!node.mbr.intersectsCircle(center, radius)) return;
        if (node.isLeaf()) {
            for (LocationObject p : ((LeafNode) node).points) {
                if (center.distanceTo(p.getPoint()) <= radius) result.add(p);
            }
        } else {
            for (Node child : ((InternalNode) node).children) {
                searchRadiusRec(child, center, radius, result);
            }
        }
    }

    /**
     * Рекурсивный обход для поиска k ближайших.
     * @param node текущий узел
     * @param center точка запроса
     * @param k количество искомых объектов
     * @param pq Max-куча с текущими кандидатами (дальний в начале)
     */
    private void searchNearestRec(Node node, Point center, int k, PriorityQueue<LocationObject> pq) {
        if (node == null || node.mbr == null) return;
        double minDist = node.mbr.distanceToPoint(center);
        // Отсекаем, если минимальное расстояние до узла больше максимального среди найденных
        if (pq.size() >= k && minDist > center.distanceTo(pq.peek().getPoint())) return;

        if (node.isLeaf()) {
            for (LocationObject p : ((LeafNode) node).points) {
                pq.offer(p);
                if (pq.size() > k) pq.poll();
            }
        } else {
            List<Node> children = ((InternalNode) node).children;
            // Сортируем детей по расстоянию от точки до их MBR для раннего отсечения
            children.sort(Comparator.comparingDouble(c -> c.mbr.distanceToPoint(center)));
            for (Node child : children) {
                searchNearestRec(child, center, k, pq);
            }
        }
    }
}