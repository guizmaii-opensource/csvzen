# csvzen performance overhaul — design

Date: 2026-05-03
Status: design (spec)
Target release: csvzen 1.0.0

## Goal

Make csvzen the fastest, least-allocating CSV writer on the JVM. Every change is
gated by JMH measurements taken before and after — no optimization lands without
data showing it moved a relevant cell.

## Constraints and decisions

These are locked in advance of the plan and override later improvisation.

| # | Decision | Rationale |
|---|---|---|
| 1 | All-out: include the `byte[]` foundation rewrite | User wants maximum throughput. Per the perf doc, the rewrite is the largest plausible win. |
| 2 | csvzen-vs-csvzen benchmarks for the optimization phases; competitor comparison only as the final phase | Keeps the optimization loop fast; competitor comparison is reporting, not optimization. |
| 3 | Datasets: 1k / 100k / 10M rows × `Mixed` + `IntHeavy` + `DoubleHeavy` + `StringHeavy` schemas | Targeted profiles let us see which optimization helped which workload. |
| 4 | Both null-sink and tmp-file output for every cell | Distinguishes encoder cost from plumbing cost; catches `BufferedWriter`-removal regressions on real IO. |
| 5 | Library JDK floor: 21. Bench JVM: 25. | Bumps floor to enable Vector API and recent JDK features; benches reflect the user's deployment. |
| 6 | UTF-8 only with ASCII fast path. `Charset` parameter dropped. | The 99 % case for CSV. ISO-8859-1 etc. is a niche we are willing to break for the perf gain. |
| 7 | Correctness: existing tests + parser-roundtrip + byte-for-byte differential against the *current* `Writer`-backed encoder, **except for float/double** which use targeted unit tests + parser-roundtrip only | The byte[] rewrite is high-risk; differential is the strongest guard. Schubfach intentionally diverges from `Float.toString` on rounding edges so floats are carved out. |
| 8 | Quoting scan: SWAR + Vector API hybrid, length-dispatched | SWAR captures the bulk of the win portably; Vector API tops up on long strings. |
| 9 | Six-phase strict bench-first plan | Each phase fully landed and benched before the next starts; intermediate releases are a free choice, not enforced. |
| 10 | Out of scope for 1.0: `FileChannel` + direct `ByteBuffer`, `mmap` writes, off-heap buffer reuse, FFM, CSV reader | Marginal wins per the perf doc unless upstream is byte-tight. Reopened conditionally if P4 shows a > 30 % gap to FastCSV on file-sink cells. |

## Architecture

### Module layout (after)

```
modules/
  core/        existing — internals rewritten, public method names preserved
  test-kit/    existing — one mechanical change (StringWriter → ByteArrayOutputStream + UTF-8 decode for the golden comparison)
  zio/         existing — signature updates (drop charset)
  bench/       new — sbt-jmh, publish/skip := true, depends on core
```

The `test-kit` change is required because `golden.scala` currently calls `CsvWriter.unsafeFromWriter(sw: StringWriter, …)`. After P1 it becomes `CsvWriter.unsafeFromOutputStream(baos: ByteArrayOutputStream, …)` followed by `baos.toString(StandardCharsets.UTF_8)` to materialise the comparison string. Identical behaviour, identical golden output.

### Public API delta (csvzen 1.0 vs. 0.x)

Breaking:

- `CsvWriter.open(path, config, charset, options*)` → `CsvWriter.open(path, config, options*)`. Charset parameter dropped; output is always UTF-8.
- `CsvWriter.unsafeFromWriter(out: Writer, config: CsvConfig)` → `CsvWriter.unsafeFromOutputStream(out: OutputStream, config: CsvConfig)`.
- `csvzen-zio`: `openCsvWriter`, `csvSink`, `csvSinkDiscard` lose their `charset` parameters.
- JDK floor 17 (implicit) → 21 (declared).

Source-compatible:

