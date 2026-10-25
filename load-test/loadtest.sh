#!/usr/bin/env bash
# Load-test orchestration for quarkus-rest-ms (JVM vs native), using plain Podman.
# Run `./load-test/loadtest.sh help` for usage.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INFRA="$ROOT/load-test/infra"
OUT="$ROOT/load-test/target/runs"
NET=loadtest

# Resource limits applied identically to the JVM and native containers (fair comparison).
APP_CPUS="${APP_CPUS:-4}"
APP_MEM="${APP_MEM:-768m}"
# Agroal pool overrides (min/initial must not exceed max).
POOL_MAX="${POOL_MAX:-20}"
POOL_MIN="${POOL_MIN:-5}"
# Extra runtime flags, e.g. APP_OPTS="-Xmx256m" (JVM and native both accept -Xmx).
APP_OPTS="${APP_OPTS:-}"
APP_PORT="${APP_PORT:-18080}"

usage() {
  cat <<EOF
Usage: $0 <command>

  build [jvm|native|all]   Package the service and build container image(s) (default: all)
  infra                    Start Postgres, postgres-exporter, Prometheus, Grafana
  app jvm|native           (Re)start the service container 'lt-app' (one at a time, port $APP_PORT)
  reset-db                 Truncate trade_transactions
  run <Simulation> [-Dk=v] Run a Gatling simulation (Smoke|Load|Stress|Spike|Soak|PoolExhaustion|DuplicateRace)
  info                     Image sizes, startup time, current container memory
  down                     Remove all load-test containers and the network

Env: APP_CPUS=$APP_CPUS APP_MEM=$APP_MEM POOL_MAX=$POOL_MAX POOL_MIN=$POOL_MIN APP_OPTS="$APP_OPTS"
UIs: Grafana http://localhost:13000  Prometheus http://localhost:19090  App http://localhost:$APP_PORT
EOF
}

net() { podman network exists "$NET" || podman network create "$NET" >/dev/null; }

wait_http() { # url, timeout seconds
  for _ in $(seq 1 "$2"); do curl -fs -o /dev/null "$1" && return 0; sleep 1; done
  echo "Timed out waiting for $1" >&2; return 1
}

build() {
  local what="${1:-all}"
  cd "$ROOT"
  if [[ "$what" == jvm || "$what" == all ]]; then
    ./mvnw -q package -DskipTests -DskipITs -pl quarkus-rest-ms
    podman build -q -f "$INFRA/Containerfile.jvm" -t loadtest-app-jvm quarkus-rest-ms
  fi
  if [[ "$what" == native || "$what" == all ]]; then
    ./mvnw -q package -Dnative -DskipTests -DskipITs -pl quarkus-rest-ms \
      -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman
    podman build -q -f "$INFRA/Containerfile.native" -t loadtest-app-native quarkus-rest-ms
  fi
}

infra() {
  net
  podman rm -f lt-postgres lt-pgexporter lt-prometheus lt-grafana >/dev/null 2>&1 || true
  podman run -d --name lt-postgres --network "$NET" -p 15432:5432 \
    -e POSTGRES_USER=quarkus -e POSTGRES_PASSWORD=quarkus -e POSTGRES_DB=quarkus_db \
    docker.io/library/postgres:16-alpine \
    -c max_connections=100 -c shared_preload_libraries=pg_stat_statements >/dev/null
  podman run -d --name lt-pgexporter --network "$NET" \
    -e DATA_SOURCE_NAME="postgresql://quarkus:quarkus@lt-postgres:5432/quarkus_db?sslmode=disable" \
    quay.io/prometheuscommunity/postgres-exporter:latest >/dev/null
  podman run -d --name lt-prometheus --network "$NET" -p 19090:9090 \
    -v "$INFRA/prometheus.yml:/etc/prometheus/prometheus.yml:ro,z" \
    docker.io/prom/prometheus:latest >/dev/null
  podman run -d --name lt-grafana --network "$NET" -p 13000:3000 \
    -e GF_AUTH_ANONYMOUS_ENABLED=true -e GF_AUTH_ANONYMOUS_ORG_ROLE=Admin \
    -v "$INFRA/grafana/provisioning:/etc/grafana/provisioning:ro,z" \
    -v "$INFRA/grafana/dashboards:/var/lib/grafana/dashboards:ro,z" \
    docker.io/grafana/grafana:latest >/dev/null
  echo "Infra started. Grafana: http://localhost:13000"
}

