# Load test & observability (`load-test/`)

Gatling load tests plus a Prometheus/Grafana stack to observe `quarkus-rest-ms` (latency, heap/GC, CPU, Agroal pool, PostgreSQL) and to compare the **JVM** build with the **GraalVM native** build under identical container limits.

## Stack

| Concern | Tool |
| :--- | :--- |
| Load generation | Gatling 3.16 (Java DSL, `gatling-maven-plugin`) |
| App metrics | `quarkus-micrometer-registry-prometheus` -> `/q/metrics` (JVM, HTTP, Agroal) |
| DB metrics | `postgres-exporter` + `pg_stat_statements` |
| Dashboards | Prometheus (2s scrape) + Grafana (provisioned dashboard "Quarkus load test") |
| Container RSS/CPU | `podman stats` sampled to CSV during each run |
| Runtime | Plain Podman (no compose needed); app, DB and monitoring share the `loadtest` network |

For a detailed explanation of how the module works, how to configure and extend it, and how to reuse the approach in other projects, see [`DEVELOPER_GUIDE.md`](DEVELOPER_GUIDE.md).

Everything runs in `%prod` mode (SQL logging off, no dev mode), in a container limited to `APP_CPUS` / `APP_MEM`.

## Quick start

```bash
./load-test/loadtest.sh build all        # fast-jar + native (native takes ~7-10 min, needs ~12 GB RAM)
./load-test/loadtest.sh infra            # Postgres :15432, Prometheus :19090, Grafana :13000
./load-test/loadtest.sh app jvm          # or: app native   (service on :18080)
./load-test/loadtest.sh run Smoke
./load-test/loadtest.sh run Load
./load-test/loadtest.sh info             # image sizes, startup time, memory
./load-test/loadtest.sh down
```

Open Grafana at <http://localhost:13000> (anonymous admin) while a simulation runs. Gatling's HTML report path and the container-stats CSV are printed at the end of `run`.

Pass any simulation tunable as `-Dname=value`, e.g. `./load-test/loadtest.sh run Load -Drate=200 -DdurationSec=300`.
Run one mode at a time (they share port 18080) and call `reset-db` or restart `infra` between runs for comparable table sizes.

## Scenarios

| Simulation | Model | What it shows | Tunables |
| :--- | :--- | :--- | :--- |
| `Smoke` | 5 users | Scripts/environment are valid | |
| `Load` | open, steady | Baseline p50/p95/p99, heap and GC at a comfortable rate (asserts <1% errors, p95 <1s) | `rate`, `durationSec`, `warmupSec` |
| `Stress` | open, ramp | Saturation knee: where latency explodes / errors begin | `maxRate`, `durationSec`, `holdSec` |
| `Spike` | open, burst | Absorption of a sudden surge and recovery time | `baseRate`, `spikeRate`, `spikeSec` |
| `Soak` | open, long | Leaks and GC drift (heap, threads, connections) | `rate`, `durationMin` |
| `PoolExhaustion` | closed, N concurrent | Agroal queueing, acquisition timeouts | `users`, `rampSec`, `holdSec` |
| `DuplicateRace` | N users, same UTI | Correctness of check-then-insert under concurrency | `users`, `rounds` |

Open model = arrival rate is fixed regardless of response time (reveals overload); closed model = fixed concurrency (reveals queueing on bounded resources).

## Environment knobs (`loadtest.sh`)

| Variable | Default | Effect |
| :--- | :--- | :--- |
| `APP_CPUS` | `4` | Container CPU limit |
| `APP_MEM` | `768m` | Container memory limit |
| `POOL_MAX` / `POOL_MIN` | `20` / `5` | Agroal pool size (`min` must be <= `max`) |
| `APP_OPTS` | empty | Extra flags, e.g. `-Xmx256m`, `-Djdk.tracePinnedThreads=short` |

Example, reproducing pool exhaustion:

```bash
APP_CPUS=1 POOL_MAX=3 POOL_MIN=1 ./load-test/loadtest.sh app jvm
./load-test/loadtest.sh run PoolExhaustion -Dusers=400
```

## What to watch in Grafana

- **Agroal pool**: `active` pinned at max with `awaiting` > 0 means exhaustion; `blocking time` rising precedes timeouts.
- **Heap used/committed/max** and **GC pause share** (JVM only; native uses SerialGC with a small heap).
- **CPU**: a saturated container makes transactions long, which holds connections longer, which starves the pool.
- **PostgreSQL connections by state**: many `idle in transaction` with an idle DB means the *app* is the bottleneck.

## Findings from the first runs (8-core laptop, Gatling on the same host)

Treat these as relative indications, not benchmarks.

1. **Native vs JVM** (`Load`, rate=100, 40s + 10s warmup, 4 CPUs, 768m): both 0 errors at ~315 req/s. JVM p95 ~1024 ms (includes JIT warm-up), native p95 ~13 ms. Startup 4.8 s (JVM) vs 0.16 s (native). Memory after the run: JVM RSS ~250-300 MB, native ~45 MB. Image 442 MB vs 208 MB. Native build takes ~7 min; peak throughput of a warmed-up JVM can still win in long runs, so check `Soak`.
2. **CPU starvation causes pool exhaustion**: the same `Load` with 2 CPUs failed >50% of requests with `Sorry, acquisition timeout!` although PostgreSQL was idle (statements ~0.5 ms, connections `idle in transaction`). The connection was held while the request waited for CPU. With 4 CPUs: no errors.
3. **Pool exhaustion surfaces as HTTP 500**, not 503 with `Retry-After`; a candidate improvement (exception mapper).
4. **`DuplicateRace` finds a real bug**: concurrent duplicate `transactionId` POSTs yield a few `500`s (unique-constraint violation after the check-then-insert race) instead of `409`. Fix idea: catch the constraint violation and map it to 409.
5. A pool of 3 with short transactions and 150 clients did **not** fail: queueing stays below the 5 s acquisition timeout. Exhaustion needs long-held connections (CPU starvation, slow queries).
6. The UBI image defaults to ParallelGC with `MaxRAMPercentage=80`; tune with `APP_OPTS` when comparing GC behaviour.
