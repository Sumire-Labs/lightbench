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

The most useful result is `gen test ... total-until-lit`. Batch and region percentiles show variance and stalls. For Pulsar, worker-thread CPU time is also reported as supplemental data; it can exceed wall time because multiple workers may run in parallel.

This is intentionally a very heavy synchronous run. The game can appear frozen while it generates more than 20,000 chunks in total, and a dedicated server's watchdog limit must be long enough for the command to finish.

## Bulk throughput benchmark

```text
/lightbench bulk [radius] [warmupRadius]
```

`bulk` preserves the older port's behavior: it submits one contiguous square and waits for lighting once at the end. This is useful as a queue-throughput stress test, but it is a different workload and should not be compared directly with `gen` latency. The old numeric form (`/lightbench 50 10`) remains available as an alias for bulk mode.

Other workloads are available through `/lightbench edits`, `/lightbench tps`, and `/lightbench spikes`.

## Fair-run checklist

For each engine under comparison:

1. Create a fresh world with the same fixed seed. Do not reuse a world that has already generated the benchmark coordinates.
2. Keep the Minecraft, Forge, Java, Lightbench, world-generator, configuration and mod-list versions identical; change only the light engine.
3. Give every run the same JVM arguments and memory allocation, and avoid other heavy work on the machine.
4. Let the built-in warmup finish. Compare the measured `gen test` result, not the warmup result.
5. Save the complete log, including the detected engine, seed, plan and all result lines.

Pulsar runs require a build whose global pending-light status remains true for both queued and already-dequeued worker tasks. Otherwise a completion barrier can return early and produce an invalid low time.

## License

Lightbench is licensed under LGPL-3.0. The original benchmark is credited to Spottedleaf.
