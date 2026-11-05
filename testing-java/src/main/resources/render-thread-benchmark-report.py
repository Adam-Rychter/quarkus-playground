#!/usr/bin/env python3
import csv
import json
import os
import platform
import statistics
import subprocess
import sys
from pathlib import Path


def load_results(path, thread_type):
    with open(path, encoding="utf-8") as results_file:
        results = json.load(results_file)
    parsed = {}
    for result in results:
        params = result["params"]
        key = (int(params["threadCount"]), int(params["elementCount"]))
        primary = result["primaryMetric"]
        allocation = result.get("secondaryMetrics", {}).get("gc.alloc.rate.norm", {})
        parsed[key] = {
            "time": float(primary["score"]),
            "unit": primary["scoreUnit"],
            "confidence": primary.get("scoreConfidence"),
            "allocation": allocation.get("score"),
            "allocation_unit": allocation.get("scoreUnit", "B/op"),
        }
    if not parsed:
        raise ValueError(f"No {thread_type} JMH results found in {path}")
    return parsed


def java_version():
    completed = subprocess.run(
        ["java", "-version"], capture_output=True, text=True, check=False
    )
    return (completed.stderr or completed.stdout).splitlines()[0].strip()


def fmt(value, digits=2):
    return "n/a" if value is None else f"{value:,.{digits}f}"


def bar(value, maximum, width=24):
    length = max(1, round(width * value / maximum)) if maximum else 0
    return "█" * length