- `FieldEmitter.emit*` method names and signatures unchanged.
- `CsvRowEncoder` and `CsvFieldEncoder` traits unchanged.
- `CsvWriter.writeRow`, `writeAll`, `writeHeader` unchanged.

New:

- Optional buffer-size parameter on `CsvWriter.open` (default chosen empirically per phase from `BufferSweepBench`).
- `FieldEmitter.flushCount: Long` — package-private getter, exposed to tests and the bench module via a friend-access pattern (e.g. an `internal` package object). Counts every flush of the byte buffer to the underlying `OutputStream`. Non-public.

### Buffer ownership

`FieldEmitter` owns one `byte[]` buffer. On overflow it flushes via `OutputStream.write(buf, 0, pos)` and resets `pos = 0`. The `OutputStream` is held unbuffered — csvzen's own buffer *is* the buffer; `BufferedOutputStream` wrapping is rejected as it doubles the buffer hierarchy. `flush()` flushes csvzen's buffer then the underlying stream; `close()` flushes both then closes.

### Vector API

`--add-modules jdk.incubator.vector` is required at runtime *only when the Vector API path is hit* (long string fields above the dispatched threshold). The SWAR path imposes no module flag. The README documents this explicitly.

## Bench harness (P0 deliverable)

### Module skeleton

```
modules/bench/
  src/main/scala/com/guizmaii/csvzen/bench/
    Schemas.scala            Mixed/IntHeavy/DoubleHeavy/StringHeavy + DeriveGen
    BenchData.scala          precomputed Vector[A] per schema × size, fixed seed
    NullOutputStream.scala   pos-tracking, no syscalls
    CsvWriteBench.scala      the 24 (later 30) @Benchmark methods
    BufferSweepBench.scala   @Param-driven buffer-size sweep
    QuotingThresholdBench.scala  added in P3
    FastCsvBench.scala       added in P4
    JacksonCsvBench.scala    added in P4
  results/                   per-phase JSON + markdown summaries
```

### Schemas (5 fields each, all flat case classes deriving `CsvRowEncoder`)

- `Mixed`: `Long id, String name, Int count, Double amount, Instant ts`
- `IntHeavy`: `Long, Long, Long, Long, Long`
- `DoubleHeavy`: `Double, Double, Double, Double, Double`
- `StringHeavy`: `String × 5`. Half generated to need quoting (contain `,`, `"`, or `\n`); half plain ASCII alphanumeric. ASCII-Latin only — no Unicode bidi from `DeriveGen[String]`.
- (P3) `StringHeavy-long`: `String × 5`, each 512+ ASCII bytes, half needing quoting. Justifies the Vector API path.

### Sizes

`small=1k`, `medium=100k`, `large=10M`. Generated once per `@State(Scope.Benchmark)` with a fixed seed.

### Output targets

- **null sink**: `NullOutputStream` (write methods are pos-tracking no-ops).
- **temp file**: `Files.createTempFile` per `@Setup(Iteration)`, deleted in `@TearDown(Iteration)`. Path on `/tmp` to keep disk noise low.

### Cell count

| Phase | Cells |
|---|---|
| P0–P2 | 4 schemas × 3 sizes × 2 sinks = 24 |
| P3+ | 5 schemas × 3 sizes × 2 sinks = 30 |

### JMH config

- Mode: `Throughput` for small/medium. `SingleShotTime` for large (10M rows; Throughput at multi-second per-op is noisy).
- Forks: 2 small/medium, 1 large. Warmup: 5 × 2s small/medium, 1 large. Measurement: 10 × 2s small/medium, 3 large.
- JVM args: `-Xms2g -Xmx2g -XX:+UseG1GC --add-modules jdk.incubator.vector`. The `--add-modules` is harmless before P3 and required from P3 onward.
- Profilers always-on: `-prof gc`, `-prof stack`. Diagnostic-only opt-in: `-prof async:event=alloc` (requires `libasyncProfiler.{dylib,so}` installed locally; documented in `modules/bench/README.md`). Linux-only opt-in: `-prof async:event=cpu,interval=1ms` for flame graphs.
- JDK: 25 (configured via `Jmh / javaHome`).

