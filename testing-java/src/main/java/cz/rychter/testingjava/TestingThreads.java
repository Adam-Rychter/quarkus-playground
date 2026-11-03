package cz.rychter.testingjava;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class TestingThreads {

    private static final int THREAD_COUNT = 500;
    private static final int SET_SIZE = 1_000_00;
    private static final int BENCHMARK_ROUNDS = 100;

    public static void main(String[] args) throws InterruptedException {
        Averages averages = testAlternatingThreads();
        System.out.println("Average system-thread time: " + averages.systemThreads() + " ms");
        System.out.println("Average virtual-thread time: " + averages.virtualThreads() + " ms");
    }

    public static BigDecimal testSystemThreads() throws InterruptedException {
        return measureThreads(false);
    }

    public static BigDecimal testVirtualThreads() throws InterruptedException {
        return measureThreads(true);
    }

    public static Averages testAlternatingThreads() throws InterruptedException {
        BigDecimal systemThreadTotal = BigDecimal.ZERO;
        BigDecimal virtualThreadTotal = BigDecimal.ZERO;

        for (int round = 0; round < BENCHMARK_ROUNDS; round++) {
            if (round % 2 == 0) {
                systemThreadTotal = systemThreadTotal.add(testSystemThreads());
                virtualThreadTotal = virtualThreadTotal.add(testVirtualThreads());
            } else {
                virtualThreadTotal = virtualThreadTotal.add(testVirtualThreads());
                systemThreadTotal = systemThreadTotal.add(testSystemThreads());
            }
        }

        BigDecimal rounds = BigDecimal.valueOf(BENCHMARK_ROUNDS);
        return new Averages(
                systemThreadTotal.divide(rounds, RoundingMode.DOWN),
                virtualThreadTotal.divide(rounds, RoundingMode.DOWN));
    }

    private static BigDecimal measureThreads(boolean virtual) throws InterruptedException {
        Thread[] threads = new Thread[THREAD_COUNT];
        int[] generatedSetSizes = new int[THREAD_COUNT];
        long startTime = System.nanoTime();

        for (int i = 0; i < THREAD_COUNT; i++) {
            int threadIndex = i;
            Runnable workload = () -> generatedSetSizes[threadIndex] = buildRandomDoubles();
            if (virtual) {
                threads[i] = Thread.ofVirtual().unstarted(workload);
            } else {
                threads[i] = Thread.ofPlatform().unstarted(workload);
            }
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        for (int generatedSetSize : generatedSetSizes) {
            if (generatedSetSize != SET_SIZE) {
                throw new IllegalStateException("Expected a set of " + SET_SIZE + " random doubles");
            }
        }

        return BigDecimal.valueOf(System.nanoTime() - startTime, 6);
    }

    private static int buildRandomDoubles() {
        Set<Double> randomDoubles = new HashSet<>(SET_SIZE);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (randomDoubles.size() < SET_SIZE) {
            randomDoubles.add(random.nextDouble());
        }
        return randomDoubles.size();
    }

    public record Averages(BigDecimal systemThreads, BigDecimal virtualThreads) {
    }
}