def main():
    if len(sys.argv) != 6:
        raise SystemExit(
            "Usage: render-thread-benchmark-report.py platform.json virtual.json "
            "peak-memory.csv report.md timing-arguments"
        )
    platform_data = load_results(sys.argv[1], "platform")
    virtual_data = load_results(sys.argv[2], "virtual")
    with open(sys.argv[3], newline="", encoding="utf-8") as memory_file:
        memory = {
            (row["threadType"], int(row["threadCount"]), int(row["elementCount"])): int(
                row["maxRssKiB"]
            )
            for row in csv.DictReader(memory_file)
        }

    keys = sorted(set(platform_data) & set(virtual_data))
    if not keys:
        raise ValueError("Platform and virtual-thread JSON files have no matching parameters")

    run_directory = os.path.relpath(Path(sys.argv[1]).parent, Path(sys.argv[4]).parent)
    timing_arguments = sys.argv[5]
    timing_setup = (
        "Timing runs used the benchmark defaults: two 1 s warmup iterations, three 1 s "
        "measurement iterations, and one fork."
        if not timing_arguments
        else f"Timing runs used these JMH CLI overrides: `{timing_arguments}`."
    )
    lines = [
        "# Platform threads vs. virtual threads",
        "",
        "## Results",
        "",
        "| Threads × values/set | Platform time (µs/op, mean [CI]) | Virtual time (µs/op, mean [CI]) | Platform / virtual | Platform alloc (B/op) | Virtual alloc (B/op) | Platform peak RSS (MiB) | Virtual peak RSS (MiB) |",
        "|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for key in keys:
        platform_result = platform_data[key]
        virtual_result = virtual_data[key]
        if platform_result["unit"] != "us/op" or virtual_result["unit"] != "us/op":
            raise ValueError("Expected JMH primary scores in us/op")
        ratio = platform_result["time"] / virtual_result["time"]
        platform_rss = memory.get(("platform", *key))
        virtual_rss = memory.get(("virtual", *key))
        platform_interval = platform_result["confidence"]
        virtual_interval = virtual_result["confidence"]
        platform_time = fmt(platform_result["time"])
        virtual_time = fmt(virtual_result["time"])
        if platform_interval:
            platform_time += f" [{fmt(platform_interval[0])}, {fmt(platform_interval[1])}]"
        if virtual_interval:
            virtual_time += f" [{fmt(virtual_interval[0])}, {fmt(virtual_interval[1])}]"
        lines.append(
            f"| {key[0]} × {key[1]:,} | {platform_time} | "
            f"{virtual_time} | {ratio:.2f}× | "
            f"{fmt(platform_result['allocation'])} | {fmt(virtual_result['allocation'])} | "
            f"{fmt(platform_rss / 1024 if platform_rss is not None else None)} | "
            f"{fmt(virtual_rss / 1024 if virtual_rss is not None else None)} |"
        )

    speedups = [
        platform_data[key]["time"] / virtual_data[key]["time"]
        for key in keys
    ]
    allocation_deltas = [
        abs(platform_data[key]["allocation"] - virtual_data[key]["allocation"])
        / platform_data[key]["allocation"]
        * 100
        for key in keys
        if platform_data[key]["allocation"] and virtual_data[key]["allocation"] is not None
    ]
    rss_deltas = [
        (memory[("virtual", *key)] / memory[("platform", *key)] - 1) * 100
        for key in keys
        if ("platform", *key) in memory
        and ("virtual", *key) in memory
        and memory[("platform", *key)]
    ]
    lines.extend(
        [
            "",
            "## Snapshot",
            "",
            f"- Virtual threads were faster in {sum(speedup > 1 for speedup in speedups)}/"
            f"{len(speedups)} cases; the geometric-mean speedup was "
            f"{statistics.geometric_mean(speedups):.2f}× (range {min(speedups):.2f}×–"
            f"{max(speedups):.2f}×).",
        ]
    )
    if allocation_deltas:
        lines.append(
            f"- Normalized heap allocation differed by at most {max(allocation_deltas):.2f}% "
            "between thread types for matched cases."
        )
    if rss_deltas:
        higher = sum(delta > 0 for delta in rss_deltas)
        lines.append(
            f"- Virtual-thread probe peak RSS was higher in {higher}/{len(rss_deltas)} cases; "
            f"the measured relative difference ranged from {min(rss_deltas):+.1f}% to "
            f"{max(rss_deltas):+.1f}%. Treat this only as a whole-process high-water "
            "observation, not a thread-stack comparison."
        )

    lines.extend(["", "## Relative elapsed-time chart", ""])
    for key in keys:
        platform_time = platform_data[key]["time"]
        virtual_time = virtual_data[key]["time"]
        maximum = max(platform_time, virtual_time)
        lines.extend(
            [
                f"**{key[0]} threads × {key[1]:,} values per set**",
                "",
                f"- Platform: `{bar(platform_time, maximum)}` {fmt(platform_time)} µs/op",
                f"- Virtual: `{bar(virtual_time, maximum)}` {fmt(virtual_time)} µs/op",
                "",
            ]
        )

    lines.extend(
        [
            "## Method",
            "",
            f"- Environment: `{java_version()}` on `{platform.platform()}`; "
            f"{os.cpu_count() or 'unknown'} logical CPUs reported by Python.",
            "- Each operation starts a bounded batch of 1, 16, or 64 threads. Every worker "
            "builds a `HashSet<Integer>` of 1,000 or 5,000 deterministic unique values, "
            "checks its size, and hashes its contents; the aggregate hash is returned to JMH.",
            "- The platform and virtual paths use the same workload, a start gate, one JMH "
            f"harness thread, and one fork. Time columns show the JMH mean and its reported "
            f"confidence-interval bounds. {timing_setup} `gc.alloc.rate.norm` is JMH's "
            "normalized allocation metric when available.",
            "- Peak RSS is captured in a fresh JVM process per thread-count/workload pair with "
            "`/usr/bin/time`; these quick probes use one 100 ms JMH measurement and no fork so "
            "the timed process is the measured JVM. Values include JVM baseline/committed heap "
            "and runtime overhead, not only thread stacks.",
            f"- Raw JMH JSON, per-probe logs, and the RSS CSV are retained in `{run_directory}`.",
            "",
            "## Interpretation and limitations",
            "",
            "- A platform/virtual ratio above 1 means the virtual-thread operation was faster; "
            "below 1 means it was slower. These results apply to this allocation-heavy workload "
            "and this machine/JDK, not to all applications or thread workloads.",
            "- JMH allocation metrics describe Java heap allocation per operation; they do not "
            "measure native thread-stack memory. Peak RSS is a whole-process high-water mark, "
            "including JVM startup and heap/runtime costs, and the short RSS probe is not a "
            "steady-state or per-thread memory measurement.",
            "- Results vary with CPU scheduling, garbage collection, JIT compilation, available "
            "memory, and background system load. Re-run under comparable conditions before "
            "drawing conclusions.",
            "",
        ]
    )
    output = Path(sys.argv[4])
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    main()
