# Performance notes

Working notes on what governs CSV-writer throughput on the JVM, and where csvzen's
remaining wins are. Not a roadmap, not a promise — a snapshot of what the hot
JVM CSV libraries do and why, used to guide future benchmarking.

## Should we drop `Files.newBufferedWriter`?

Yes, but only marginally on its own. It isn't the dominant cost.

`Files.newBufferedWriter` chains:

- `FileOutputStream` (raw bytes → kernel)
- `OutputStreamWriter` (chars → bytes via `CharsetEncoder`, runs on every encode)
- `BufferedWriter` (8 KB char buffer, synchronized on a `lock` field)

Two real costs:

1. **Char → byte path.** `OutputStreamWriter` runs every chunk through
   `CharsetEncoder` even for pure-ASCII content. Throughput drops on hot
   numeric/identifier output where a 1-char-1-byte fast path would suffice.
2. **8 KB default buffer.** Tiny for a hot writer; high syscall count on large
   outputs.

Replacing it with `BufferedOutputStream` (64–128 KB) → `FileOutputStream`, plus a
custom ASCII fast-path encoder, removes the `CharsetEncoder` overhead for the
common case while keeping a fallback for non-ASCII. `FileChannel` +
`DirectByteBuffer`, or `mmap`, helps for very large outputs but rarely matters
unless the upstream is already byte-tight.

Standalone, this swap is a 5–15 % win. The real unlock is using it as a stepping
stone to a byte-buffer–based emitter (see below).

## What the fast JVM CSV libraries actually do

Common bag of tricks across FastCSV, uniVocity-parsers, sfm-csv, and
jackson-dataformat-csv:

### Bytes, not chars
Skip `Writer` entirely. Write into a `byte[]` ring buffer and flush to
`OutputStream` in big chunks. Treat ASCII (the 99 % case for numeric/identifier
content) as 1 char = 1 byte. UTF-8 multi-byte chars take a slow path.

csvzen currently routes through `Writer.write(int)` for each char in the
escaping path — moving to a `byte[]` buffer replaces virtual calls with
`buf[pos++] = ch`.

### Custom int / long formatting
Format digits directly into the output buffer, no `Integer.toString` /
`Long.toString` round-trip. The JDK does this internally too but allocates a
`String` per call.

csvzen already does this — see `writeInt` / `writeLong` in `FieldEmitter`.

### Schubfach / Ryū for Double / Float
`Double.toString` was the dominant cost on older JDKs (Grisu/dtoa, slow and
allocating). JDK 19+ ships Schubfach internally, so `Double.toString` is now
~3–5× faster — but it still allocates a `String`. Libraries chasing maximum
throughput use a Schubfach implementation that writes digits directly into the
output buffer with no `String` round-trip.

csvzen's `out.write(java.lang.Float.toString(f))` is currently the largest
per-row allocation for float-/double-heavy data.

### Vectorized scan for "needs quoting?"
Detecting whether a string contains `,` `"` `\r` `\n` is the hottest loop on
string-heavy CSVs. Hot libraries scan word-at-a-time (read a `long`, mask-test
all four sentinels simultaneously) or, on JDK 21+, use the Vector API.

csvzen's `writeEscaped` walks char-by-char. Real win on long string fields,
small win on short ones.

### No locking
`BufferedWriter` synchronizes on a `lock` field. Uncontended monitorenter / exit
isn't free. CSV writers are single-threaded by construction (csvzen's
`FieldEmitter` documents "not thread-safe"), so a custom buffer with no locking
removes the cost.

### Bigger OS buffer
64–128 KB, not 8 KB. Halves-or-better the syscall count.

### Pre-flattened encoders
Compose encoders at compile time so each row is a straight-line sequence of
writes — no virtual dispatch per field. csvzen does this via
`CsvRowEncoder.derived[A]`.

## Where csvzen's wins are, ordered

A JMH run on a representative workload would likely rank the wins as:

1. **`Float` / `Double` emission.** Replace `out.write(java.lang.Float.toString(f))`
   with an in-buffer Schubfach implementation. Single biggest allocation kill
   for floating-point-heavy CSVs.
2. **Switch from `Writer` to `byte[]` buffer + `OutputStream`.** ASCII fast path,
   no `CharsetEncoder`, no per-char virtual calls. 2–3× on integer-heavy rows is
   plausible.
3. **Word-at-a-time `writeEscaped` scan.** Wins on long string fields. Not worth
   doing before #1 / #2.
4. **Bigger buffer (64 KB+).** One-line change. Small but free.

Swapping out `Files.newBufferedWriter` on its own is a 5–15 % win. The
encoder/writer architecture change in #2 is what unlocks the larger gains, and
it's a significant rewrite — `FieldEmitter` would take a `byte[]` buffer, the
charset would become the emitter's concern, and a UTF-8 encoder for non-ASCII
would have to live in csvzen.

## How to validate

Before committing to any of the above: JMH a representative workload (a flat
case class with mixed types, ~1M rows) with `-prof gc` to see allocation rate
broken down by call site. That tells you whether `Double.toString` or the
writer path dominates for typical inputs, and whether the rewrite is worth the
complexity for the workloads csvzen actually targets.