### `BufferSweepBench`

- Single schema, single size: `Mixed @ 100k`, both sinks.
- `@Param` over `{4 KB, 8 KB, 16 KB, 32 KB, 64 KB, 128 KB, 256 KB, 512 KB, 1 MB}` = 9 values × 2 sinks = 18 cells.
- Run at P0 (baseline), P1 (picks the new default after the rewrite), P3 (re-checks after Vector API). Each run produces a line plot of ops/sec vs. buffer size; the optimum sets the default for the next phase.

### `flushCount`

Package-private `Long` field on `FieldEmitter`, incremented on every flush. Two assertion-style unit tests, *not* benches:

- `IntHeavy @ 100k` with the configured buffer flushes between N and N+ε times (catches buffer-sizing regressions).
- The same workload calls `OutputStream.write(byte[], int, int)` exactly `flushCount` times and never `OutputStream.write(int)` (catches per-byte-write regressions which would tank syscall throughput).

### Cells we explicitly skip

Scala 2.13 cross-build (csvzen is Scala 3 only); Scala.js / Native (out of scope); custom dialects (TSV, semicolon, `\n`-only — same hot loop, no extra signal); append-mode file writes.

## Phase plan

Each phase ends with: all tests green + cell rerun (24 or 30) + results committed under `modules/bench/results/<phase>/` + a one-paragraph delta narrative in the phase commit message.

### Regression bar

Any cell **more than 5 % slower** than the prior phase, or **any increase** in `gc.alloc.rate.norm`, blocks the phase from landing. (5 % is calibrated to JMH noise on these schemas; can be relaxed for genuinely noisy cells with explicit justification.)

### P0 — Bench harness + baseline

No csvzen runtime change. Lands `csvzen-bench`, the 24-cell main bench, `BufferSweepBench`, the `flushCount` getter on `FieldEmitter`, and `results/p0-baseline/{json, md}`.

**DoD:** baseline JSON + markdown summary committed; `flushCount` test passes against the *current* Writer-backed path.

### P1 — byte[] foundation rewrite

- `FieldEmitter` rewritten: `byte[]` buffer + `OutputStream`. ASCII fast path (`buf[pos++] = c.toByte`). Hand-rolled UTF-8 encoder for non-ASCII (handles surrogate pairs → 4-byte sequences correctly).
- `writeInt` / `writeLong` rewritten to write digit *bytes* directly into `buf`. Negative-domain extraction trick preserved.
- `writeEscaped` keeps its byte-by-byte structure for this phase (vectorization is P3) but operates on bytes.
- `emitFloat` / `emitDouble` continue to call `java.lang.Float.toString` / `Double.toString` (Schubfach is P2). Their ASCII output is written via the fast path.
- Public API breakage applied: drop `charset` from `CsvWriter.open`; rename `unsafeFromWriter` → `unsafeFromOutputStream`; ZIO sinks lose `charset`.
- Migrate existing `*Spec` tests in `core` and `zio`, plus `test-kit/golden.scala`, from `StringWriter` to `ByteArrayOutputStream` + UTF-8 decode. Mechanical sweep — affects every test file that compares emitted CSV against a string literal.
- Test fixture: legacy Writer-backed `FieldEmitter` preserved verbatim in `core/src/test/scala/.../legacy/FieldEmitterLegacy.scala`. Differential property test runs every input through both, asserts byte-for-byte equality on schemas without floats. FastCSV pulled in as a `Test`-scope dep for parser-roundtrip checks.
- `BufferSweepBench` rerun → new default written into `CsvWriter.open`.

**DoD:** differential + parser-roundtrip green; main bench shows ≥ 2× on `IntHeavy/null-sink`; `gc.alloc.rate.norm` drops to ~0 on `IntHeavy/null-sink` and `StringHeavy/null-sink`.

### P2 — In-buffer Schubfach

