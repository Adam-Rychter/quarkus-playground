# Quarkus Playground

A multi-module enterprise playground exploring **Quarkus 3.40.x**, **Java 21 Virtual Threads (Project Loom)**, **Hibernate ORM with Panache**, and **PostgreSQL 16**.

---

## Modules

- **[`quarkus-rest-ms/`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms)**: Primary REST microservice implementing ISO 20022 / MiFIR stock trade transaction reporting, virtual thread concurrency, Agroal pool management, and enterprise-separated unit & integration tests.
- **[`load-test/`](file:///home/adam/IdeaProjects/quarkus-playground/load-test)**: Gatling load tests, Prometheus/Grafana dashboards and JVM vs native comparison tooling.

---

## Quick Start

### 1. Prerequisites
- Java 21 LTS
- Podman or Docker (for PostgreSQL container)

### 2. Run Tests
- **Fast Unit Tests (no database required):**
  ```bash
  ./mvnw test
  ```
- **Integration Tests (against PostgreSQL):**
  ```bash
  ./mvnw verify
  ```

### 3. Run in Development Mode (Live Reload)
```bash
./mvnw quarkus:dev -pl quarkus-rest-ms
```

---

## Documentation
- See **[`AGENTS.md`](file:///home/adam/IdeaProjects/quarkus-playground/AGENTS.md)** for architectural rules, coding standards, and agent guidelines.
- See **[`quarkus-rest-ms/README.md`](file:///home/adam/IdeaProjects/quarkus-playground/quarkus-rest-ms/README.md)** for in-depth concurrency architecture, Agroal pool sizing, and ISO 20022 trade reporting API usage.
