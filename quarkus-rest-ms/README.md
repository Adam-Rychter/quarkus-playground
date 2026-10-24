# Quarkus REST Microservice (`quarkus-rest-ms`)

A production-grade REST microservice built with **Quarkus 3.40.x**, **Java 21 Virtual Threads (Project Loom)**, **Hibernate ORM with Panache (Repository Pattern)**, and **PostgreSQL 16**.

---

## 1. Architecture & Design

### Technology Stack
- **Language & Runtime:** Java 21 (OpenJDK 21)
- **Framework:** Quarkus 3.40.1.1
- **REST Engine:** RESTEasy Reactive / Quarkus REST with Jackson
- **Concurrency:** Java 21 Virtual Threads via `@RunOnVirtualThread`
- **Persistence:** Hibernate ORM with Panache using `PanacheRepository<TimeEntry>`
- **Connection Pooling:** Agroal
- **Transaction Demarcation:** Narayana JTA (`jakarta.transaction.Transactional`)
- **Database:** PostgreSQL 16 (Containerized via Podman)

### Code Structure
- [`GreetingResource.java`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/main/java/cz/rychter/GreetingResource.java): Simple health/greeting endpoint (`GET /hello`).
- [`TimeEntry.java`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/main/java/cz/rychter/TimeEntry.java): JPA Entity representing a database-persisted timestamp with a synthetic auto-increment identity ID.
- [`TimeRepository.java`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/main/java/cz/rychter/TimeRepository.java): Data access layer implementing `PanacheRepository<TimeEntry>`, keeping the domain entity clean and enterprise-standard.
- [`TimeResponse.java`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/main/java/cz/rychter/TimeResponse.java): Immutable Java `record` DTO preventing direct entity leakage over the HTTP API.
- [`TimeResource.java`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/main/java/cz/rychter/TimeResource.java): Endpoint (`GET /time`) executing on Java 21 Virtual Threads with automated JTA transaction management.

---

## 2. Multi-Threading & Concurrency Architecture

### The Dual-Layer Execution Model

```
[Incoming HTTP Requests]
           │
           ▼
┌──────────────────────────────────────────────┐
│  Vert.x Event Loop Threads (IO Layer)        │  (Non-blocking TCP socket intake)
└──────────────────────┬───────────────────────┘
                       │ Handed off via @RunOnVirtualThread
                       ▼
┌──────────────────────────────────────────────┐
│  Java 21 Virtual Threads (Carrier Pool)      │  (Near-zero memory & context-switch cost)
│  - Executes business logic & transactions    │
└──────────────────────┬───────────────────────┘
                       │ Leases connection (Gatekeeper)
                       ▼
┌──────────────────────────────────────────────┐
│  Agroal Connection Pool (max-size = 20)      │  (Serialized database access)
└──────────────────────┬───────────────────────┘
                       │ Physical JDBC Sockets
                       ▼
┌──────────────────────────────────────────────┐
│  PostgreSQL Database (port 5432)             │
└──────────────────────────────────────────────┘
```

1. **Non-blocking IO Intake:** Eclipse Vert.x event loops ingest HTTP network sockets and parse headers without blocking.
2. **Virtual Thread Dispatch:** Because [`TimeResource`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/main/java/cz/rychter/TimeResource.java) is annotated with `@RunOnVirtualThread`, Quarkus spawns a lightweight Java 21 virtual thread for each request.
3. **Agroal Pool as Gatekeeper:** Virtual threads do not hammer the database directly. They pass through Agroal's bounded connection pool (`max-size = 20`).

### What Happens When 100 Concurrent Requests Arrive?

1. **Intake:** Vert.x event loops accept all 100 requests instantaneously.
2. **Virtual Threads Spawned:** 100 virtual threads are allocated. Unlike OS threads, virtual threads cost only a few hundred bytes of heap each.
3. **Connection Leasing:**
   - **First 20 threads:** Immediately acquire the 20 available JDBC connections from Agroal.
   - **Remaining 80 threads:** Placed in Agroal's thread-safe FIFO waiting queue, bounded by `acquisition-timeout = 5s`. The underlying OS carrier threads are unmounted and remain free to do other work while the virtual threads park.
4. **Fast Commit & Release:** Each insert takes $< 1\text{ ms}$ in PostgreSQL. As each `@Transactional` method exits, Narayana flushes, commits, releases database locks, and returns the connection to Agroal.
5. **Queue Draining:** Agroal dispenses the returned connections to the waiting virtual threads. All 100 requests complete within 20–50 ms total, with zero thread starvation.

---

## 3. Database Memory Architecture: Oracle PGA vs PostgreSQL

In corporate environments migrating from or comparing with Oracle Database, memory exhaustion under load is a frequent operational concern.

### Architectural Comparison: Oracle vs PostgreSQL