app() {
  local mode="${1:?jvm|native}"
  net
  podman rm -f lt-app >/dev/null 2>&1 || true
  local args=(-d --name lt-app --network "$NET" -p "$APP_PORT:8080"
    --cpus "$APP_CPUS" --memory "$APP_MEM"
    -e DB_HOST=lt-postgres
    -e QUARKUS_DATASOURCE_JDBC_MAX_SIZE="$POOL_MAX"
    -e QUARKUS_DATASOURCE_JDBC_MIN_SIZE="$POOL_MIN"
    -e QUARKUS_DATASOURCE_JDBC_INITIAL_SIZE="$POOL_MIN")
  case "$mode" in
    jvm) podman run "${args[@]}" -e JAVA_OPTS_APPEND="$APP_OPTS" loadtest-app-jvm >/dev/null ;;
    native) podman run "${args[@]}" loadtest-app-native ./application -Dquarkus.http.host=0.0.0.0 $APP_OPTS >/dev/null ;;
    *) usage; exit 1 ;;
  esac
  echo "$mode" > "$ROOT/load-test/target/.mode" 2>/dev/null || { mkdir -p "$ROOT/load-test/target"; echo "$mode" > "$ROOT/load-test/target/.mode"; }
  wait_http "http://localhost:$APP_PORT/q/metrics" 60
  echo "lt-app ($mode) is up: $(podman logs lt-app 2>&1 | grep -o 'started in [0-9.]*s' | head -1)"
}

reset_db() {
  podman exec lt-postgres psql -U quarkus -d quarkus_db -c "TRUNCATE trade_transactions RESTART IDENTITY" || true
}

run() {
  local sim="${1:?simulation name}"; shift
  local mode; mode="$(cat "$ROOT/load-test/target/.mode" 2>/dev/null || echo unknown)"
  local stamp; stamp="$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$OUT"
  local csv="$OUT/$stamp-$mode-$sim-container-stats.csv"
  echo "time,cpu,mem_usage" > "$csv"
  (while true; do
     echo "$(date +%T),$(podman stats --no-stream --format '{{.CPUPerc}},{{.MemUsage}}' lt-app 2>/dev/null)" >> "$csv"
     sleep 2
   done) &
  local sampler=$!
  cd "$ROOT"
  ./mvnw -pl load-test gatling:test \
    -Dgatling.simulationClass="cz.rychter.loadtest.${sim}Simulation" \
    -DbaseUrl="http://localhost:$APP_PORT" "$@" || true
  kill "$sampler" 2>/dev/null || true
  echo "Container stats: $csv"
  echo "Gatling report:  $(ls -dt "$ROOT"/load-test/target/gatling/*/ | head -1)index.html"
}

info() {
  podman images --format '{{.Repository}} {{.Size}}' | grep loadtest-app || true
  podman logs lt-app 2>&1 | grep -E 'started in|Installed features' | head -2
  podman stats --no-stream lt-app
}

down() {
  podman rm -f lt-app lt-postgres lt-pgexporter lt-prometheus lt-grafana >/dev/null 2>&1 || true
  podman network rm "$NET" >/dev/null 2>&1 || true
}

case "${1:-help}" in
  build) build "${2:-all}" ;;
  infra) infra ;;
  app) app "${2:-}" ;;
  reset-db) reset_db ;;
  run) shift; run "$@" ;;
  info) info ;;
  down) down ;;
  *) usage ;;
esac
