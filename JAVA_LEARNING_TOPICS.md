# Java Learning Topics

A shortlist of useful topics to explore next, especially after experimenting with threads and garbage collection.

## 1. Java Memory Model

Learn visibility, happens-before relationships, safe publication, and when to use `volatile`, locks, or atomics. A useful exercise is to create a small data race, observe its behavior, then fix it in different ways.

## 2. Virtual-Thread Boundaries

Explore cancellation, thread-local usage, blocking calls, and pinning. Compare virtual threads with bounded platform-thread pools, and check which APIs and behaviors apply to the JDK version being used.

## 3. Reliable Performance Measurement

Study JIT warmup, dead-code elimination, allocation profiling, and JMH. These help distinguish a real performance difference from benchmark noise.

## 4. Object Lifetime and Memory

Practice reading heap dumps and dominator trees, following paths to GC roots, and distinguishing allocation rate from retained memory. Also learn how Java heap usage differs from native memory usage.

## 5. Data-Structure Costs

Explore the `equals`/`hashCode` contract, mutable map keys, resizing, and load factors. The `HashSet` workload in `testing-java` is a useful starting point.

## Suggested starting point

Start with the Java Memory Model. Understanding it helps prevent concurrency bugs that can be difficult to reproduce.