| Architectural Component | Oracle Database | PostgreSQL Equivalent |
| :--- | :--- | :--- |
| **Shared Cache / Buffers** | **SGA** (`shared_pool`, `db_cache_size`) | **`shared_buffers`** |
| **Private Backend Memory** | **PGA** (`pga_aggregate_target`) | **Backend Process Private Memory** (`work_mem`, `temp_buffers`, stack, catalog caches) |
| **Process Model** | Dedicated server process per connection | Process per connection (`postgres: user db [client]`) |
| **Failure Mode Under Memory Pressure** | ORA-04030 / ORA-04036 (PGA limit exceeded) | **Linux OOM Killer** forcefully kills `postgres` backend processes |

### Why Private Memory Exhaustion Occurs in PostgreSQL

#### 1. Connection Bloat
PostgreSQL allocates a dedicated operating system process for each connection. Each connection consumes a baseline of **5 MB – 15 MB** of RAM simply being idle (holding catalog caches `catcache`, schema metadata `relcache`, prepared statement caches, and memory contexts).
> **Corporate Risk:** Opening 1,000 unmanaged microservice connections will consume **5 GB – 15 GB of RAM** before executing any SQL queries.

#### 2. The `work_mem` Multiplier Trap
In Oracle, the PGA manages sorting and hash table areas. In PostgreSQL, this is governed by **`work_mem`**.
> [!WARNING]
> In PostgreSQL, `work_mem` is **NOT** allocated once per connection or once per query. It is allocated **per operation node in the query execution plan**.

If `work_mem = 64MB`, a query with:
- 3 `ORDER BY` sorts
- 2 `HASH JOIN` operations
- 1 `BITMAP INDEX SCAN`

Can allocate:
$$\text{Single Query Memory} = 6 \times 64\text{ MB} = \mathbf{384\text{ MB}}$$

If 20 concurrent requests execute that query simultaneously:
$$\text{Total Memory} = 20 \times 384\text{ MB} = \mathbf{7.68\text{ GB}}$$

If this exceeds physical RAM, the Linux kernel's **OOM Killer** will terminate PostgreSQL, forcing the entire database server into crash recovery.

---

## 4. Mitigation Strategies for Enterprise Production

### 1. Connection Pool Clamping (Agroal in Quarkus)
Never allow application pods to create unbounded database connections. Sizing must be strictly managed in [`application.properties`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/main/resources/application.properties):

```properties
# Sizing
quarkus.datasource.jdbc.min-size=5
quarkus.datasource.jdbc.max-size=20
quarkus.datasource.jdbc.initial-size=5

# Fail-fast timeout to prevent thread pile-ups during pool starvation
quarkus.datasource.jdbc.acquisition-timeout=5s

# Proactive connection leak detection
quarkus.datasource.jdbc.leak-detection-interval=10s
```

**Recommended Sizing Formula:**
$$\text{Pool Size} = (\text{CPU Cores} \times 2) + \text{Effective Spindle Count}$$
Virtual threads allow the application layer to absorb thousands of concurrent client requests, while Agroal serializes database access through a lean, predictable number of connections.

### 2. Introduce PgBouncer for Multi-Pod Microservices
When scaling horizontally (e.g. 20 Kubernetes pods $\times$ 20 connections = 400 PostgreSQL backends), place **PgBouncer** in **Transaction Pooling** mode between Quarkus and PostgreSQL:
- Quarkus talks to PgBouncer.
- PgBouncer multiplexes thousands of client connections onto a small pool of 20–50 real PostgreSQL backend processes, capping private backend memory to a fixed ceiling.

### 3. Conservative Global `work_mem` + Scoped Dynamic Overrides
Keep global `work_mem` conservative (e.g. `4MB` to `16MB`) in `postgresql.conf` so standard OLTP queries never risk OOM. For large analytical queries or batch jobs, override `work_mem` dynamically **for that specific transaction only**:

```java
// Dynamically grant sort memory only for the duration of the current transaction
entityManager.createNativeQuery("SET LOCAL work_mem = '128MB'").executeUpdate();
```
Because `SET LOCAL` is scoped strictly to the active transaction, PostgreSQL automatically frees the allocated memory as soon as the transaction commits.

### 4. Statement & Session Timeouts
Prevent runaway queries or stuck sessions from holding memory contexts indefinitely:
```ini
# postgresql.conf
statement_timeout = 15000                   # Abort any query exceeding 15 seconds
idle_in_transaction_session_timeout = 10000  # Kill sessions holding open transactions > 10s
```

### 5. Inspecting Backend Memory Contexts (PostgreSQL 14+)
Monitor real-time private memory consumption per backend process (analogous to `V$PROCESS_MEMORY` in Oracle):
```sql
SELECT 
    pid, 
    context, 
    pg_size_pretty(total_bytes) AS total_memory, 
    pg_size_pretty(free_bytes) AS free_memory
FROM pg_backend_memory_contexts
ORDER BY total_bytes DESC
LIMIT 10;
```

---

## 5. Running the Application

### Development Mode (with Live Reload)
```bash
./mvnw quarkus:dev
```
- REST endpoint: [http://localhost:8080/time](http://localhost:8080/time)
- Dev UI: [http://localhost:8080/q/dev-ui](http://localhost:8080/q/dev-ui)

### Production Mode (Fast-JAR Layout)
```bash
./mvnw clean package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar
```
