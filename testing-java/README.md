# testing-java

A dependency-free Java 21 Maven module for experimenting with core Java features. It currently contains a small comparison of platform threads and virtual threads.

## Thread comparison

`cz.rychter.testingjava.TestingThreads` runs the same workload using platform threads and virtual threads. In each trial, it starts 500 threads; each thread builds a `HashSet` containing 100,000 unique random `Double` values. The trial measures elapsed time from before thread creation until all threads finish and their resulting set sizes are checked.

The benchmark runs 100 trials per thread type. It alternates which type runs first in each round and prints the average elapsed time in milliseconds. Each trial creates new threads, so the timing includes thread creation, scheduling, the workload, and joining—not just the set-building work.

The sets are not retained after their builder method returns; only each set's size is stored. They then become eligible for garbage collection, but collection is not immediate or guaranteed at that point.

## Build and run

From the repository root:

```bash
./mvnw -pl testing-java verify
java -cp testing-java/target/classes cz.rychter.testingjava.TestingThreads
```

The benchmark is resource-intensive: one trial builds 50 million set entries across its 500 threads. Adjust `THREAD_COUNT`, `SET_SIZE`, and `BENCHMARK_ROUNDS` in `TestingThreads` to suit the machine and experiment.

## Interpreting results

This is an exploratory benchmark, not a rigorous microbenchmark. It has no dedicated warmup or process forks, and results are affected by the JDK version, available processors, garbage collection, and system load. It measures the end-to-end cost of creating and running many threads for this particular allocation-heavy workload; it does not establish that one thread type is always faster. For reliable comparisons, use the same JDK and machine, repeat runs, and consider a benchmarking harness such as JMH.
