# csvzen-bench

JMH benchmarks for csvzen. Private module — `publish / skip := true`. Not part
of the published artifact set; exists only to gate optimization phases on
measured deltas.

## Layout

- `CsvWriteBench` — main 24-cell bench (4 schemas × 3 sizes × 2 sinks)
  - `CsvWriteBenchSmallMedium`: 1k + 100k rows, `Throughput` mode
  - `CsvWriteBenchLarge`: 10M rows, `SingleShotTime` mode
- `BufferSweepBench` — 9-point buffer-size sweep on `Mixed @ 100k`, both sinks
- (P3 will add `QuotingThresholdBench`)
- (P4 will add `FastCsvBench` and `JacksonCsvBench`)

## Running

Bench JVM target is JDK 25 (per design doc). Set `JAVA_HOME` accordingly:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"   # macOS
# or
export JAVA_HOME="/usr/lib/jvm/java-25-openjdk"      # Linux
```

### Full main bench (all 24 cells, ~30 minutes total)

```bash
sbt --client "bench/Jmh/run -prof gc -prof stack -rf json -rff modules/bench/results/p0-baseline/csv-write.json com.guizmaii.csvzen.bench.CsvWriteBench.*"
```

### Buffer sweep (~10 minutes)

```bash
sbt --client "bench/Jmh/run -prof gc -rf json -rff modules/bench/results/p0-baseline/buffer-sweep.json com.guizmaii.csvzen.bench.BufferSweepBench.*"
```

### Quick single-cell smoke (use during development)

```bash
sbt --client "bench/Jmh/run -wi 2 -i 3 -f 1 .*intHeavy_medium_null.*"
```

## Profilers

`-prof gc` and `-prof stack` are the always-on diagnostic baseline. Use these
opt-in profilers when investigating a regression:

### async-profiler (allocations, macOS + Linux)

Install async-profiler first:

```bash
# macOS
brew install async-profiler

# Linux — download from https://github.com/async-profiler/async-profiler/releases
```

Then run with the `async` profiler:

```bash
sbt --client "bench/Jmh/run -prof async:event=alloc;dir=modules/bench/results/async/ .*doubleHeavy_medium_null.*"
```

This produces flame graphs showing where allocations originate.

### CPU sampling (Linux only)

```bash
sbt --client "bench/Jmh/run -prof async:event=cpu;interval=1ms;dir=modules/bench/results/async-cpu/ .*StringHeavy.*"
```

## Results

Per-phase results live under `modules/bench/results/<phase>/`. Each phase
commits:

- `csv-write.json` — raw JMH output for the 24-cell main bench
- `buffer-sweep.json` — raw JMH output for the buffer sweep (P0, P1, P3 only)
- `SUMMARY.md` — human-written narrative: hardware, JDK version, top-line
  numbers per cell, deltas vs. previous phase

The JSON is committed because per-phase comparisons need it. JSON is small
(< 100 KB per file).
