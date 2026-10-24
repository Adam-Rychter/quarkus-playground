# Quarkus REST Microservice (`quarkus-rest-ms`)

A production-grade REST microservice built with **Quarkus 3.40.x**, **Java 21 Virtual Threads (Project Loom)**, **Hibernate ORM with Panache (Repository Pattern)**, **Jakarta Bean Validation**, and **PostgreSQL 16**.

---\n
## 1. Architecture & Design

### Technology Stack
- **Language & Runtime:** Java 21 (OpenJDK 21)
- **Framework:** Quarkus 3.40.1.1
- **REST Engine:** RESTEasy Reactive / Quarkus REST with Jackson
- **Validation:** Hibernate Validator (`quarkus-hibernate-validator`)
- **Concurrency:** Java 21 Virtual Threads via `@RunOnVirtualThread`
- **Persistence:** Hibernate ORM with Panache (`PanacheRepository<Entity>`)
- **Connection Pooling:** Agroal
- **Transaction Demarcation:** Narayana JTA (`jakarta.transaction.Transactional`)
- **Testing:** JUnit 5, Mockito (`quarkus-junit-mockito`), REST-Assured, Maven Surefire & Failsafe
- **Database:** PostgreSQL 16 (Containerized via Podman)

### Code Structure
- **Greeting & System Endpoints:**
  - [`GreetingResource.java`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/main/java/cz/rychter/GreetingResource.java): Simple health/greeting endpoint (`GET /hello`).
  - [`TimeEntry.java`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/main/java/cz/rychter/TimeEntry.java): JPA Entity for timestamp persistence.
  - [`TimeRepository.java`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/main/java/cz/rychter/TimeRepository.java): Panache repository for `TimeEntry`.
  - [`TimeResource.java`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/main/java/cz/rychter/TimeResource.java): Endpoint (`GET /time`) executing on virtual threads.
- **ISO 20022 Stock Trade Reporting (`cz.rychter.trade`):**
  - [`TradeTransaction.java`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/main/java/cz/rychter/trade/model/TradeTransaction.java): JPA Entity representing MiFIR / ISO 20022 trade execution reports with database indexes on `isin`, `ticker`, and `trade_timestamp`, plus a unique constraint on `transaction_id`.
  - [`TradeReportRequest.java`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/main/java/cz/rychter/trade/dto/TradeReportRequest.java): Ingestion record DTO enforcing Bean Validation constraints (ISIN format, 20-char LEI, 4-char MIC, positive volume and price).
  - [`TradeReportResponse.java`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/main/java/cz/rychter/trade/dto/TradeReportResponse.java): Immutable response record with total gross amount and audit timestamp.
  - [`TradeTransactionRepository.java`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/main/java/cz/rychter/trade/repository/TradeTransactionRepository.java): Panache repository supporting queries by UTI.
  - [`TradeTransactionResource.java`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/main/java/cz/rychter/trade/resource/TradeTransactionResource.java): REST resource (`/api/v1/trades`) executing on Java 21 Virtual Threads with constructor injection.

---

## 2. Testing Architecture (Enterprise Standards)

The project cleanly separates Unit Tests and Integration Tests across plugins, phases, and dependencies:

| Category | File Pattern | Runner Plugin | Maven Phase | Characteristics |
| :--- | :--- | :--- | :--- | :--- |
| **Unit Tests** | `*Test.java` | `maven-surefire-plugin` | `mvn test` | Pure JUnit 5 + Mockito. **Zero database dependency**, runs in $< 1\text{s}$. |
| **Integration Tests** | `*IT.java` | `maven-failsafe-plugin` | `mvn verify` | `@QuarkusTest` / `@QuarkusIntegrationTest` against real PostgreSQL instance. |

### Test Files
- [`GreetingResourceTest.java`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/test/java/cz/rychter/GreetingResourceTest.java): Pure unit test testing string response in memory.
- [`GreetingResourceIT.java`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/test/java/cz/rychter/GreetingResourceIT.java): Black-box integration test verifying the packaged fast-JAR HTTP output.
- [`TradeTransactionResourceTest.java`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/test/java/cz/rychter/trade/TradeTransactionResourceTest.java): Mockito unit test testing business calculation, uppercase normalization, duplicate conflict handling, and error codes without starting Quarkus or PostgreSQL.
- [`TradeTransactionResourceIT.java`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/src/test/java/cz/rychter/trade/TradeTransactionResourceIT.java): Integration test against PostgreSQL verifying SQL tables, schema constraints, Bean Validation errors, and transactions.

---

## 3. Stock Trade Transaction API Usage

### Ingest Trade Report
```bash
curl -X POST http://localhost:8080/api/v1/trades \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "UTI-20261024-AAPL-982138",
    "isin": "US0378331005",
    "ticker": "AAPL",
    "side": "BUY",
    "quantity": 150.0000,
    "price": 178.5000,
    "currency": "USD",
    "tradeTimestamp": "2026-10-24T14:30:00Z",
    "brokerLei": "5493006MHB84DD0ZWV18",
    "clientId": "CLIENT-INST-9921",
    "venueMic": "XNAS",
    "status": "NEW"
  }'
```

**Response (`201 Created`):**
```json
{
  "id": 1,
  "transactionId": "UTI-20261024-AAPL-982138",
  "isin": "US0378331005",
  "ticker": "AAPL",
  "side": "BUY",
  "quantity": 150.0000,
  "price": 178.5000,
  "currency": "USD",
  "grossAmount": 26775.0000,
  "tradeTimestamp": "2026-10-24T14:30:00Z",
  "brokerLei": "5493006MHB84DD0ZWV18",
  "clientId": "CLIENT-INST-9921",
  "venueMic": "XNAS",
  "status": "NEW",
  "createdAt": "2026-10-24T22:28:44.123456Z"
}
```

### Query Endpoints
- **Get by synthetic ID:** `GET /api/v1/trades/1`
- **Get by Unique Trade Identifier (UTI):** `GET /api/v1/trades/tx/UTI-20261024-AAPL-982138`
- **List recent trades (paginated):** `GET /api/v1/trades?page=0&limit=20`

---

## 4. Multi-Threading & Concurrency Architecture

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
2. **Virtual Thread Dispatch:** Because resources are annotated with `@RunOnVirtualThread`, Quarkus spawns a lightweight Java 21 virtual thread for each request.
3. **Agroal Pool as Gatekeeper:** Virtual threads do not hammer the database directly. They pass through Agroal's bounded connection pool (`max-size = 20`).

---

## 5. Running the Application

### Fast Unit Tests
```bash
./mvnw test
```

### Full Verification & Integration Tests
```bash
./mvnw verify
```

### Development Mode (with Live Reload)
```bash
./mvnw quarkus:dev
```
- REST endpoint: [http://localhost:8080/api/v1/trades](http://localhost:8080/api/v1/trades)
- Dev UI: [http://localhost:8080/q/dev-ui](http://localhost:8080/q/dev-ui)

### Production Mode (Fast-JAR Layout)
```bash
./mvnw clean package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar
```
