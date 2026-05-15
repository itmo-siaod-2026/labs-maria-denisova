package com.example.concurrentmap;


/**
 * Утилита для ручного профилирования.
 * Запуск с флагами JVM, например:
 *   -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints
 *   -XX:StartFlightRecording=filename=profile.jfr
 *   или с async-profiler: -agentpath:/path/to/libasyncProfiler.so=start,file=profile.html
 */
public class ConcurrentHashMapProfile {

    public static void main(String[] args) throws Exception {
        ConcurrentHashMap<Integer, byte[]> map = new ConcurrentHashMap<>();

        // Разогрев
        for (int i = 0; i < 100_000; i++) {
            map.put(i, new byte[256]);
        }

        // Нагрузка для профилирования CPU/памяти
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 1_000_000; i++) {
                    int key = (int) (Math.random() * 100_000);
                    map.put(key, new byte[128]);
                    byte[] val = map.get(key);
                    if (val != null) {
                        // фиктивная работа
                        val[0] = 1;
                    }
                }
            });
            threads[t].start();
        }

        for (Thread t : threads) {
            t.join();
        }

        System.out.println("Size: " + map.size());
        // Точка для снятия дампа кучи:
        // jmap -dump:live,format=b,file=heap.bin <pid>
        System.out.println("Press Enter to exit...");
        System.in.read();
    }
}
