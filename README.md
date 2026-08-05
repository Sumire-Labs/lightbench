# Lightbench for Minecraft 1.12.2

Lightbench is a benchmark harness for comparing light engines under repeatable chunk-generation and block-update workloads. It is not a performance mod and does not change lighting by itself.

This project ports the methodology of [Spottedleaf's original lightbench](https://github.com/Spottedleaf/lightbench) to Forge 1.12.2. Because 1.12.2 light engines use different execution models, the primary result is end-to-end wall time: a batch is not complete until its chunks have been generated and all associated lighting work has finished.

## Generation benchmark

Run either of these commands; they are equivalent:

```text
/lightbench
/lightbench gen
```

The fixed `gen` plan is:

- Warm up with a radius-50 region: 101×101, or 10,201 chunks.
- Measure 36 separate radius-8 regions: 36×17×17, or 10,404 chunks.
- Request at most five chunks at a time.
- Wait for the installed light engine to finish after every batch.
- Use chunk coordinates around −10,000 for warmup and +10,000 onward for measurement. These are chunk coordinates, not block coordinates.

Before any chunk is requested, Lightbench checks every target region plus a one-chunk border using the provider's non-generating existence query. If even one of those chunks is loaded, saved, or waiting to be saved, the command stops without starting the benchmark. This prevents an accidentally reused world from producing artificially low disk-load results and keeps pre-existing neighboring chunks from changing 1.12.2 population behavior.

The most useful result is `gen test ... total-until-lit`. Batch and region percentiles show variance and stalls. For Pulsar, worker-thread CPU time is also reported as supplemental data; it can exceed wall time because multiple workers may run in parallel.

This is intentionally a very heavy synchronous run. The game can appear frozen while it generates more than 20,000 chunks in total, and a dedicated server's watchdog limit must be long enough for the command to finish.

`gen` is an end-to-end chunk-generation workload, not an isolated light-executor microbenchmark. Terrain generation, population and chunk-provider work are intentionally inside its wall time. This makes it useful for measuring the complete 1.12.2 generation path, but small differences between light engines can be hidden by unrelated world-generation cost. Do not present `gen` results as lighting-only timings.

## Light-update benchmark

```text
/lightbench updates
```

Run `updates` in a disposable, server-side Superflat world with a low, uniform, fully opaque and non-emitting floor. The command loads a fixed footprint around block x/z 20,008, verifies at least 240 clear air blocks above every floor column, and normalizes a 64×64 stone platform at y=254. It stops before measurement if the floor, air volume, initial light or engine adapter cannot be verified. The platform is a real world edit, so do not use a world you need to preserve.

The fixed update plan is:

- Warm up with 20 pairs of each workload at a separate position.
- Measure 200 samples for each of `sky_remove`, `sky_place`, `block_place` and `block_remove`.
- Use one fixed measured position per workload so terrain and chunk locality do not vary between samples.
- Wait for the installed engine's server-side lighting work after every individual edit.
- Check the resulting Sky or Block light after every sample, including light level 1 at distance 14 and level 0 at distance 15. Verification happens after the timed interval. A failed check aborts the run without writing a result.

The primary metric is `completion_nanos`: wall time from immediately before `setBlockState` until the engine-specific completion barrier has returned. This includes the block-state submission path because engines divide synchronous and deferred work differently; timing only one internal executor would omit Vanilla's inline work and would not be an equivalent comparison. `submission_nanos` and `barrier_nanos` are retained as diagnostics, but neither should be used alone to rank engines. Vanilla performs lighting inline and therefore has a zero-duration barrier.

The 200 samples in one phase share a JVM, world, chunk and measured position. They describe the distribution of this deliberately hot, local workload; they are not 200 independent benchmark runs and must not be treated as such for confidence claims. Use separate Minecraft restarts as the independent repetitions summarized by the comparator.

`updates` measures logical-server block edits and lighting completion. It does not measure client rendering, frame rate, network latency, particles, neighbor notifications or arbitrary gameplay events. Its values are also specific to Minecraft 1.12.2 and this fixed plan; they must not be numerically compared with Starlight results from another Minecraft version, machine or benchmark implementation.

## Bulk throughput benchmark

```text
/lightbench bulk [radius] [warmupRadius]
```

`bulk` preserves the older port's behavior: it submits one contiguous square and waits for lighting once at the end. This is useful as a queue-throughput stress test, but it is a different workload and should not be compared directly with `gen` latency. The old numeric form (`/lightbench 50 10`) remains available as an alias for bulk mode.

Other workloads are available through `/lightbench edits`, `/lightbench tps`, and `/lightbench spikes`.

## Result files

Every completed `gen`, `bulk` or `updates` run writes a versioned JSON file under the tested world's `lightbench-results` directory. The filename includes the UTC completion time, mode, detected engine and dimension. Existing files are never overwritten.

The JSON contains:

- The exact seed as a lossless string, dimension ID and provider, Lightbench version and benchmark plan.
- For `gen` and `bulk`, integer-nanosecond totals and every raw chunk batch, including its region, size, first and last chunk, `provideChunk` time, completion-barrier time and wall time.
- For `updates`, all 200 raw submission, barrier and completion timings for every phase, plus the per-sample correctness marker and the exact light probes used by the run.
- Raw-derived nearest-rank p50, p95 and p99 summaries. The offline comparator recalculates them instead of trusting the recorded summaries.
- Minecraft, Forge and MCP versions; Java VM, heap limit, GC and performance-related JVM arguments; OS, logical processor count and the platform-provided CPU identifier when available.
- Integrated/dedicated server mode and server implementation class. Update results also record measurement-window GC deltas and Pulsar worker CPU time when available.
- Terrain type, generator options, structures setting and difficulty.
- The active mod list, versions, source filenames and SHA-256 hashes for file-backed mods.
- A combined SHA-256 fingerprint of the instance's regular config files, without copying their contents into the report.

Raw observations are stored in preallocated primitive arrays while the benchmark is running. JSON construction, mod/config hashing, console summaries and disk writing begin only after all measured phases have finished, so result-file work is not included in `total-until-lit` or update completion samples.

## Comparing saved runs

Copy only the JSON files you want to compare into one directory, then run the offline comparator from the Lightbench source directory:

```powershell
.\gradlew.bat compareBenchmarks "-PbenchmarkResults=C:\Benchmarks\lightbench-results"
```

The directory is searched recursively. Individual files or multiple paths can also be supplied; quote the property when using Windows' semicolon path separator:

```powershell
.\gradlew.bat compareBenchmarks "-PbenchmarkResults=C:\Benchmarks\vanilla;C:\Benchmarks\pulsar"
```

Successful comparisons create a new timestamped directory under `build/lightbench-comparisons`. It contains:

- `comparison.csv`, with one row per run for `gen`/`bulk`, or one row per run and update phase for `updates`. Timing columns are recalculated from the raw samples and retain nanosecond units.
- `comparison.md`, with individual runs and a mode-specific engine summary. Multiple runs of the same engine are grouped using nearest-rank medians and a vanilla-relative speed ratio when Vanilla results are present.

The comparator is built from a separate offline-tool source set and is not included in the distributable Lightbench Mod JAR.

Before writing either file, the comparator validates every result independently. For `gen` and `bulk`, it recalculates totals and percentiles from the raw batch and region samples. For `updates`, it additionally verifies the fixed protocol, controlled-platform counts, engine-specific completion and light-reading adapters, phase order and positions, all 200 sample ordinals and correctness markers, each timing interval, and all three raw-derived timing distributions. It then requires exact agreement on the schema, benchmark mode and plan, seed, dimension, applicable preflight data, Lightbench and game versions, JVM and machine details, world and server settings, config fingerprint, phase layout, and every non-engine mod. A mismatch exits without creating a report and lists each condition that differed.

Measurement-window GC deltas and Pulsar worker CPU time are reported but are not compatibility conditions: they are observations that can legitimately vary between otherwise equivalent repetitions. If a run suffered GC or is an obvious outlier, retain and disclose it; collect more repetitions instead of silently deleting inconvenient samples.

The comparator also refuses a run if its config fingerprint was unavailable, a file-backed mod could not be hashed, or a mod was loaded from an unpacked development directory. Use packaged JARs for publishable measurements; otherwise exact code equality cannot be established from the report.

Pulsar and Alfheim are treated as the engine choices, so their mod entries may differ between engine groups. Repeated runs of one engine must still use exactly the same version and JAR hash. Other known-safe differences can be excluded explicitly with `-PbenchmarkIgnoreMods=id1,id2`, but doing so weakens the comparability check and should be disclosed with published results.

The config fingerprint covers the complete `config` directory. When switching engines in one instance, retain every engine's config file and keep the directory byte-for-byte identical for all runs; otherwise the comparator deliberately refuses the comparison.

## Fair-run checklist

For each engine under comparison:

1. Clone the same instance and world template. Keep Minecraft, Forge, Java, Lightbench, the world generator, configuration and non-engine mod JARs byte-for-byte identical; change only the light engine.
2. Use packaged JARs, not development directories. Retain every engine's config file in every cloned instance so the complete `config` directory has the same fingerprint.
3. Give every run the same JVM arguments, heap allocation and server mode. A dedicated server is preferable for publishable server-light results; if using an integrated server, use it for every engine and keep client activity identical. Keep the machine idle and use the same power/performance settings.
4. Restart Minecraft before each recorded repetition, let the built-in warmup finish, and do not interact with the game during measurement.
5. Collect at least three runs per engine; five or more is preferable for published graphs. Alternate the engine order between repetitions so heating, background activity and run order do not consistently favor one engine.
6. For `gen`, use a fresh world with the same fixed seed and compare only the measured `gen test`, not its warmup. Lightbench rejects an already-generated benchmark footprint, but a fresh clone also controls unrelated world state.
7. For `updates`, use equivalent disposable Superflat worlds and compare each named phase separately. Do not combine Sky increase/decrease or Block increase/decrease into one unexplained number.
8. Save the complete log and every JSON result, including outliers. Publish the raw files, hardware/JVM details, Lightbench commit or JAR hash, sample count and aggregation rule alongside graphs.

Keep the generated JSON files as the authoritative raw results. Use the comparator above before calculating additional tables or graphs. Do not mix modes in one comparison: `gen`, `bulk` and `updates` answer different questions.

Pulsar runs require a build whose global pending-light status remains true for both queued and already-dequeued worker tasks. Otherwise a completion barrier can return early and produce an invalid low time.

## License

Lightbench is licensed under LGPL-3.0. The original benchmark is credited to Spottedleaf.
