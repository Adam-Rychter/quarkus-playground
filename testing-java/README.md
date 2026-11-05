# testing-java

A Java 21 Maven module for experimenting with core Java features. It contains a platform-thread versus virtual-thread comparison and JMH benchmarks for heap allocation.

## Thread performance benchmark

`cz.rychter.testingjava.ThreadBenchmark` provides separate JMH benchmark methods for platform-thread-per-task and virtual-thread-per-task runs. Each operation starts a batch of 1, 16, or 64 threads; each worker builds and hashes an identical deterministic `HashSet<Integer>` containing 1,000 or 5,000 values. The bounded parameter sets are intentional: platform threads consume native resources, and this benchmark should not be used to exhaust the host.

Run the complete comparison from the repository root:

```bash
./testing-java/src/main/resources/run-thread-benchmark.sh
```

The script builds the shaded JMH jar, records separate JMH JSON results with the GC profiler, and performs a fresh-JVM peak-RSS probe for each parameter pair. It generates `testing-java/THREAD_BENCHMARK_REPORT.md` and preserves raw JMH JSON, RSS data, and probe logs in a timestamped directory under `testing-java/target/thread-benchmark-results/`. Set `THREAD_BENCHMARK_RESULTS_DIR` or `THREAD_BENCHMARK_REPORT` to choose another output location. Additional JMH options apply to the timing runs; for a smoke run, for example:

```bash
./testing-java/src/main/resources/run-thread-benchmark.sh -wi 0 -i 1 -r 100ms
```

The report explains the methodology and limitations. JMH's normalized allocation metric is Java heap allocation, not native thread-stack memory. Peak RSS is a whole-process high-water mark and is collected separately in a new JVM per case; it includes JVM and runtime overhead and uses a short measurement, so it is only a coarse process-memory comparison.

## Thread comparison

`cz.rychter.testingjava.TestingThreads` runs the same workload using platform threads and virtual threads. In each trial, it starts 500 threads; each thread builds a `HashSet` containing 100,000 unique random `Double` values. The trial measures elapsed time from before thread creation until all threads finish and their resulting set sizes are checked.

The benchmark runs 100 trials per thread type. It alternates which type runs first in each round and prints the average elapsed time in milliseconds. Each trial creates new threads, so the timing includes thread creation, scheduling, the workload, and joining—not just the set-building work.

The sets are not retained after their builder method returns; only each set's size is stored. They then become eligible for garbage collection, but collection is not immediate or guaranteed at that point.

### Java Memory Model connection

Each worker writes its result to a distinct element of `generatedSetSizes`, so workers do not race with one another. Starting a thread happens-before actions in that thread, and successfully joining it happens-before the joining thread continues. Therefore, after all joins, the main thread is guaranteed to observe the workers' writes. The benchmark's timing still includes much more than thread scheduling, so it is not an isolated comparison of thread types.

## Heap allocation benchmark

`cz.rychter.testingjava.AllocationBenchmark` uses JMH to compare three workloads at two sizes: a primitive array, an array containing separately allocated records, and a `HashSet` of boxed integers. Results returned from each benchmark are consumed by JMH so the work cannot simply be discarded. Each JMH worker has its own benchmark state (`Scope.Thread`); this measures allocation behavior, not cross-thread visibility.

Build and run the allocation benchmarks from the repository root:

```bash
./testing-java/src/main/resources/run-allocation-benchmark.sh
```

In the profiler output, `gc.alloc.rate.norm` reports normalized allocated bytes per operation. Compare workloads at the same `elementCount`; the reported allocation includes their backing arrays, collection nodes/tables, and boxed/object values. It is not the amount of live heap after an operation, nor does it mean the garbage collector immediately reclaims those objects.

Additional JMH arguments can be passed to the script, for example:

```bash
./testing-java/src/main/resources/run-allocation-benchmark.sh -p elementCount=1000
```

## Build and run

From the repository root:

```bash
./mvnw -pl testing-java verify
java -cp testing-java/target/classes cz.rychter.testingjava.TestingThreads
```

The benchmark is resource-intensive: one trial builds 50 million set entries across its 500 threads. Adjust `THREAD_COUNT`, `SET_SIZE`, and `BENCHMARK_ROUNDS` in `TestingThreads` to suit the machine and experiment.

## Interpreting results

This is an exploratory benchmark, not a rigorous microbenchmark. It has no dedicated warmup or process forks, and results are affected by the JDK version, available processors, garbage collection, and system load. It measures the end-to-end cost of creating and running many threads for this particular allocation-heavy workload; it does not establish that one thread type is always faster. For reliable comparisons, use the same JDK and machine, repeat runs, and consider a benchmarking harness such as JMH.