- New `core/src/main/scala/.../internal/Schubfach.scala` — port of the Schubfach algorithm writing decimal digits directly into `(buf, pos)`, returning new `pos`. Zero `String` allocation per emit.
- `emitFloat` / `emitDouble` switch to it.
- Targeted unit tests for historically-tricky inputs: `±0.0`, `±Inf`, `NaN`, `Float.MIN_VALUE`, `Float.MIN_NORMAL`, `Float.MAX_VALUE`, `Double.MIN_VALUE`, `Double.MIN_NORMAL`, `Double.MAX_VALUE`, plus the Grisu/Ryū rounding-boundary vectors lifted from Raffaello Giulietti's reference suite.
- Differential test stays *disabled* for floats per Q7 carve-out. Float correctness rests on targeted vectors + FastCSV parser-roundtrip ("does FastCSV parse our output back to the same `Float`/`Double`?").

**DoD:** all float-correctness tests green; `DoubleHeavy/null-sink` `gc.alloc.rate.norm` drops by ≥ 80 %; throughput ≥ 1.5× on `DoubleHeavy/null-sink`; no regression on any other cell.

### P3 — Quoting scan (SWAR + Vector API hybrid)

- `writeEscaped`'s prescan loop becomes length-dispatched:
  - `len < T_swar`: byte-by-byte scan.
  - `T_swar ≤ len < T_vec`: SWAR — read 8 bytes as a `long`, mask-test the four sentinels (`,`, `"`, `\r`, `\n`) simultaneously.
  - `len ≥ T_vec`: `jdk.incubator.vector.ByteVector` with species-preferred lane width.
- New `QuotingThresholdBench` sweeps `T_swar` and `T_vec` over `StringHeavy` with controlled string lengths to find crossovers. Defaults baked in from the result.
- Main bench grows from 24 to 30 cells — `StringHeavy-long` added.
- `BufferSweepBench` rerun. Default updated if the optimum shifted.

**DoD:** `StringHeavy-long/null-sink` throughput ≥ 1.5× the same cell measured immediately after P2; no regression elsewhere; `T_swar` and `T_vec` defaults documented in source comments.

### P4 — Competitor benchmarks (measurement only)

- `csvzen-bench` adds `de.siegmar:fastcsv` and `com.fasterxml.jackson.dataformat:jackson-dataformat-csv` as bench-only deps.
- Two new bench classes, equivalent CSV output (UTF-8, RFC 4180, `\r\n`).
- Results committed under `results/competitors/`.
- **No win/loss bar — this is reporting, not optimization.** Surprising gaps open follow-up issues but do not block.

**DoD:** comparison committed; gap-analysis paragraph in the phase commit message.

### P5 — Release prep

- README: drop `Charset` from examples; declare JDK 21 floor; document `--add-modules jdk.incubator.vector` runtime requirement when consuming the Vector API path; document the configurable buffer size.
- `CHANGELOG.md`: enumerate breaking changes.
- Version bump to `1.0.0`. MiMa report verified to show only the *expected* breakages.
- One end-to-end smoke test using a `scala-cli` script as a downstream consumer.

**DoD:** clean `sbt --client +publishLocal`; README example is copy-pasteable and runs.

## Phase ordering rationale

- P1 must precede P2: Schubfach writes into the byte buffer.
- P3 could technically precede P2 (independent of float emission), but doing P2 first means P3's bench numbers are not muddied by the float change.
- P4 and P5 are sequenced last by definition.

## Out of scope for 1.0

- `FileChannel` + direct `ByteBuffer` writes
- `mmap` writes
- Off-heap buffer reuse
- Foreign Function & Memory API (FFM)
- CSV reader / parser

These are conditionally reopenable as a P6 if P4 shows csvzen still > 30 % behind FastCSV on file-sink cells.

## Open follow-ups

None blocking. Everything required for the implementation plan is settled.

## Implementation plan

The implementation plan (step-by-step task breakdown) is generated separately
via the `superpowers:writing-plans` skill, after this design is approved.
