# Load-Test Module: Developer Guide

How the `load-test/` module works, how it is configured, and how to extend it or copy the approach to another project. For quick-start commands and the first findings see [`README.md`](README.md).

---

## 1. Goals and design principles

The module answers: *how does a Quarkus service behave under load in terms of latency, CPU, memory, connection pool and database?* and *how does the JVM build compare with the native build?*

Principles that shape every decision:

1. **Test the production artifact, not dev mode.** The service runs as a container in `%prod` (SQL logging off, no live reload).
2. **Identical, bounded resources.** App containers get the same `--cpus` and `--memory`, otherwise comparisons are meaningless and "pool exhaustion" cannot be reproduced.
3. **Two viewpoints.** Gatling shows what the *client* experienced (latency percentiles, errors). Prometheus/Grafana show what happened *inside* (heap, GC, pool, DB). You need both to explain a result.
4. **Disposable infrastructure.** Everything is plain Podman containers on one network, created and removed by one script. No compose or other tooling is required.
5. **Parameters, not copies.** Scenarios are tuned with `-Dname=value`, the environment with env variables.

---

## 2. Architecture

```
 host                                   podman network "loadtest"
┌──────────────────────┐   HTTP :18080   ┌──────────┐  JDBC   ┌─────────────┐
│ Gatling (Maven fork) │ ──────────────▶ │  lt-app  │ ──────▶ │ lt-postgres │
│ + podman stats loop  │                 │ jvm|native│        └──────┬──────┘
└──────────────────────┘                 └────┬─────┘               │
                                              │ /q/metrics          │ SQL stats
                                   scrape 2s  ▼                     ▼
                                        ┌─────────────┐     ┌───────────────┐
                                        │ lt-prometheus│◀────│ lt-pgexporter │
                                        └──────┬──────┘     └───────────────┘
                                               ▼
                                        ┌────────────┐
                                        │ lt-grafana │  http://localhost:13000
                                        └────────────┘
```

| Container | Image | Host port | Role |
| :--- | :--- | :--- | :--- |
| `lt-app` | `loadtest-app-jvm` or `loadtest-app-native` (built locally) | 18080 | System under test. Only one runs at a time, always with this name, so Prometheus has one stable target |
| `lt-postgres` | `postgres:16-alpine` | 15432 | Dedicated DB (separate from your dev `postgres-quarkus`), `pg_stat_statements` preloaded |
| `lt-pgexporter` | `postgres-exporter` | none | Exposes `pg_stat_*` as Prometheus metrics |
| `lt-prometheus` | `prom/prometheus` | 19090 | Scrapes `lt-app` and `lt-pgexporter` every 2 s |
| `lt-grafana` | `grafana/grafana` | 13000 | Anonymous admin, provisioned datasource and dashboard |

Ports are deliberately different from the dev defaults (8080, 5432) so the load-test stack can run next to your devcontainer.

