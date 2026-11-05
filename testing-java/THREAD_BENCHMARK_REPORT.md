# Platform threads vs. virtual threads

## Results

| Threads × values/set | Platform time (µs/op, mean [CI]) | Virtual time (µs/op, mean [CI]) | Platform / virtual | Platform alloc (B/op) | Virtual alloc (B/op) | Platform peak RSS (MiB) | Virtual peak RSS (MiB) |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 × 1,000 | 87.11 [79.61, 94.62] | 21.61 [16.50, 26.72] | 4.03× | 56,792.62 | 56,968.89 | 87.20 | 116.77 |
| 1 × 5,000 | 151.56 [132.63, 170.50] | 70.78 [31.16, 110.41] | 2.14× | 273,369.07 | 273,542.37 | 134.29 | 142.57 |
| 16 × 1,000 | 809.78 [626.00, 993.55] | 83.74 [75.21, 92.27] | 9.67× | 906,825.97 | 909,735.43 | 130.21 | 279.85 |
| 16 × 5,000 | 1,051.95 [928.87, 1,175.03] | 352.25 [328.72, 375.78] | 2.99× | 4,371,797.98 | 4,373,789.13 | 210.34 | 256.61 |
| 64 × 1,000 | 3,748.65 [3,426.33, 4,070.97] | 262.27 [239.83, 284.72] | 14.29× | 3,626,730.04 | 3,635,139.73 | 134.43 | 267.34 |
| 64 × 5,000 | 4,635.04 [3,380.75, 5,889.34] | 1,231.90 [1,182.98, 1,280.81] | 3.76× | 17,487,600.62 | 17,492,702.02 | 222.29 | 287.54 |

## Snapshot

- Virtual threads were faster in 6/6 cases; the geometric-mean speedup was 4.87× (range 2.14×–14.29×).
- Normalized heap allocation differed by at most 0.32% between thread types for matched cases.
- Virtual-thread probe peak RSS was higher in 6/6 cases; the measured relative difference ranged from +6.2% to +114.9%. Treat this only as a whole-process high-water observation, not a thread-stack comparison.

## Relative elapsed-time chart

**1 threads × 1,000 values per set**

- Platform: `████████████████████████` 87.11 µs/op
- Virtual: `██████` 21.61 µs/op

**1 threads × 5,000 values per set**

- Platform: `████████████████████████` 151.56 µs/op
- Virtual: `███████████` 70.78 µs/op

**16 threads × 1,000 values per set**

- Platform: `████████████████████████` 809.78 µs/op
- Virtual: `██` 83.74 µs/op

**16 threads × 5,000 values per set**

- Platform: `████████████████████████` 1,051.95 µs/op
- Virtual: `████████` 352.25 µs/op

**64 threads × 1,000 values per set**

- Platform: `████████████████████████` 3,748.65 µs/op
- Virtual: `██` 262.27 µs/op

**64 threads × 5,000 values per set**

- Platform: `████████████████████████` 4,635.04 µs/op
- Virtual: `██████` 1,231.90 µs/op

## Method

- Environment: `openjdk version "25.0.4.1" 2026-08-18` on `Linux-7.0.0-38-generic-x86_64-with-glibc2.39`; 12 logical CPUs reported by Python.
- Each operation starts a bounded batch of 1, 16, or 64 threads. Every worker builds a `HashSet<Integer>` of 1,000 or 5,000 deterministic unique values, checks its size, and hashes its contents; the aggregate hash is returned to JMH.
- The platform and virtual paths use the same workload, a start gate, one JMH harness thread, and one fork. Time columns show the JMH mean and its reported confidence-interval bounds. Timing runs used the benchmark defaults: two 1 s warmup iterations, three 1 s measurement iterations, and one fork. `gc.alloc.rate.norm` is JMH's normalized allocation metric when available.
- Peak RSS is captured in a fresh JVM process per thread-count/workload pair with `/usr/bin/time`; these quick probes use one 100 ms JMH measurement and no fork so the timed process is the measured JVM. Values include JVM baseline/committed heap and runtime overhead, not only thread stacks.
- Raw JMH JSON, per-probe logs, and the RSS CSV are retained in `target/thread-benchmark-results/run-20261105T125603-28120`.

## Interpretation and limitations

- A platform/virtual ratio above 1 means the virtual-thread operation was faster; below 1 means it was slower. These results apply to this allocation-heavy workload and this machine/JDK, not to all applications or thread workloads.
- JMH allocation metrics describe Java heap allocation per operation; they do not measure native thread-stack memory. Peak RSS is a whole-process high-water mark, including JVM startup and heap/runtime costs, and the short RSS probe is not a steady-state or per-thread memory measurement.
- Results vary with CPU scheduling, garbage collection, JIT compilation, available memory, and background system load. Re-run under comparable conditions before drawing conclusions.
