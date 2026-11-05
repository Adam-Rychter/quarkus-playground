package cz.rychter.testingjava;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Thread)
public class ThreadBenchmark {

    @Param({"1", "16", "64"})
    public int threadCount;

    @Param({"1000", "5000"})
    public int elementCount;

    @Benchmark
    public long platformThreads() throws InterruptedException {
        return runThreads(Thread.ofPlatform().factory());
    }

    @Benchmark
    public long virtualThreads() throws InterruptedException {
        return runThreads(Thread.ofVirtual().factory());
    }

    private long runThreads(ThreadFactory threadFactory) throws InterruptedException {
        if ((threadCount != 1 && threadCount != 16 && threadCount != 64)
                || (elementCount != 1000 && elementCount != 5000)) {
            throw new IllegalArgumentException("Benchmark parameters exceed the bounded workload matrix");
        }

        Thread[] threads = new Thread[threadCount];
        long[] results = new long[threadCount];
        Throwable[] failures = new Throwable[threadCount];
        CountDownLatch startGate = new CountDownLatch(1);

        try {
            for (int i = 0; i < threadCount; i++) {
                int index = i;
                threads[i] = threadFactory.newThread(() -> {
                    try {
                        startGate.await();
                        results[index] = buildSetAndHash(elementCount);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        failures[index] = interrupted;
                    } catch (RuntimeException | Error failure) {
                        failures[index] = failure;
                    }
                });
                threads[i].start();
            }
        } catch (RuntimeException | Error startFailure) {
            startGate.countDown();
            for (Thread thread : threads) {
                if (thread == null) {
                    break;
                }
                try {
                    thread.join();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    startFailure.addSuppressed(interrupted);
                    break;
                }
            }
            throw startFailure;
        }
        startGate.countDown();

        for (Thread thread : threads) {
            thread.join();
        }

        long result = 0;
        for (int i = 0; i < threadCount; i++) {
            if (failures[i] != null) {
                throw new IllegalStateException("Thread workload failed at index " + i, failures[i]);
            }
            result += results[i];
        }
        return result;
    }

    private static long buildSetAndHash(int expectedSize) {
        Set<Integer> values = new HashSet<>(hashSetCapacity(expectedSize));
        for (int value = 0; value < expectedSize; value++) {
            values.add(value + 1_000);
        }
        if (values.size() != expectedSize) {
            throw new IllegalStateException("Expected " + expectedSize + " unique values");
        }
        return values.hashCode();
    }

    private static int hashSetCapacity(int expectedSize) {
        return Math.max(16, (int) (expectedSize / 0.75f) + 1);
    }
}