Gatling itself runs on the host through the Maven plugin. This is simple, but it shares CPU with the containers; see [Limitations](#9-limitations-and-pitfalls).

---

## 3. Directory layout

```
load-test/
├── pom.xml                         # Maven module: Gatling dependency + gatling-maven-plugin
├── loadtest.sh                     # Orchestration (build/infra/app/run/info/down)
├── README.md                       # Quick start + findings
├── DEVELOPER_GUIDE.md              # This file
├── infra/
│   ├── Containerfile.jvm           # ubi9 openjdk-21-runtime + fast-jar
│   ├── Containerfile.native        # ubi9 quarkus micro image + native runner
│   ├── prometheus.yml              # Scrape config
│   └── grafana/
│       ├── provisioning/datasources/prometheus.yml
│       ├── provisioning/dashboards/dashboards.yml
│       └── dashboards/quarkus-load.json
└── src/test/java/cz/rychter/loadtest/
    ├── TradeSupport.java           # Protocol, payloads, reusable request chains
    └── *Simulation.java            # One class per scenario
```

The module is registered in the root `pom.xml`. The Gatling plugin binds no goal to a lifecycle phase, so `./mvnw verify` at the root does **not** run load tests; they only run via `gatling:test` (what `loadtest.sh run` invokes).

---

## 4. Service-side configuration (`quarkus-rest-ms`)

What the service needs to be observable and tunable:

| Setting | Where | Why |
| :--- | :--- | :--- |
| `quarkus-micrometer-registry-prometheus` dependency | `quarkus-rest-ms/pom.xml` | Exposes `/q/metrics`: JVM memory/GC/threads, `http_server_requests_*`, process CPU |
| `quarkus.datasource.metrics.enabled=true` | `application.properties` | Adds `agroal_*` pool metrics (active, available, awaiting, max used, blocking time, leaks) |
| `%prod.quarkus.hibernate-orm.log.sql=false` | `application.properties` | SQL logging dominates latency under load |
| `QUARKUS_DATASOURCE_JDBC_MAX_SIZE` / `_MIN_SIZE` / `_INITIAL_SIZE` | env vars set by `loadtest.sh` | Override pool sizing per run without rebuilding. Quarkus maps env vars to properties automatically (`quarkus.datasource.jdbc.max-size` -> `QUARKUS_DATASOURCE_JDBC_MAX_SIZE`) |
| `DB_HOST=lt-postgres` | env var | The datasource URL already uses `${DB_HOST:...}` |

Important constraint: Agroal fails at startup if `min-size` or `initial-size` is greater than `max-size`. `loadtest.sh` therefore sets all three; when you lower `POOL_MAX` also lower `POOL_MIN`.

---

## 5. How `loadtest.sh` works

Commands: `build [jvm|native|all]`, `infra`, `app jvm|native`, `reset-db`, `run <Simulation> [-D...]`, `info`, `down`.

### `build`
- JVM: `./mvnw package` produces `quarkus-rest-ms/target/quarkus-app/` (fast-jar); `podman build -f infra/Containerfile.jvm` copies it into a UBI OpenJDK runtime image.
- Native: `./mvnw package -Dnative -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman`. The native compiler (Mandrel) runs *inside* a container, so no local GraalVM is needed. The `*-runner` binary is copied into a micro image.
- The native binary must run on a libc at least as new as the builder's. That is why `Containerfile.native` uses `ubi9-quarkus-micro-image` (an older micro image fails with `GLIBC_2.34 not found`).
- Build context is `quarkus-rest-ms/`, and the Containerfiles live in `load-test/infra/` (`-f` points to them).

### `infra`
Creates the `loadtest` network (if missing) and (re)starts Postgres, the exporter, Prometheus and Grafana. Config files are bind-mounted read-only (`:ro,z`; `z` sets a shared SELinux label and is harmless elsewhere). Data is ephemeral: re-running `infra` gives an empty DB and empty metrics history.

### `app jvm|native`
Removes any existing `lt-app`, starts the chosen image with:
- `--cpus $APP_CPUS --memory $APP_MEM`, the same for both modes,
- the pool and DB env vars from section 4,
- `APP_OPTS` as extra runtime flags (`JAVA_OPTS_APPEND` for JVM; program arguments for native, where `-Xmx...` is accepted),
- then waits for `/q/metrics` and prints the startup time from the log line `started in X s`.
It also writes the mode into `load-test/target/.mode`, so results are labelled correctly.

### `run <Name>`
1. Starts a background loop that appends `time,cpu,mem` from `podman stats --no-stream` every ~2 s to `load-test/target/runs/<timestamp>-<mode>-<Name>-container-stats.csv`. This is the RSS/CPU view of the *container* (what Kubernetes would bill and OOM-kill on), which the in-process JVM metrics do not give you.
2. Runs `./mvnw -pl load-test gatling:test -Dgatling.simulationClass=cz.rychter.loadtest.<Name>Simulation -DbaseUrl=http://localhost:$APP_PORT <your -D args>`. The plugin forks a JVM and propagates `-D` properties, which is how `-Drate=...` reaches the simulation code.
3. Stops the sampler and prints the paths of the CSV and the Gatling HTML report (`load-test/target/gatling/<sim>-<timestamp>/index.html`).
A failing Gatling *assertion* makes Maven report BUILD FAILURE, but the script still prints the report paths (`|| true`).

### Environment variables

| Variable | Default | Meaning |
| :--- | :--- | :--- |
| `APP_CPUS` | 4 | CPU limit of `lt-app` |
| `APP_MEM` | 768m | Memory limit of `lt-app` |
| `POOL_MAX`, `POOL_MIN` | 20, 5 | Agroal pool bounds (initial size = min) |
| `APP_OPTS` | empty | Extra JVM/native flags |
| `APP_PORT` | 18080 | Host port for the app |

---

## 6. How the Gatling simulations work

### Core ideas
- A **scenario** is a chain of requests executed by a *virtual user*. A **simulation** injects users into scenarios and sets assertions.
- **Open model** (`injectOpen`): you control the *arrival rate* of new users; responses may be slow, but arrivals continue. This models independent clients on the internet and shows overload honestly (latency and error growth).
- **Closed model** (`injectClosed`): you control the *number of concurrent users*; a new user starts only when another finishes. This models a bounded client population and is the right tool to push on a bounded resource like a connection pool.

### `TradeSupport` (shared code)
- `BASE_URL`: from `-DbaseUrl`, default `http://localhost:18080`.
- `protocol()`: JSON headers, `disableCaching()`.
- `payload(transactionId)`: builds a *valid* `TradeReportRequest` JSON. It must satisfy the service's Bean Validation rules (uppercase `USD`, `XNAS`, `AAPL`, a 20-char LEI, valid ISIN). If you change the DTO validation, update this, otherwise every request returns 400 and the smoke test will tell you.
- `newTransactionId()`: `LT-<runId>-<counter>`. The run id is derived from the start time, so IDs are unique across runs; the unique constraint on `transaction_id` is therefore only hit when a scenario wants it (`DuplicateRace`).
- `reportAndRead()`: `POST /api/v1/trades` (expects 201, saves `id` and `transactionId` from the JSON response), `exitHereIfFailed()` (skip the reads if the write failed), then `GET /{id}` and `GET /tx/{uti}` (expect 200).
- `browse()`: `GET /api/v1/trades?limit=20&page=<0..49>`.
- `intProp(name, default)`: reads `-D` parameters.

Each request has a **name** (`POST /trades`, `GET /trades/{id}`...). Gatling aggregates by name, so keep names stable and free of IDs, otherwise reports explode into thousands of rows.

### Simulations

| Class | Injection | Purpose |
| :--- | :--- | :--- |
| `SmokeSimulation` | 5 users at once; asserts 0 failures | Validate scripts, payloads, environment |
| `LoadSimulation` | ingest at `rate` users/s plus browse at `rate/2`, after a ramp-up; asserts <1% failures and p95 <1 s | Baseline |
| `StressSimulation` | ramp from 10 to `maxRate` users/s, then hold | Find the saturation knee |
| `SpikeSimulation` | calm, 5 s ramp to `spikeRate`, hold, back to calm | Absorption and recovery |
| `SoakSimulation` | constant `rate` for `durationMin` | Leak/GC drift detection |
| `PoolExhaustionSimulation` | closed model: ramp to `users` concurrent, hold, each user loops without think time | Bounded-resource queueing |
| `DuplicateRaceSimulation` | `users` users, each repeats `rounds` times POSTing the *same* UTI per round; accepts 201 and 409 only | Correctness under concurrency: any 500 is a defect |

Note that one "user" in `reportAndRead` produces three requests, so requests/s is roughly 3x the user rate (plus browse).

### Reading Gatling output
- A request is **KO** when a check fails (wrong status) *or* the connection fails or times out. The error table lists the reason, e.g. `status.find.is(201), but actually found 500`.
- Compare `50th`/`95th`/`99th` percentiles, not means.
- Remember warm-up: a JVM's first seconds include JIT compilation; use `warmupSec` and compare steady-state sections, or run longer.

---

## 7. Observability stack

### Prometheus
`infra/prometheus.yml` scrapes `lt-app:8080/q/metrics` and `lt-pgexporter:9187` every 2 s (default 15 s hides short spikes). Targets are addressed by **container name** over the podman network, which is why the app container is always called `lt-app`.

### Grafana
Provisioning is file-based, so nothing is configured by hand:
- `provisioning/datasources/prometheus.yml` defines the datasource with a fixed `uid: prometheus`.
- `provisioning/dashboards/dashboards.yml` tells Grafana to load every JSON in `/var/lib/grafana/dashboards`.
- `dashboards/quarkus-load.json` is the dashboard (UID `quarkus-load`). Panel queries reference the datasource by that uid.

Panels and their key metrics:

| Panel | Metrics |
| :--- | :--- |
| HTTP throughput by status | `http_server_requests_seconds_count` |
| Mean latency per endpoint, max | `..._seconds_sum / ..._count`, `..._seconds_max` |
| Agroal pool | `agroal_active_count`, `agroal_available_count`, `agroal_awaiting_count`, `agroal_max_used_count` |
| Agroal blocking and leaks | `agroal_blocking_time_*_milliseconds`, `agroal_leak_detection_count_total` |
| Heap used/committed/max | `jvm_memory_{used,committed,max}_bytes` |
| GC pauses | `jvm_gc_pause_seconds_{sum,count}` |
| CPU | `process_cpu_usage`, `system_cpu_usage` |
| Threads | `jvm_threads_live_threads`, `jvm_threads_states_threads` |
| PostgreSQL connections by state | `pg_stat_activity_count`, `pg_settings_max_connections` |
| PostgreSQL commits and inserts | `pg_stat_database_*` |

The dashboard has no percentile panels because the HTTP percentile histogram is not enabled; Gatling is the source of truth for percentiles.

### Diagnostic queries worth knowing
- Is the DB or the app the bottleneck? `select state, wait_event_type, count(*) from pg_stat_activity group by 1,2;` many `idle in transaction` plus an idle DB means the *application* holds connections while waiting on something else (CPU, locks, downstream calls). This is how the CPU-starvation finding was diagnosed.
- Slowest statements: `CREATE EXTENSION pg_stat_statements;` once, then query `pg_stat_statements` ordered by `total_exec_time`. Execution time excludes commit/WAL flush.
- Virtual-thread pinning: run the app with `APP_OPTS=-Djdk.tracePinnedThreads=short` and grep the log (no pinning was observed with the current driver).

### Adding a metric panel
1. Find the metric: `curl -s localhost:18080/q/metrics | grep <name>`.
2. Test the PromQL in Prometheus (`:19090`).
3. Edit the panel list in `quarkus-load.json` (or edit in Grafana, "Export as JSON", and replace the file), then `podman restart lt-grafana`.

---

## 8. Extending the module

### Add a scenario
1. Create `XyzSimulation extends Simulation` in `src/test/java/cz/rychter/loadtest/`.
2. Build chains from `TradeSupport` or add new chains there. Inject with `injectOpen` or `injectClosed`.
3. Read tunables via `TradeSupport.intProp("name", default)`.
4. Run: `./load-test/loadtest.sh run Xyz` (name without the `Simulation` suffix).
5. Document it in the scenario table in `README.md`.

Skeleton:

```java
public class ReadHeavySimulation extends Simulation {
    {
        int rate = TradeSupport.intProp("rate", 200);
        var reads = scenario("reads").exec(TradeSupport.browse());
        setUp(reads.injectOpen(constantUsersPerSec(rate).during(Duration.ofSeconds(60))))
                .protocols(TradeSupport.protocol())
                .assertions(global().responseTime().percentile(95.0).lt(300));
    }
}
```

### Add an endpoint to test
Add a chain to `TradeSupport`, give each request a stable name, and use `check(status().is(...))` plus `jsonPath(...).saveAs(...)` to carry values between requests. Prefer realistic data (unique IDs, varied sizes), because identical payloads and cached rows flatter the results.

### Seed data
Reads on an empty table are optimistic. Either run `Load` first, or add a `before { ... }` hook / a separate scenario that inserts N rows, then run the read scenarios. Use `./load-test/loadtest.sh reset-db` to truncate between comparisons.

### Add assertions (CI gating)
Assertions turn a run into pass/fail (`global().failedRequests().percent().lt(1.0)`, `forAll().responseTime().max().lt(...)`, per-request `details("POST /trades").responseTime().percentile(95.0).lt(...)`). `Smoke` and `Load` carry assertions; `Stress`/`Spike`/`PoolExhaustion` intentionally do not, since they are exploratory. For CI, run `Smoke` on every PR and `Load` nightly on stable hardware; absolute numbers from shared runners are noisy, so compare against a baseline rather than a hard-coded value.

### Compare configurations
Everything that matters is a parameter: run the same simulation with different `APP_CPUS`, `APP_MEM`, `POOL_MAX`, `APP_OPTS` (heap size, GC), or `jvm` vs `native`. Change **one thing per run** and keep the simulation parameters identical. Record the Gatling report and the CSV for each run (they are timestamped under `load-test/target/`, which is git-ignored; copy out what you want to keep).

### Add another service
Add another container to `app()` in `loadtest.sh` or generalise it with a `SERVICE` variable, add its scrape target to `prometheus.yml`, and add panels. Keep the single stable container name per target.

---

## 9. Limitations and pitfalls

- **Gatling and the app share the host.** CPU contention can distort results. For serious numbers run Gatling on a separate machine (pass `-DbaseUrl=http://<host>:18080`, and publish the port on a reachable interface).
- **Laptop noise.** Thermal throttling, other containers and background load matter. Repeat runs and compare ratios, not absolute numbers.
- **Warm-up.** The JVM needs time (JIT); a 30 s test mostly measures warm-up. Use `Soak` or longer durations for steady state. Native has no warm-up but a lower peak optimisation ceiling.
- **Container limits.** `--memory` is a hard limit (OOM kill), while the JVM sizes its heap from it (`MaxRAMPercentage=80` in the UBI image). JVM flags also depend on the visible CPU count: with few CPUs the JVM may pick different GCs and thread counts.
- **Postgres is not tuned or limited.** It is a convenient baseline; use realistic resources and `synchronous_commit` settings if you want DB-bound results. Commit (fsync) latency on a container overlay disk can dominate write tests.
- **Memory attribution.** `podman stats` memory includes page cache attributable to the container; use the JVM's own metrics for heap and the CSV for total footprint.
- **Open-model overload.** Under overload Gatling keeps creating users, so memory use on the load generator grows; stop and lower `maxRate` if the generator itself struggles.
- **Pool exhaustion needs long-held connections.** Short transactions drain a small pool quickly. To demonstrate exhaustion, combine a small pool with CPU starvation or slow queries.
- **Native build.** Needs lots of RAM (~12 GB) and minutes; reflection-heavy code may need extra configuration. The `native` profile in `quarkus-rest-ms/pom.xml` activates with `-Dnative`.

---

## 10. Replicating this approach in another project

The approach is generic; only a few pieces are project-specific.

**Reusable as-is**
- `loadtest.sh` structure (`infra`, `app`, `run`, `down`), the Prometheus/Grafana provisioning files, and the Containerfile patterns.
- The generic panels: HTTP, JVM, Agroal, Postgres.

**Steps**
1. **Expose metrics.** Add `quarkus-micrometer-registry-prometheus` and enable datasource metrics (section 4). For non-Quarkus Java apps use Micrometer or the Prometheus JMX exporter; for other stacks use their native Prometheus client.
2. **Make config overridable by environment.** Pool sizes and DB host must be settable via env vars so runs don't need rebuilds.
3. **Containerise** with a JVM Containerfile and (optionally) a native one; keep identical resource limits.
4. **Copy `load-test/`** into the new repo (or a sibling repo), adjust the module's `<parent>` and package name, update `container names/ports` if they collide, and set `-DbaseUrl`.
5. **Write payloads and chains** for the new API in a `*Support` class; start with `Smoke`, which must pass before anything else.
6. **Choose scenarios by question:** capacity (`Load`/`Stress`), resilience (`Spike`, `PoolExhaustion`), stability (`Soak`), correctness (`DuplicateRace`-style concurrency tests on any invariant guarded by a check-then-act pattern).
7. **Adapt the dashboard** to the app's own business metrics (custom Micrometer counters/timers are the best addition: e.g. `@Timed` on service methods, counters for 409 conflicts).
8. **Run a baseline first**, record it, and compare every later change against it.

**Using another load tool.** k6 or JMeter can replace Gatling: only the `run()` function in `loadtest.sh` and `src/test/...` change. Everything else (containers, metrics, dashboards) is tool-independent. k6 is a single binary with a small footprint; Gatling's advantage is staying in the Java toolchain with type-safe code and detailed HTML reports.

**Using Kubernetes instead of Podman.** The container limits become `resources.requests/limits`, Prometheus scraping uses a `ServiceMonitor`/annotations, and the load generator runs as a Job in the cluster (or externally). Keep the same discipline: fixed limits, one change per run, both client- and server-side views.

---

## 11. Troubleshooting

| Symptom | Likely cause | Fix |
| :--- | :--- | :--- |
| Smoke: all requests 400 | Payload no longer satisfies validation | Check the violations with `curl`, update `TradeSupport.payload` |
| `Timed out waiting for .../q/metrics` | App crashed at start | `podman logs lt-app` (e.g. pool `min-size` > `max-size`, or the DB not reachable) |
| Native container: `GLIBC_x not found` | Runtime image older than the builder | Use `ubi9-quarkus-micro-image` as in `Containerfile.native` |
| Prometheus target `postgres` down right after `infra` | Transient DNS/startup | Wait a few seconds; check `pg_up` |
| 500s with `acquisition timeout` | Pool exhausted (often due to CPU starvation or slow queries) | Look at the Agroal and CPU panels; raise `APP_CPUS`/`POOL_MAX` or fix the slow path |
| Grafana shows no data | App container not running or restarted | `podman ps`; check `localhost:19090/targets` |
| `-D` parameter seems ignored | Misspelled name (silently falls back to default) | Compare with the `intProp` name in the simulation |
| Port already in use | Another stack on 18080/15432/19090/13000 | `./load-test/loadtest.sh down` or set `APP_PORT` |
