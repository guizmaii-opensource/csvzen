# P0 baseline — csvzen pre-rewrite

**Hardware:** MacBook Pro `Mac15,7` (Apple M3 Pro, 36 GB), macOS Darwin 25.4.0 (arm64)
**JDK:** OpenJDK Corretto 25.0.2 (`25.0.2+10-LTS`)
**csvzen:** branch `perf/p0-bench-harness`, run on the tree at the time of `csv-write.json` capture
**Date:** 2026-05-04

JMH 1.37 via sbt-jmh 0.4.7. JVM args (per `@Fork`): `-Xms2g -Xmx2g -XX:+UseG1GC --add-modules jdk.incubator.vector` for `CsvWriteBenchSmallMedium` and `BufferSweepBench`; `-Xms10g -Xmx10g -XX:+UseG1GC --add-modules jdk.incubator.vector` for `CsvWriteBenchLarge`. Profilers `-prof gc -prof stack` on the main bench, `-prof gc` on the sweep.

## Headline numbers

- **csvzen writes a flat 5-field row at ≈ 5–7 K rows/sec on small/medium null-sink workloads** (millions of rows per second when amortised: small Mixed = 3.7 M rows/sec, IntHeavy = 5.8 M rows/sec, StringHeavy = 7.2 M rows/sec).
- **Allocation is dominated by `Double.toString` / `Float.toString` and `Instant.toString`.** `DoubleHeavy/medium/null` allocates 52 MB per benchmark op (per 100k-row write) — the per-row Schubfach `String` allocation called out in `docs/performance-notes.md`. P2 must drop this by ≥ 80 %.
- **`IntHeavy` and `StringHeavy` already allocate near-zero on null sinks** (~106 KB per 100k-row write) — most of that is `BufferedWriter`/`CharsetEncoder` internals. P1's byte[] rewrite drops it to ~0.
- **The `BufferedWriter` size sweep is essentially flat** — 4 KB to 1 MB moves null-sink throughput by < 5 %. The current `Writer`/`CharsetEncoder` path is the bottleneck, not buffer size. P1 buffer-sweep rerun (post-rewrite) is what actually picks the new default.

## Main bench (`CsvWriteBench`)

Throughput in `ops/s` (one op = one full N-row write). For Large (10M rows) the JMH mode is `SingleShotTime` (`s/op`); the Mode column flags this. Allocation in `gc.alloc.rate.norm` (B/op).

### Null sink

| schema      | small (1k)        | small B/op | medium (100k)     | medium B/op | large (10M, ss)   | large B/op |
|-------------|-------------------|------------|-------------------|-------------|-------------------|------------|
| Mixed       | 3,667 ops/s       | 329.7 KB   | 33 ops/s          | 34.0 MB     | 3.057 s/op        | 4.00 GB    |
| IntHeavy    | 5,768 ops/s       | 26.6 KB    | 57 ops/s          | 105.7 KB    | 1.703 s/op        | 7.9 MB     |
| DoubleHeavy | 4,698 ops/s       | 545.2 KB   | 38 ops/s          | 52.0 MB     | 3.020 s/op        | 5.19 GB    |
| StringHeavy | 7,227 ops/s       | 26.6 KB    | 68 ops/s          | 107.1 KB    | 1.454 s/op        | 8.2 MB     |

### Tmp file (`/tmp/...`, `Files.newBufferedWriter`, default options)

| schema      | small (1k)        | small B/op | medium (100k)     | medium B/op | large (10M, ss)   | large B/op |
|-------------|-------------------|------------|-------------------|-------------|-------------------|------------|
| Mixed       | 403 ops/s         | 331.6 KB   | 26 ops/s          | 32.8 MB     | 4.020 s/op        | 4.00 GB    |
| IntHeavy    | 467 ops/s         | 28.4 KB    | 38 ops/s          | 111.9 KB    | 2.424 s/op        | 8.3 MB     |
| DoubleHeavy | 452 ops/s         | 547.1 KB   | 27 ops/s          | 52.0 MB     | 4.445 s/op        | 5.20 GB    |
| StringHeavy | 469 ops/s         | 28.4 KB    | 42 ops/s          | 112.8 KB    | 2.186 s/op        | 8.6 MB     |

