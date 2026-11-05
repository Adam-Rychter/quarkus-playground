package cz.rychter.testingjava;

import java.util.HashSet;
import java.util.Set;
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
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class AllocationBenchmark {

    @Param({"1000", "10000"})
    public int elementCount;

    @Benchmark
    public int[] allocatePrimitiveArray() {
        return new int[elementCount];
    }

    @Benchmark
    public Point[] allocateObjectGraph() {
        Point[] points = new Point[elementCount];
        for (int i = 0; i < elementCount; i++) {
            points[i] = new Point(i, i + 1);
        }
        return points;
    }

    @Benchmark
    public Set<Integer> buildHashSet() {
        Set<Integer> values = new HashSet<>(hashSetCapacity(elementCount));
        for (int i = 0; i < elementCount; i++) {
            values.add(1_000 + i);
        }
        return values;
    }

    private static int hashSetCapacity(int expectedSize) {
        return Math.max(16, (int) (expectedSize / 0.75f) + 1);
    }

    public record Point(int x, int y) {
    }
}
