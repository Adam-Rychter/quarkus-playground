#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../../../.." && pwd)"

"$REPO_ROOT/mvnw" -f "$REPO_ROOT/pom.xml" -pl testing-java package

exec java -jar "$REPO_ROOT/testing-java/target/benchmarks.jar" AllocationBenchmark -prof gc "$@"
