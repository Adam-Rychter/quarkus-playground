# AGENTS.md

Welcome to the **Quarkus Playground** repository. This document serves as instructions and architectural context for AI agents and human contributors working within this codebase.

---

## 1. Project Overview & Architecture

- **Project Type:** Multi-module Maven project.
  - Root: [`pom.xml`](file:///home/adam/IdeaProjects/quarkus-playground/pom.xml) manages common properties, compiler configurations, and child modules.
  - Load-test Module: [`load-test/`](file:///home/adam/IdeaProjects/quarkus-playground/load-test) (Gatling simulations + Podman-based Prometheus/Grafana stack; see its README).
  - Active Module: [`quarkus-rest-ms/`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms) (Quarkus REST microservice).
- **Runtime & Language:** Java 21 (LTS) targeting OpenJDK 21.
- **Framework:** Quarkus 3.40.x (Hibernate ORM with Panache, Narayana JTA, Agroal Connection Pool, Jackson, RESTEasy Reactive / Quarkus REST, Hibernate Validator).
- **Database:** PostgreSQL 16 running via Podman containerization.
- **Dev Container:** Standardized Java 21 devcontainer environment with Podman socket integration.

---

## 2. Architectural Guidelines & Best Practices

When adding or modifying code in this repository, adhere to the following principles:

### A. Persistence & Data Access
- **Repository Pattern:** Prefer `PanacheRepository<Entity>` over Active Record (`PanacheEntity`) to keep entities clean, decoupled from framework inheritance, and compliant with enterprise architectural standards.
- **Entities:** Use standard Jakarta Persistence (`@Entity`, `@Table`, `@Id`, `@GeneratedValue(strategy = GenerationType.IDENTITY)`, `@Column`, `@Index`).
- **DTOs / Records:** Expose immutable Java `record` classes with Jakarta Bean Validation constraints (`@NotBlank`, `@Pattern`, `@DecimalMin`, `@NotNull`) for request and response payloads; do not directly leak JPA entities over REST endpoints.
- **Constructor Injection:** Use constructor injection (`@Inject public MyResource(MyRepository repo)`) on resource classes to enable clean Mockito instantiation in unit tests without reflection or framework containers.

### B. Testing Architecture & Separation Standards
- **Unit Tests (`*Test.java`):**
  - Executed by `maven-surefire-plugin` during `mvn test`.
  - Must remain fast, isolated, and have **zero dependency on database or Quarkus runtime boot**.
  - Mock dependencies using Mockito (`@ExtendWith(MockitoExtension.class)`, `@Mock`, `@InjectMocks`).
- **Integration Tests (`*IT.java`):**
  - Executed by `maven-failsafe-plugin` during `mvn verify`.
  - Annotated with `@QuarkusTest` or `@QuarkusIntegrationTest`.
  - Connect to the real PostgreSQL database and verify HTTP deserialization, validation, SQL constraints, transactions, and persistence.

### C. Transaction & Connection Pool Invariants
- **Transaction Boundaries:** Always decorate mutating or data-fetching service/resource methods with `jakarta.transaction.Transactional`.
- **Automatic Demarcation:** Never manually open, commit, roll back, or close JDBC connections or `EntityManager` sessions. Narayana JTA automatically commits on successful completion, rolls back on uncaught runtime exceptions, releases database locks, and returns the physical connection to the Agroal pool.
- **Connection Leak Safety:** Agroal is configured with a 10s leak detection interval (`quarkus.datasource.jdbc.leak-detection-interval=10s`) and a 30s default transaction timeout. Keep database transactions concise.

### D. Configuration Rules
- **Modern Schema Management:** Do **not** use the deprecated `quarkus.hibernate-orm.database.generation`. Always use `quarkus.hibernate-orm.schema-management.strategy`.
- **Container Host Resolution:** Datasource URLs in dev mode use `${DB_HOST:host.containers.internal}` so that services resolve the PostgreSQL container from inside the devcontainer (`host.containers.internal:5432`) and host environments.
- **Dev Mode Network Binding:** Keep `quarkus.http.host=0.0.0.0` and allow dev-ui hosts via regex (`quarkus.dev-ui.hosts=.*`) to prevent HTTP 403 Forbidden errors when accessing Dev UI across container boundaries.

---

## 3. Directory Layout

```
.
├── .devcontainer/
│   ├── devcontainer.json        # IntelliJ / VS Code devcontainer definition
│   └── docker-compose.yml       # Multi-service compose (app + postgres)
├── mvnw, mvnw.cmd, .mvn/         # Maven wrapper files (root level)
├── pom.xml                      # Parent POM (declares modules)
├── load-test/                   # Gatling simulations, loadtest.sh, Containerfiles, Prometheus/Grafana config
└── quarkus-rest-ms/
    ├── pom.xml                  # Microservice dependencies & Quarkus plugin
    ├── src/main/java/cz/rychter/
    │   ├── GreetingResource.java # GET /hello (plain text endpoint)
    │   ├── TimeEntry.java        # JPA Entity for persisted timestamps
    │   ├── TimeRepository.java   # PanacheRepository<TimeEntry>
    │   ├── TimeResource.java     # GET /time (JSON endpoint with transactional persistence)
    │   ├── TimeResponse.java     # Response DTO record
    │   └── trade/                # ISO 20022 Stock Trade Transaction Reporting Domain
    │       ├── dto/
    │       │   ├── TradeReportRequest.java   # Ingestion DTO record with Bean Validation
    │       │   └── TradeReportResponse.java  # Response DTO record
    │       ├── model/
    │       │   ├── TradeSide.java            # BUY / SELL enum
    │       │   ├── TradeStatus.java          # NEW / CANCELLED / AMENDED enum
    │       │   └── TradeTransaction.java     # JPA Entity (table: trade_transactions)
    │       ├── repository/
    │       │   └── TradeTransactionRepository.java # PanacheRepository<TradeTransaction>
    │       └── resource/
    │           └── TradeTransactionResource.java   # REST Endpoint (/api/v1/trades)
    ├── src/main/resources/
    │   └── application.properties # Datasource, Agroal pool, schema & dev settings
    └── src/test/java/cz/rychter/
        ├── GreetingResourceTest.java             # Pure Unit Test (JUnit 5)
        ├── GreetingResourceIT.java               # Packaged Black-box IT (@QuarkusIntegrationTest)
        └── trade/
            ├── TradeTransactionResourceTest.java # Pure Unit Test (Mockito)
            └── TradeTransactionResourceIT.java   # Full-stack IT with PostgreSQL (@QuarkusTest)
```

---

## 4. Common Commands & Workflows

### A. Running in Dev Mode (Live Reload Enabled)
- **From project root:**
  ```bash
  ./mvnw quarkus:dev -pl quarkus-rest-ms
  ```
- **From inside `quarkus-rest-ms` directory:**
  ```bash
  ./mvnw quarkus:dev
  ```

### B. Running in Production / Fast-JAR Mode (Without Dev Reload)
```bash
./mvnw clean package -DskipTests
java -jar quarkus-rest-ms/target/quarkus-app/quarkus-run.jar
```

### C. Executing Tests
- **Run fast Unit Tests only (Surefire, no database required):**
  ```bash
  ./mvnw test -pl quarkus-rest-ms
  ```
- **Run full suite including Integration Tests against PostgreSQL (Failsafe):**
  ```bash
  ./mvnw verify -pl quarkus-rest-ms
  ```

### D. Load Testing (JVM vs Native)
```bash
./load-test/loadtest.sh build all && ./load-test/loadtest.sh infra
./load-test/loadtest.sh app jvm        # or native
./load-test/loadtest.sh run Load       # Smoke|Load|Stress|Spike|Soak|PoolExhaustion|DuplicateRace
```

### E. Managing Podman Infrastructure
- **Verify Podman Socket:**
  ```bash
  systemctl --user status podman.socket
  ```
- **Inspect PostgreSQL Container:**
  ```bash
  podman ps --filter "name=postgres-quarkus"
  ```
- **Connect to PostgreSQL Database via CLI:**
  ```bash
  podman exec -it postgres-quarkus psql -U quarkus -d quarkus_db
  ```

---

## 5. Endpoints Reference

| Method | Path | Description | Response Example |
| :--- | :--- | :--- | :--- |
| `GET` | `/hello` | Basic greeting check | `Hello from Quarkus REST` |
| `GET` | `/time` | Persists current timestamp to PostgreSQL and returns ID | `{"id":1,"timestamp":"2026-10-24T20:45:30Z"}` |
| `POST` | `/api/v1/trades` | Ingests ISO 20022 trade report, validates, persists to DB | `{"id":1,"transactionId":"UTI-123","grossAmount":26775.0,...}` (`201 Created`) |
| `GET` | `/api/v1/trades/{id}` | Fetches trade by synthetic database ID | Trade JSON (`200 OK`) |
| `GET` | `/api/v1/trades/tx/{transactionId}` | Fetches trade by Unique Trade Identifier (UTI) | Trade JSON (`200 OK`) |
| `GET` | `/api/v1/trades` | Lists recent trades (query params `page`, `limit`) | Array of Trade JSON (`200 OK`) |
| `GET` | `/q/dev-ui` | Quarkus Dev UI dashboard | HTML Application |
| `GET` | `/q/metrics` | Micrometer/Prometheus metrics (JVM, HTTP, Agroal) | Prometheus text |
| `GET` | `/q/health` | Application health status (when health extension enabled) | JSON |