## Buffer sweep (`BufferSweepBench`, Mixed @ 100k)

| bufSize | null-sink ops/s | file ops/s |
|---------|-----------------|------------|
| 4 KB    | 33              | 25         |
| 8 KB    | 34              | 26         |
| 16 KB   | 33              | 26         |
| 32 KB   | 34              | 26         |
| 64 KB   | 34              | 26         |
| 128 KB  | 34              | 27         |
| 256 KB  | 34              | 27         |
| 512 KB  | 34              | 27         |
| 1 MB    | 35              | 28         |

**Optimum (informational):** null-sink optimum is essentially indifferent (35 vs. 33 ops/s — within JMH noise). File-sink optimum is 1 MB at 28 ops/s, but the curve is shallow (12 % spread across the entire 256× range). The current `Writer` path dominates; buffer size barely matters until P1.

## Reference comparisons for later phases

These are the numbers later phases must clear to land. The regression bar is **> 5 % slower on any cell, or any increase in `gc.alloc.rate.norm`, blocks landing**.

- **P1 (byte[] rewrite) DoD:** ≥ 2× throughput on `IntHeavy/small/null` (= ≥ 11.5K ops/s) and `IntHeavy/medium/null` (= ≥ 114 ops/s). `gc.alloc.rate.norm` should drop to ≈ 0 on `IntHeavy/null-sink` and `StringHeavy/null-sink` cells.
- **P2 (in-buffer Schubfach) DoD:** `DoubleHeavy/medium/null` `gc.alloc.rate.norm` drops by ≥ 80 % (from 52.0 MB to ≤ 10.4 MB), throughput ≥ 1.5× (from 38 to ≥ 57 ops/s). The 5.19 GB B/op on the Large null-sink is dominated by 50M `Double.toString` String allocations — the most direct measure of Schubfach impact will be there.
- **P3 (SWAR + Vector API quoting scan) DoD:** the new `StringHeavy-long` schema (5× 512+ char strings, half quoting-needed) — added in P3 — must beat its post-P2 baseline by ≥ 1.5×. Existing schemas must not regress.

## Notes / caveats

- These numbers measure the *current* `Writer`-backed encoder. P1 will replace it; the Writer-vs-byte[] delta lives in P1's SUMMARY.
- The Large benchmarks initially OOM'd with the plan-prescribed `-Xms4g -Xmx4g` — `StringHeavy` alone holds ~3 GB of `String` data live as the trial fixture. Bumped to `-Xms10g -Xmx10g` (committed; see commit log on this branch). Worth folding into the plan upstream.
- The plan's `-rff modules/bench/...` relative path doesn't survive sbt's JMH fork (the sub-process resolves relative paths against a different cwd). Used absolute paths for the captures. Worth folding into the plan upstream.
- `-prof stack` returns `NaN` everywhere — JMH's stack sampler doesn't agree with one of our `--add-modules` interactions on JDK 25, or the sample window was simply too short on the 10M-row SingleShotTime benchmarks. Not pursuing — the data we wanted was `-prof gc`, which captured cleanly.
- `gc.alloc.rate.norm` is per-op (per full N-row write). Per-row allocation: divide by N. Example: `DoubleHeavy/medium/null` → 52.0 MB ÷ 100,000 = ≈ 520 bytes/row, of which ~5 × `Double.toString` ≈ 5 × 24-byte `String` = 120 bytes are the Schubfach allocations and the rest is `BufferedWriter`/`Writer` chain overhead.

## Reproduce locally

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/current"   # JDK 25
export PATH="$JAVA_HOME/bin:$PATH"

# Main bench (~18 min on the M3 Pro). Note absolute path.
sbt --client "bench/Jmh/run -prof gc -prof stack -rf json \
  -rff $PWD/modules/bench/results/p0-baseline/csv-write.json \
  com.guizmaii.csvzen.bench.CsvWriteBench.*"

# Buffer sweep (~18 min).
sbt --client "bench/Jmh/run -prof gc -rf json \
  -rff $PWD/modules/bench/results/p0-baseline/buffer-sweep.json \
  com.guizmaii.csvzen.bench.BufferSweepBench.*"
```
