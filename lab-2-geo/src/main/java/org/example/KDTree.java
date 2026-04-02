package org.example;

import java.util.*;

/**
 * KD-дерево для поиска ближайших соседей и поиска в радиусе.
 * Реализовано как рекурсивная структура с разделением по осям.
 * Каждый узел хранит одну точку и разбивает пространство на две половины
 * по оси, которая чередуется с глубиной.
 */
class KDTree implements SpatialIndex<LocationObject> {
    private KDNode root;
    private int size = 0;

    /**
     * Узел KD-дерева.
     * Хранит точку, ссылки на левое и правое поддеревья,
     * а также ось, по которой производится разбиение в данном узле.
     */
    private static class KDNode {
        LocationObject point;   // точка, хранящаяся в узле
        KDNode left, right;     // левое и правое поддеревья
        int axis;               // 0 - разбиение по X, 1 - по Y

        KDNode(LocationObject point, int axis) {
            this.point = point;
            this.axis = axis;
        }
    }

    /**
     * Вставляет объект в дерево.
     * @param object добавляемый географический объект
     */
    @Override
    public void insert(LocationObject object) {
        root = insertRec(root, object, 0);
        size++;
    }

    /**
     * Рекурсивная вставка.
     * @param node текущий узел (поддерево)
     * @param object вставляемый объект
     * @param depth текущая глубина (для определения оси разбиения)
     * @return корень поддерева после вставки
     */
    private KDNode insertRec(KDNode node, LocationObject object, int depth) {
        if (node == null) {
            return new KDNode(object, depth % 2);
        }
        int axis = node.axis;
        double val = (axis == 0) ? object.getPoint().x : object.getPoint().y;
        double nodeVal = (axis == 0) ? node.point.getPoint().x : node.point.getPoint().y;

        if (val < nodeVal) {
            node.left = insertRec(node.left, object, depth + 1);
        } else {
            node.right = insertRec(node.right, object, depth + 1);
        }
        return node;
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
     * @param radius поиска
     * @param result накопитель результатов
     */
    private void searchRadiusRec(KDNode node, Point center, double radius, List<LocationObject> result) {
        if (node == null) return;
        double dist = center.distanceTo(node.point.getPoint());
        if (dist <= radius) {
            result.add(node.point);
        }
        int axis = node.axis;
        double diff = (axis == 0) ? (center.x - node.point.getPoint().x) : (center.y - node.point.getPoint().y);
        // Сначала обходим поддерево, которое находится с той же стороны от разделяющей плоскости,
        // что и центр запроса
        KDNode first = (diff <= 0) ? node.left : node.right;
        KDNode second = (diff <= 0) ? node.right : node.left;
        searchRadiusRec(first, center, radius, result);
        // Если расстояние до разделяющей плоскости меньше радиуса, нужно обойти и второе поддерево
        if (Math.abs(diff) <= radius) {
            searchRadiusRec(second, center, radius, result);
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
        if (k <= 0 || root == null) return Collections.emptyList();
        // Max-куча: хранит узлы, отсортированные по расстоянию (дальний в начале)
        PriorityQueue<KDNode> pq = new PriorityQueue<>((a, b) ->
                Double.compare(center.distanceTo(b.point.getPoint()), center.distanceTo(a.point.getPoint())));
        nearestNeighborRec(root, center, k, pq);
        List<LocationObject> result = new ArrayList<>();
        while (!pq.isEmpty()) {
            result.add(pq.poll().point);
        }
        Collections.reverse(result); // порядок от ближайшего к дальнему
        return result;
    }

    /**
     * Рекурсивная реализация поиска k ближайших соседей.
     * @param node текущий узел
     * @param center точка, от которой ищем
     * @param k требуемое количество
     * @param pq приоритетная очередь, хранящая текущих кандидатов (дальний в начале)
     */
    private void nearestNeighborRec(KDNode node, Point center, int k, PriorityQueue<KDNode> pq) {
        if (node == null) return;
        double dist = center.distanceTo(node.point.getPoint());
        pq.offer(node);
        if (pq.size() > k) pq.poll(); // удаляем самый дальний

        int axis = node.axis;
        double diff = (axis == 0) ? (center.x - node.point.getPoint().x) : (center.y - node.point.getPoint().y);
        KDNode first = (diff <= 0) ? node.left : node.right;
        KDNode second = (diff <= 0) ? node.right : node.left;
        // сначала обходим поддерево, содержащее точку запроса
        nearestNeighborRec(first, center, k, pq);

        // Проверяем, нужно ли обходить второе поддерево:
        // если мы нашли меньше k кандидатов, обязательно обходим,
        // иначе обходим, только если расстояние до разделяющей плоскости меньше
        // расстояния до самого дальнего из найденных кандидатов
        if (pq.size() < k) {
            nearestNeighborRec(second, center, k, pq);
        } else {
            double farthestDist = center.distanceTo(pq.peek().point.getPoint()); // самое дальнее расстояние
            if (Math.abs(diff) < farthestDist) {
                nearestNeighborRec(second, center, k, pq);
            }
        }
    }
}

