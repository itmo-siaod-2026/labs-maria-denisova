package org.example;

import java.util.*;

/**
 * Октодерево для 2D (квадродерево). Разбивает пространство на 4 подквадранта.
 * Каждый узел хранит точки, пока их количество не превышает MAX_POINTS_PER_NODE,
 * после чего узел разбивается на четыре дочерних узла. Поиск выполняется с отсечением
 * поддеревьев, не пересекающих область запроса.
 */
class OctoTree implements SpatialIndex<LocationObject> {
    private static final int MAX_POINTS_PER_NODE = 10;
    private static final int MAX_DEPTH = 20;

    private OctoTreeNode root;
    private final double minX, minY, maxX, maxY; // границы всей области

    /**
     * Конструктор октодерева.
     * @param minX минимальная координата X (долгота) всей области
     * @param minY минимальная координата Y (широта) всей области
     * @param maxX максимальная координата X
     * @param maxY максимальная координата Y
     */
    public OctoTree(double minX, double minY, double maxX, double maxY) {
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
        root = new OctoTreeNode(minX, minY, maxX, maxY, 0);
    }

    /**
     * Узел октодерева (квадродерева). Хранит ограничивающий прямоугольник,
     * список точек (для листового узла) и массив дочерних узлов.
     */
    private static class OctoTreeNode {
        double minX, minY, maxX, maxY;
        int depth;
        List<LocationObject> points;
        OctoTreeNode[] children; // 4 детей для 2D

        /**
         * Конструктор узла.
         * @param minX левая граница
         * @param minY нижняя граница
         * @param maxX правая граница
         * @param maxY верхняя граница
         * @param depth глубина узла в дереве
         */
        OctoTreeNode(double minX, double minY, double maxX, double maxY, int depth) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.depth = depth;
            this.points = new ArrayList<>();
            this.children = null;
        }

        /** Проверяет, является ли узел листом (не имеет детей). */
        boolean isLeaf() {
            return children == null;
        }

        /**
         * Разбивает текущий листовой узел на четыре дочерних узла.
         * Точки из текущего узла распределяются по детям, после чего список точек очищается.
         */
        void subdivide() {
            double midX = (minX + maxX) / 2;
            double midY = (minY + maxY) / 2;
            children = new OctoTreeNode[4];
            children[0] = new OctoTreeNode(minX, minY, midX, midY, depth + 1);
            children[1] = new OctoTreeNode(midX, minY, maxX, midY, depth + 1);
            children[2] = new OctoTreeNode(minX, midY, midX, maxY, depth + 1);
            children[3] = new OctoTreeNode(midX, midY, maxX, maxY, depth + 1);
            for (LocationObject p : points) {
                addToChildren(p);
            }
            points.clear(); // больше не храним точки в этом узле
        }

        /**
         * Определяет, в какой из четырёх дочерних узлов следует поместить объект,
         * и вызывает insert для этого узла.
         * @param obj добавляемый объект
         */
        void addToChildren(LocationObject obj) {
            Point pt = obj.getPoint();
            // Индекс: 0 - левый нижний, 1 - правый нижний, 2 - левый верхний, 3 - правый верхний
            int idx = (pt.x < (minX + maxX) / 2 ? 0 : 1) + (pt.y < (minY + maxY) / 2 ? 0 : 2);
            children[idx].insert(obj);
        }

