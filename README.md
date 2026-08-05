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

## Bulk throughput benchmark

```text
/lightbench bulk [radius] [warmupRadius]
```

`bulk` preserves the older port's behavior: it submits one contiguous square and waits for lighting once at the end. This is useful as a queue-throughput stress test, but it is a different workload and should not be compared directly with `gen` latency. The old numeric form (`/lightbench 50 10`) remains available as an alias for bulk mode.

Other workloads are available through `/lightbench edits`, `/lightbench tps`, and `/lightbench spikes`.

## Result files

Every completed `gen` or `bulk` run writes a versioned JSON file under the tested world's `lightbench-results` directory. The filename includes the UTC completion time, mode, detected engine and dimension. Existing files are never overwritten.

The JSON contains:

- The exact seed as a lossless string, dimension ID and provider, Lightbench version and benchmark plan.
- Integer-nanosecond totals and every raw batch sample, including its region, size, first and last chunk, `provideChunk` time, completion-barrier time and wall time.
- Region samples and nearest-rank p50, p95 and p99 summaries.
- Minecraft, Forge and MCP versions; Java VM, heap limit, GC and performance-related JVM arguments; OS, logical processor count and the platform-provided CPU identifier when available.
- Terrain type, generator options, structures setting and difficulty.
- The active mod list, versions, source filenames and SHA-256 hashes for file-backed mods.
- A combined SHA-256 fingerprint of the instance's regular config files, without copying their contents into the report.

Raw observations are stored in preallocated primitive arrays while the benchmark is running. JSON construction, mod/config hashing and disk writing begin only after all measured phases have finished, so result-file work is not included in `total-until-lit`.

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

- `comparison.csv`, with one row per run and integer-nanosecond raw-derived metrics.
- `comparison.md`, with individual runs and an engine summary. Multiple runs of the same engine are grouped, using nearest-rank medians and a vanilla-relative speed ratio when vanilla results are present.

The comparator is built from a separate offline-tool source set and is not included in the distributable Lightbench Mod JAR.

Before writing either file, the comparator independently recalculates phase totals and percentiles from the raw batch and region samples. It rejects malformed or internally inconsistent observations. It then requires exact agreement on the schema, benchmark mode and plan, raw chunk traversal, seed, dimension, preflight result, Lightbench and game versions, JVM and machine details, world settings, config fingerprint, and every non-engine mod. A mismatch exits without creating a report and lists each condition that differed.

The comparator also refuses a run if its config fingerprint was unavailable, a file-backed mod could not be hashed, or a mod was loaded from an unpacked development directory. Use packaged JARs for publishable measurements; otherwise exact code equality cannot be established from the report.

Pulsar and Alfheim are treated as the engine choices, so their mod entries may differ between engine groups. Repeated runs of one engine must still use exactly the same version and JAR hash. Other known-safe differences can be excluded explicitly with `-PbenchmarkIgnoreMods=id1,id2`, but doing so weakens the comparability check and should be disclosed with published results.

The config fingerprint covers the complete `config` directory. When switching engines in one instance, retain every engine's config file and keep the directory byte-for-byte identical for all runs; otherwise the comparator deliberately refuses the comparison.

## Fair-run checklist

For each engine under comparison:

1. Create a fresh world with the same fixed seed. Lightbench rejects generated chunks in the benchmark footprint, but a dedicated fresh world remains the clearest way to isolate every run.
2. Keep the Minecraft, Forge, Java, Lightbench, world-generator, configuration and mod-list versions identical; change only the light engine.
3. Give every run the same JVM arguments and memory allocation, and avoid other heavy work on the machine.
4. Let the built-in warmup finish. Compare the measured `gen test` result, not the warmup result.
5. Save the complete log, including the detected engine, seed, plan and all result lines.

Keep the generated JSON files as the authoritative raw results. Use the comparator above before calculating additional tables or graphs.

Pulsar runs require a build whose global pending-light status remains true for both queued and already-dequeued worker tasks. Otherwise a completion barrier can return early and produce an invalid low time.

## License

Lightbench is licensed under LGPL-3.0. The original benchmark is credited to Spottedleaf.
