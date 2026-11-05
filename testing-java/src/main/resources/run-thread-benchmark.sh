#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../../../.." && pwd)"
JAR="$REPO_ROOT/testing-java/target/benchmarks.jar"
RESULT_ROOT="${THREAD_BENCHMARK_RESULTS_DIR:-$REPO_ROOT/testing-java/target/thread-benchmark-results}"
REPORT="${THREAD_BENCHMARK_REPORT:-$REPO_ROOT/testing-java/THREAD_BENCHMARK_REPORT.md}"
RUN_DIR="$RESULT_ROOT/run-$(date +%Y%m%dT%H%M%S)-$$"

for argument in "$@"; do
    case "$argument" in
        -p|-p*|-t|-t[0-9]*|-t=*|-threads|-threads=*|--threads|--threads=*)
            echo "Thread-count and workload parameters are fixed to the safe benchmark matrix" >&2
            exit 2
            ;;
    esac
done

if ! command -v python3 >/dev/null 2>&1; then
    echo "python3 is required to generate the comparison report" >&2
    exit 1
fi
if [ ! -x /usr/bin/time ]; then
    echo "/usr/bin/time is required to capture isolated process peak RSS" >&2
    exit 1
fi

"$REPO_ROOT/mvnw" -f "$REPO_ROOT/pom.xml" -pl testing-java package
mkdir -p "$RUN_DIR"

java -jar "$JAR" 'ThreadBenchmark.platformThreads' -prof gc -rf json -rff "$RUN_DIR/platform.json" "$@"
java -jar "$JAR" 'ThreadBenchmark.virtualThreads' -prof gc -rf json -rff "$RUN_DIR/virtual.json" "$@"

printf 'threadType,threadCount,elementCount,maxRssKiB\n' > "$RUN_DIR/peak-memory.csv"
for thread_type in platform virtual; do
    if [ "$thread_type" = platform ]; then
        benchmark='ThreadBenchmark.platformThreads'
    else
        benchmark='ThreadBenchmark.virtualThreads'
    fi

    for thread_count in 1 16 64; do
        for element_count in 1000 5000; do
            memory_file="$RUN_DIR/memory-$thread_type-$thread_count-$element_count.txt"
            log_file="$RUN_DIR/memory-$thread_type-$thread_count-$element_count.log"
            /usr/bin/time -f '%M' -o "$memory_file" \
                java -jar "$JAR" "$benchmark" \
                    -f 0 -wi 0 -i 1 -r 100ms \
                    -p "threadCount=$thread_count" \
                    -p "elementCount=$element_count" \
                    > "$log_file" 2>&1
            rss_kib="$(cat "$memory_file")"
            printf '%s,%s,%s,%s\n' "$thread_type" "$thread_count" "$element_count" "$rss_kib" \
                >> "$RUN_DIR/peak-memory.csv"
        done
    done
done

python3 "$SCRIPT_DIR/render-thread-benchmark-report.py" \
    "$RUN_DIR/platform.json" "$RUN_DIR/virtual.json" "$RUN_DIR/peak-memory.csv" "$REPORT" "$*"
printf 'Raw JMH and process-memory data: %s\nReport: %s\n' "$RUN_DIR" "$REPORT"