        /**
         * Вставляет объект в поддерево, корнем которого является текущий узел.
         * Если узел — лист и количество точек превышает порог, выполняется разбиение.
         * @param obj вставляемый объект
         */
        void insert(LocationObject obj) {
            if (!isLeaf()) {
                addToChildren(obj);
                return;
            }
            points.add(obj);
            if (points.size() > MAX_POINTS_PER_NODE && depth < MAX_DEPTH) {
                subdivide();
            }
        }
    }

    /**
     * Вставляет географический объект в дерево.
     * @param object объект с координатами
     */
    @Override
    public void insert(LocationObject object) {
        root.insert(object);
    }

    /**
     * Выполняет поиск всех объектов в заданном радиусе от центра.
     * @param center точка-центр круга
     * @param radius радиус поиска
     * @return список объектов, попавших в круг
     */
    @Override
    public List<LocationObject> searchRadius(Point center, double radius) {
        List<LocationObject> result = new ArrayList<>();
        searchRadiusRec(root, center, radius, result);
        return result;
    }

    /**
     * Рекурсивная реализация поиска в радиусе.
     * @param node текущий узел
     * @param center центр круга
     * @param radius радиус
     * @param result накопитель результатов
     */
    private void searchRadiusRec(OctoTreeNode node, Point center, double radius, List<LocationObject> result) {
        if (node == null) return;
        // Проверка пересечения круга с прямоугольником узла
        double closestX = Math.max(node.minX, Math.min(center.x, node.maxX));
        double closestY = Math.max(node.minY, Math.min(center.y, node.maxY));
        double dx = center.x - closestX;
        double dy = center.y - closestY;
        if (dx * dx + dy * dy > radius * radius) return;

        if (node.isLeaf()) {
            for (LocationObject obj : node.points) {
                if (center.distanceTo(obj.getPoint()) <= radius) {
                    result.add(obj);
                }
            }
        } else {
            for (int i = 0; i < 4; i++) {
                searchRadiusRec(node.children[i], center, radius, result);
            }
        }
    }

    /**
     * Поиск k ближайших объектов к заданной точке.
     * @param center точка, от которой измеряется расстояние
     * @param k количество искомых ближайших объектов
     * @return список из k ближайших объектов (от ближайшего к дальнему)
     */
    @Override
    public List<LocationObject> searchNearest(Point center, int k) {
        if (k <= 0) return Collections.emptyList();
        // Max-куча: хранит кандидатов, упорядоченных по убыванию расстояния
        PriorityQueue<LocationObject> pq = new PriorityQueue<>((a, b) ->
                Double.compare(center.distanceTo(b.getPoint()), center.distanceTo(a.getPoint())));
        searchNearestRec(root, center, k, pq);
        List<LocationObject> result = new ArrayList<>();
        while (!pq.isEmpty()) {
            result.add(pq.poll());
        }
        Collections.reverse(result); // порядок от ближайшего к дальнему
        return result;
    }

    /**
     * Рекурсивная реализация поиска k ближайших.
     * @param node текущий узел
     * @param center точка, от которой ищем
     * @param k требуемое количество
     * @param pq приоритетная очередь, хранящая текущих кандидатов (дальний в начале)
     */
    private void searchNearestRec(OctoTreeNode node, Point center, int k, PriorityQueue<LocationObject> pq) {
        if (node == null) return;
        // минимальное расстояние от точки до прямоугольника узла
        double dx = 0, dy = 0;
        if (center.x < node.minX) dx = node.minX - center.x;
        else if (center.x > node.maxX) dx = center.x - node.maxX;
        if (center.y < node.minY) dy = node.minY - center.y;
        else if (center.y > node.maxY) dy = center.y - node.maxY;
        double minDist = Math.sqrt(dx * dx + dy * dy);
        // Если уже нашли k кандидатов и минимальное расстояние до узла больше максимального из найденных,
        // узел можно отбросить.
        if (pq.size() >= k && minDist > center.distanceTo(pq.peek().getPoint())) {
            return;
        }
        if (node.isLeaf()) {
            for (LocationObject obj : node.points) {
                pq.offer(obj);
                if (pq.size() > k) pq.poll();
            }
        } else {
            // Сортируем детей по расстоянию от точки до их MBR (по возрастанию)
            List<OctoTreeNode> childrenList = Arrays.asList(node.children);
            childrenList.sort(Comparator.comparingDouble(child -> {
                double dxc = 0, dyc = 0;
                if (center.x < child.minX) dxc = child.minX - center.x;
                else if (center.x > child.maxX) dxc = center.x - child.maxX;
                if (center.y < child.minY) dyc = child.minY - center.y;
                else if (center.y > child.maxY) dyc = center.y - child.maxY;
                return Math.sqrt(dxc * dxc + dyc * dyc);
            }));
            for (OctoTreeNode child : childrenList) {
                searchNearestRec(child, center, k, pq);
            }
        }
    }
}