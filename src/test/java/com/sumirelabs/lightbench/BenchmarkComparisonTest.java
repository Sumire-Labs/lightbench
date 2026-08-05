package com.sumirelabs.lightbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkComparisonTest {

    private static final Gson GSON =
            new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);

    @Test
    void compatibleRepeatedRunsAreGroupedAndRecalculatedFromRawSamples(@TempDir final Path temporary) throws Exception {
        final Path vanilla1 =
                write(temporary.resolve("vanilla-1.json"), result("vanilla", 1_000_000_000L, "123", SHA_A));
        final Path vanilla2 =
                write(temporary.resolve("vanilla-2.json"), result("vanilla", 1_200_000_000L, "123", SHA_A));
        final Path pulsar1 = write(temporary.resolve("pulsar-1.json"), result("pulsar", 500_000_000L, "123", SHA_A));
        final Path pulsar2 = write(temporary.resolve("pulsar-2.json"), result("pulsar", 600_000_000L, "123", SHA_A));

        final BenchmarkComparison.Result comparison = BenchmarkComparison.compare(
                Arrays.asList(vanilla1, vanilla2, pulsar1, pulsar2), Collections.emptySet());
        final String markdown = comparison.renderMarkdown();
        final String csv = comparison.renderCsv();

        assertEquals(4, comparison.runCount());
        assertTrue(markdown.contains("| vanilla | 2 | 1.000 | 1.000–1.200"));
        assertTrue(markdown.contains("| pulsar | 2 | 0.500 | 0.500–0.600"));
        assertTrue(markdown.contains("2.00x"));
        assertTrue(markdown.contains("`pulsar`"));
        assertEquals(5, csv.lines().count());
        assertTrue(csv.contains("\"pulsar-2\""));
        assertFalse(csv.contains("NaN"));
    }

    @Test
    void strictMetadataMismatchRejectsAllOutput(@TempDir final Path temporary) throws Exception {
        final Path first = write(temporary.resolve("first.json"), result("vanilla", 1_000_000_000L, "123", SHA_A));
        final Path second = write(temporary.resolve("second.json"), result("pulsar", 500_000_000L, "456", SHA_B));
        final BenchmarkComparison.IncompatibleResultsException exception = assertThrows(
                BenchmarkComparison.IncompatibleResultsException.class,
                () -> BenchmarkComparison.compare(Arrays.asList(first, second), Collections.emptySet()));

        assertTrue(exception.mismatches().stream().anyMatch(message -> message.contains("/benchmark/seed")));
        assertTrue(exception.mismatches().stream()
                .anyMatch(message -> message.contains("/environment/config_fingerprint")));

        final Path outputRoot = temporary.resolve("comparison-output");
        final ByteArrayOutputStream errors = new ByteArrayOutputStream();
        final int exitCode = BenchmarkCompare.run(
                new String[] {"--results", temporary.toString(), "--output", outputRoot.toString()},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8.name()),
                new PrintStream(errors, true, StandardCharsets.UTF_8.name()));
        assertEquals(2, exitCode);
        assertFalse(Files.exists(outputRoot));
        assertTrue(errors.toString(StandardCharsets.UTF_8.name()).contains("no report was written"));
    }

    @Test
    void nonEngineModDifferenceIsNotSilentlyIgnored(@TempDir final Path temporary) throws Exception {
        final JsonObject firstResult = result("vanilla", 1_000_000_000L, "123", SHA_A);
        final JsonObject secondResult = result("pulsar", 500_000_000L, "123", SHA_A);
        secondResult.getAsJsonArray("mods").get(0).getAsJsonObject().addProperty("version", "different");
        final Path first = write(temporary.resolve("first.json"), firstResult);
        final Path second = write(temporary.resolve("second.json"), secondResult);

        final BenchmarkComparison.IncompatibleResultsException exception = assertThrows(
                BenchmarkComparison.IncompatibleResultsException.class,
                () -> BenchmarkComparison.compare(Arrays.asList(first, second), Collections.emptySet()));
        assertTrue(exception.mismatches().stream().anyMatch(message -> message.contains("/mods/lightbench")));
    }

    @Test
    void repeatedRunsRequireTheExactSameEngineJar(@TempDir final Path temporary) throws Exception {
        final JsonObject firstResult = result("pulsar", 500_000_000L, "123", SHA_A);
        final JsonObject secondResult = result("pulsar", 600_000_000L, "123", SHA_A);
        secondResult.getAsJsonArray("mods").get(1).getAsJsonObject().addProperty("source_sha256", SHA_C);
        final Path first = write(temporary.resolve("pulsar-1.json"), firstResult);
        final Path second = write(temporary.resolve("pulsar-2.json"), secondResult);

        final BenchmarkComparison.IncompatibleResultsException exception = assertThrows(
                BenchmarkComparison.IncompatibleResultsException.class,
                () -> BenchmarkComparison.compare(Arrays.asList(first, second), Collections.emptySet()));
        assertTrue(exception.mismatches().stream().anyMatch(message -> message.contains("engine mod /mods/pulsar")));
    }

    @Test
    void rawCoordinateLayoutMustMatchAcrossRuns(@TempDir final Path temporary) throws Exception {
        final JsonObject firstResult = result("vanilla", 1_000_000_000L, "123", SHA_A);
        final JsonObject secondResult = result("pulsar", 500_000_000L, "123", SHA_A);
        secondResult
                .getAsJsonObject("benchmark")
                .getAsJsonArray("phases")
                .get(1)
                .getAsJsonObject()
                .getAsJsonArray("batches")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("first_chunk")
                .set(0, new com.google.gson.JsonPrimitive(99999));
        final Path first = write(temporary.resolve("vanilla.json"), firstResult);
        final Path second = write(temporary.resolve("pulsar.json"), secondResult);

        final BenchmarkComparison.IncompatibleResultsException exception = assertThrows(
                BenchmarkComparison.IncompatibleResultsException.class,
                () -> BenchmarkComparison.compare(Arrays.asList(first, second), Collections.emptySet()));
        assertTrue(exception.mismatches().stream().anyMatch(message -> message.contains("/first_chunk")));
    }

    @Test
    void inconsistentRawSamplesAreRejectedBeforeComparison(@TempDir final Path temporary) throws Exception {
        final JsonObject corrupt = result("vanilla", 1_000_000_000L, "123", SHA_A);
        corrupt.getAsJsonObject("benchmark")
                .getAsJsonArray("phases")
                .get(1)
                .getAsJsonObject()
                .addProperty("provide_nanos", 1);
        final Path corruptFile = write(temporary.resolve("corrupt.json"), corrupt);
        final Path validFile = write(temporary.resolve("valid.json"), result("pulsar", 500_000_000L, "123", SHA_A));

        final BenchmarkComparison.InvalidResultException exception = assertThrows(
                BenchmarkComparison.InvalidResultException.class,
                () -> BenchmarkComparison.compare(Arrays.asList(corruptFile, validFile), Collections.emptySet()));
        assertTrue(exception.getMessage().contains("sum of batch provide times"));
    }

    @Test
    void unverifiableConfigOrDirectoryBackedModsAreRejected(@TempDir final Path temporary) throws Exception {
        final JsonObject missingConfig = result("vanilla", 1_000_000_000L, "123", SHA_A);
        missingConfig
                .getAsJsonObject("environment")
                .getAsJsonObject("config_fingerprint")
                .addProperty("status", "unavailable");
        final Path missingConfigFile = write(temporary.resolve("missing-config.json"), missingConfig);
        final Path valid = write(temporary.resolve("valid.json"), result("pulsar", 500_000_000L, "123", SHA_A));

        final BenchmarkComparison.InvalidResultException configException = assertThrows(
                BenchmarkComparison.InvalidResultException.class,
                () -> BenchmarkComparison.compare(Arrays.asList(missingConfigFile, valid), Collections.emptySet()));
        assertTrue(configException.getMessage().contains("cannot establish comparable settings"));

        final JsonObject directoryMod = result("vanilla", 1_000_000_000L, "123", SHA_A);
        directoryMod.getAsJsonArray("mods").get(0).getAsJsonObject().addProperty("source_type", "directory");
        final Path directoryModFile = write(temporary.resolve("directory-mod.json"), directoryMod);
        final BenchmarkComparison.InvalidResultException modException = assertThrows(
                BenchmarkComparison.InvalidResultException.class,
                () -> BenchmarkComparison.compare(Arrays.asList(directoryModFile, valid), Collections.emptySet()));
        assertTrue(modException.getMessage().contains("benchmark packaged JARs instead"));
    }

    @Test
    void onlyBuiltinRuntimeModsMayOmitPackagedSourceMetadata(@TempDir final Path temporary) throws Exception {
        final JsonObject missingSource = result("vanilla", 1_000_000_000L, "123", SHA_A);
        final JsonObject lightbench =
                missingSource.getAsJsonArray("mods").get(0).getAsJsonObject();
        lightbench.remove("source_name");
        lightbench.remove("source_type");
        lightbench.remove("source_size_bytes");
        lightbench.remove("source_sha256");
        final Path missingSourceFile = write(temporary.resolve("missing-source.json"), missingSource);
        final Path valid = write(temporary.resolve("valid.json"), result("pulsar", 500_000_000L, "123", SHA_A));

        final BenchmarkComparison.InvalidResultException exception = assertThrows(
                BenchmarkComparison.InvalidResultException.class,
                () -> BenchmarkComparison.compare(Arrays.asList(missingSourceFile, valid), Collections.emptySet()));
        assertTrue(exception.getMessage().contains("packaged mod source metadata is required"));

        final JsonObject firstResult = result("vanilla", 1_000_000_000L, "123", SHA_A);
        final JsonObject secondResult = result("pulsar", 500_000_000L, "123", SHA_A);
        firstResult.getAsJsonArray("mods").add(runtimeMod("minecraft", "1.12.2"));
        secondResult.getAsJsonArray("mods").add(runtimeMod("minecraft", "1.12.2"));
        final Path first = write(temporary.resolve("builtin-first.json"), firstResult);
        final Path second = write(temporary.resolve("builtin-second.json"), secondResult);

        assertEquals(
                2,
                BenchmarkComparison.compare(Arrays.asList(first, second), Collections.emptySet())
                        .runCount());
    }

    @Test
    void commandDiscoversDirectoriesAndWritesAUniqueReportPair(@TempDir final Path temporary) throws Exception {
        final Path nested = temporary.resolve("results").resolve("worlds");
        Files.createDirectories(nested);
        write(nested.resolve("vanilla.json"), result("vanilla", 1_000_000_000L, "123", SHA_A));
        write(nested.resolve("pulsar.json"), result("pulsar", 500_000_000L, "123", SHA_A));
        final Path outputRoot = temporary.resolve("reports");
        final ByteArrayOutputStream messages = new ByteArrayOutputStream();

        final int exitCode = BenchmarkCompare.run(
                new String[] {"--results", temporary.resolve("results").toString(), "--output", outputRoot.toString()},
                new PrintStream(messages, true, StandardCharsets.UTF_8.name()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8.name()));

        assertEquals(0, exitCode);
        try (java.util.stream.Stream<Path> reports = Files.list(outputRoot)) {
            final Path directory = reports.findFirst().orElseThrow(AssertionError::new);
            assertTrue(Files.isRegularFile(directory.resolve("comparison.md")));
            assertTrue(Files.isRegularFile(directory.resolve("comparison.csv")));
        }
        assertTrue(messages.toString(StandardCharsets.UTF_8.name()).contains("Compatibility checks passed for 2 runs"));
    }

    @Test
    void commandAcceptsPlatformSeparatedAbsoluteResultPaths(@TempDir final Path temporary) throws Exception {
        final Path first = write(temporary.resolve("vanilla.json"), result("vanilla", 1_000_000_000L, "123", SHA_A));
        final Path second = write(temporary.resolve("pulsar.json"), result("pulsar", 500_000_000L, "123", SHA_A));
        final Path outputRoot = temporary.resolve("reports");

        final int exitCode = BenchmarkCompare.run(
                new String[] {
                    "--results",
                    first.toAbsolutePath() + File.pathSeparator + second.toAbsolutePath(),
                    "--output",
                    outputRoot.toString()
                },
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8.name()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8.name()));

        assertEquals(0, exitCode);
    }

    @Test
    void compatibleUpdateRunsProducePhaseRowsAndEngineSummaries(@TempDir final Path temporary) throws Exception {
        final Path vanilla = write(temporary.resolve("updates-vanilla.json"), updateResult("vanilla", 1_000_000L));
        final Path alfheim = write(temporary.resolve("updates-alfheim.json"), updateResult("alfheim", 700_000L));
        final Path pulsar = write(temporary.resolve("updates-pulsar.json"), updateResult("pulsar", 500_000L));

        final BenchmarkComparison.Result comparison =
                BenchmarkComparison.compare(Arrays.asList(vanilla, alfheim, pulsar), Collections.emptySet());
        final String markdown = comparison.renderMarkdown();
        final String csv = comparison.renderCsv();

        assertEquals(3, comparison.runCount());
        assertEquals(13, csv.lines().count());
        assertTrue(csv.startsWith("run,source_file,engine,started_at_utc,mode,seed,dimension_id,phase"));
        assertTrue(csv.contains("completion_p50_nanos"));
        assertTrue(csv.contains("\"sky_remove\""));
        assertTrue(csv.contains("\"block_remove\""));
        assertTrue(markdown.contains("# Lightbench light-update comparison"));
        assertTrue(markdown.contains("### `sky_remove`"));
        assertTrue(markdown.contains("### `block_place`"));
        assertTrue(markdown.contains("Completion is the primary cross-engine metric"));
        assertFalse(markdown.contains("NaN"));
    }

    @Test
    void updateSummaryIsRecalculatedFromRawSamples(@TempDir final Path temporary) throws Exception {
        final JsonObject corrupt = updateResult("vanilla", 1_000_000L);
        updatePhase(corrupt, 0).getAsJsonObject("completion_summary_nanos").addProperty("p95", 1);

        final BenchmarkComparison.InvalidResultException exception = assertThrows(
                BenchmarkComparison.InvalidResultException.class,
                () -> BenchmarkComparison.compare(
                        Arrays.asList(
                                write(temporary.resolve("corrupt.json"), corrupt),
                                write(temporary.resolve("valid.json"), updateResult("pulsar", 500_000L))),
                        Collections.emptySet()));
        assertTrue(exception.getMessage().contains("completion_summary_nanos/p95"));
        assertTrue(exception.getMessage().contains("raw p95"));
    }

    @Test
    void unverifiedUpdateSampleIsRejected(@TempDir final Path temporary) throws Exception {
        final JsonObject corrupt = updateResult("vanilla", 1_000_000L);
        updatePhase(corrupt, 1)
                .getAsJsonArray("samples")
                .get(17)
                .getAsJsonObject()
                .addProperty("light_verified", false);

        final BenchmarkComparison.InvalidResultException exception = assertThrows(
                BenchmarkComparison.InvalidResultException.class,
                () -> BenchmarkComparison.compare(
                        Arrays.asList(
                                write(temporary.resolve("corrupt.json"), corrupt),
                                write(temporary.resolve("valid.json"), updateResult("pulsar", 500_000L))),
                        Collections.emptySet()));
        assertTrue(exception.getMessage().contains("light_verified"));
    }

    @Test
    void updateCompletionMustContainSubmissionAndBarrierIntervals(@TempDir final Path temporary) throws Exception {
        final JsonObject corrupt = updateResult("pulsar", 500_000L);
        updatePhase(corrupt, 2)
                .getAsJsonArray("samples")
                .get(0)
                .getAsJsonObject()
                .addProperty("completion_nanos", 1);

        final BenchmarkComparison.InvalidResultException exception = assertThrows(
                BenchmarkComparison.InvalidResultException.class,
                () -> BenchmarkComparison.compare(
                        Arrays.asList(
                                write(temporary.resolve("corrupt.json"), corrupt),
                                write(temporary.resolve("valid.json"), updateResult("vanilla", 1_000_000L))),
                        Collections.emptySet()));
        assertTrue(exception.getMessage().contains("submission_nanos + barrier_nanos"));
    }

    @Test
    void updateAdapterMustMatchTheDetectedEngine(@TempDir final Path temporary) throws Exception {
        final JsonObject corrupt = updateResult("pulsar", 500_000L);
        corrupt.getAsJsonObject("benchmark").addProperty("completion_adapter", "pulsar_global_pending_poll");

        final BenchmarkComparison.InvalidResultException exception = assertThrows(
                BenchmarkComparison.InvalidResultException.class,
                () -> BenchmarkComparison.compare(
                        Arrays.asList(
                                write(temporary.resolve("corrupt.json"), corrupt),
                                write(temporary.resolve("valid.json"), updateResult("vanilla", 1_000_000L))),
                        Collections.emptySet()));
        assertTrue(exception.getMessage().contains("pulsar_chunk_future_then_global_pending"));
    }

    @Test
    void updateEngineSpecificMeasurementsAndNullablePairsAreValidated(@TempDir final Path temporary) throws Exception {
        final JsonObject vanillaBarrier = updateResult("vanilla", 1_000_000L);
        updatePhase(vanillaBarrier, 0)
                .getAsJsonArray("samples")
                .get(0)
                .getAsJsonObject()
                .addProperty("barrier_nanos", 1);
        final BenchmarkComparison.InvalidResultException barrierException = assertThrows(
                BenchmarkComparison.InvalidResultException.class,
                () -> BenchmarkComparison.compare(
                        Arrays.asList(
                                write(temporary.resolve("vanilla-barrier.json"), vanillaBarrier),
                                write(temporary.resolve("valid-pulsar.json"), updateResult("pulsar", 500_000L))),
                        Collections.emptySet()));
        assertTrue(barrierException.getMessage().contains("vanilla_inline"));

        final JsonObject alfheimWorker = updateResult("alfheim", 700_000L);
        alfheimWorker.getAsJsonObject("benchmark").addProperty("pulsar_worker_cpu_nanos", 1);
        final BenchmarkComparison.InvalidResultException workerException = assertThrows(
                BenchmarkComparison.InvalidResultException.class,
                () -> BenchmarkComparison.compare(
                        Arrays.asList(
                                write(temporary.resolve("alfheim-worker.json"), alfheimWorker),
                                write(temporary.resolve("valid-vanilla.json"), updateResult("vanilla", 1_000_000L))),
                        Collections.emptySet()));
        assertTrue(workerException.getMessage().contains("engines other than Pulsar"));

        final JsonObject partialGc = updateResult("pulsar", 500_000L);
        partialGc
                .getAsJsonObject("benchmark")
                .getAsJsonObject("measurement_gc")
                .add("collection_time_millis_delta", com.google.gson.JsonNull.INSTANCE);
        final BenchmarkComparison.InvalidResultException gcException = assertThrows(
                BenchmarkComparison.InvalidResultException.class,
                () -> BenchmarkComparison.compare(
                        Arrays.asList(
                                write(temporary.resolve("partial-gc.json"), partialGc),
                                write(temporary.resolve("another-vanilla.json"), updateResult("vanilla", 1_000_000L))),
                        Collections.emptySet()));
        assertTrue(gcException.getMessage().contains("either both be integers or both be null"));
    }

    @Test
    void updateCorrectnessProbePlanMustMatchTheFixedProtocol(@TempDir final Path temporary) throws Exception {
        final JsonObject corrupt = updateResult("vanilla", 1_000_000L);
        corrupt.getAsJsonObject("benchmark")
                .getAsJsonObject("plan")
                .getAsJsonArray("workloads")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("open_expected_light")
                .get(3)
                .getAsJsonObject()
                .addProperty("level", 2);

        final BenchmarkComparison.InvalidResultException exception = assertThrows(
                BenchmarkComparison.InvalidResultException.class,
                () -> BenchmarkComparison.compare(
                        Arrays.asList(
                                write(temporary.resolve("corrupt-plan.json"), corrupt),
                                write(temporary.resolve("valid.json"), updateResult("pulsar", 500_000L))),
                        Collections.emptySet()));
        assertTrue(exception.getMessage().contains("fixed expected light level"));
    }

    @Test
    void updatePreflightMustCoverTheEntireControlledPlatform(@TempDir final Path temporary) throws Exception {
        final JsonObject corrupt = updateResult("vanilla", 1_000_000L);
        corrupt.getAsJsonObject("benchmark").getAsJsonObject("preflight").addProperty("checked_air_blocks", 1);

        final BenchmarkComparison.InvalidResultException exception = assertThrows(
                BenchmarkComparison.InvalidResultException.class,
                () -> BenchmarkComparison.compare(
                        Arrays.asList(
                                write(temporary.resolve("corrupt.json"), corrupt),
                                write(temporary.resolve("valid.json"), updateResult("pulsar", 500_000L))),
                        Collections.emptySet()));
        assertTrue(exception.getMessage().contains("every clear-air block"));
    }

    @Test
    void updateServerModeMustMatchButGcObservationsMayDiffer(@TempDir final Path temporary) throws Exception {
        final JsonObject vanillaResult = updateResult("vanilla", 1_000_000L);
        final JsonObject pulsarResult = updateResult("pulsar", 500_000L);
        final JsonObject measurementGc =
                pulsarResult.getAsJsonObject("benchmark").getAsJsonObject("measurement_gc");
        measurementGc.addProperty("collection_count_delta", 3);
        measurementGc.addProperty("collection_time_millis_delta", 8);
        assertEquals(
                2,
                BenchmarkComparison.compare(
                                Arrays.asList(
                                        write(temporary.resolve("vanilla.json"), vanillaResult),
                                        write(temporary.resolve("pulsar.json"), pulsarResult)),
                                Collections.emptySet())
                        .runCount());

        final JsonObject integratedResult = updateResult("pulsar", 500_000L);
        integratedResult
                .getAsJsonObject("environment")
                .getAsJsonObject("server")
                .addProperty("dedicated", false);
        final BenchmarkComparison.IncompatibleResultsException exception = assertThrows(
                BenchmarkComparison.IncompatibleResultsException.class,
                () -> BenchmarkComparison.compare(
                        Arrays.asList(
                                write(temporary.resolve("dedicated.json"), updateResult("vanilla", 1_000_000L)),
                                write(temporary.resolve("integrated.json"), integratedResult)),
                        Collections.emptySet()));
        assertTrue(exception.mismatches().stream().anyMatch(message -> message.contains("/environment/server")));
    }

    private static JsonObject result(
            final String engine, final long testTotalNanos, final String seed, final String configHash) {
        final JsonObject root = new JsonObject();
        root.addProperty("schema_version", 1);

        final JsonObject benchmark = new JsonObject();
        benchmark.addProperty("mode", "gen");
        benchmark.addProperty("started_at_utc", "2026-08-05T00:00:00Z");
        benchmark.addProperty("completed_at_utc", "2026-08-05T00:00:01Z");
        benchmark.addProperty("lightbench_version", "test");
        benchmark.addProperty("engine", engine);
        benchmark.addProperty("seed", seed);
        benchmark.addProperty("time_unit", "nanoseconds");
        benchmark.addProperty("reporting_excluded_from_measurements", true);

        final JsonObject dimension = new JsonObject();
        dimension.addProperty("id", 0);
        dimension.addProperty("name", "overworld");
        dimension.addProperty("provider_class", "test.WorldProvider");
        dimension.addProperty("has_sky_light", true);
        benchmark.add("dimension", dimension);

        final JsonObject preflight = new JsonObject();
        preflight.addProperty("halo_chunks", 1);
        preflight.addProperty("checked_chunks", 25);
        preflight.addProperty("elapsed_nanos", 100);
        preflight.addProperty("all_ungenerated", true);
        benchmark.add("preflight", preflight);

        final JsonObject plan = new JsonObject();
        plan.addProperty("coordinate_unit", "chunk");
        plan.addProperty("completion_barrier", "after_each_batch");
        final JsonObject warmupPlan = new JsonObject();
        warmupPlan.addProperty("center_x", -10000);
        warmupPlan.addProperty("center_z", -10000);
        warmupPlan.addProperty("radius", 1);
        warmupPlan.addProperty("chunk_count", 9);
        plan.add("warmup", warmupPlan);
        final JsonObject testPlan = new JsonObject();
        testPlan.addProperty("first_center_x", 10000);
        testPlan.addProperty("first_center_z", 10000);
        testPlan.addProperty("region_radius", 1);
        testPlan.addProperty("region_count", 1);
        testPlan.addProperty("region_stride_x", 4);
        testPlan.addProperty("region_stride_z", 4);
        testPlan.addProperty("chunks_per_region", 9);
        testPlan.addProperty("chunk_count", 9);
        testPlan.addProperty("batch_limit", 5);
        testPlan.addProperty("traversal", "original_lightbench_center_out_rings");
        plan.add("test", testPlan);
        benchmark.add("plan", plan);

        final JsonArray phases = new JsonArray();
        phases.add(phase("gen warmup", 900_000_000L, engine));
        phases.add(phase("gen test", testTotalNanos, engine));
        benchmark.add("phases", phases);
        root.add("benchmark", benchmark);

        final JsonObject environment = new JsonObject();
        environment.addProperty("minecraft_version", "1.12.2");
        environment.addProperty("forge_version", "14.23.5.2860");
        environment.addProperty("mcp_version", "9.42");
        final JsonObject java = new JsonObject();
        java.addProperty("version", "25.0.2");
        java.addProperty("vendor", "test");
        java.addProperty("vm_name", "Test VM");
        java.addProperty("vm_version", "25.0.2");
        java.addProperty("max_heap_bytes", 4_000_000_000L);
        java.addProperty("logical_processors", 8);
        java.add("performance_arguments", new JsonArray());
        final JsonArray collectors = new JsonArray();
        collectors.add("Test GC");
        java.add("garbage_collectors", collectors);
        environment.add("java", java);
        final JsonObject operatingSystem = new JsonObject();
        operatingSystem.addProperty("name", "Test OS");
        operatingSystem.addProperty("version", "1");
        operatingSystem.addProperty("arch", "amd64");
        operatingSystem.addProperty("processor_identifier", "Test CPU");
        environment.add("operating_system", operatingSystem);
        final JsonObject worldSettings = new JsonObject();
        worldSettings.addProperty("terrain_type", "default");
        worldSettings.addProperty("generator_options", "");
        worldSettings.addProperty("map_features", true);
        worldSettings.addProperty("difficulty", "normal");
        environment.add("world_settings", worldSettings);
        final JsonObject configFingerprint = new JsonObject();
        configFingerprint.addProperty("status", "ok");
        configFingerprint.addProperty("file_count", 10);
        configFingerprint.addProperty("sha256", configHash);
        environment.add("config_fingerprint", configFingerprint);
        root.add("environment", environment);

        final JsonArray mods = new JsonArray();
        mods.add(mod("lightbench", "test", "lightbench.jar"));
        if (!"vanilla".equals(engine)) {
            mods.add(mod(engine, "test", engine + ".jar"));
        }
        root.add("mods", mods);
        return root;
    }

    private static JsonObject updateResult(final String engine, final long timingBase) {
        final JsonObject root = result(engine, 1_000_000_000L, "123", SHA_A);
        final JsonObject benchmark = new JsonObject();
        benchmark.addProperty("mode", "updates");
        benchmark.addProperty("started_at_utc", "2026-08-05T00:00:00Z");
        benchmark.addProperty("completed_at_utc", "2026-08-05T00:00:01Z");
        benchmark.addProperty("lightbench_version", "test");
        benchmark.addProperty("engine", engine);
        benchmark.addProperty("seed", "123");
        benchmark.addProperty("time_unit", "nanoseconds");
        benchmark.addProperty("reporting_excluded_from_measurements", true);
        if ("pulsar".equals(engine)) {
            benchmark.addProperty("completion_adapter", "pulsar_chunk_future_then_global_pending");
            benchmark.addProperty("verification_reader", "world_stored_light");
        } else if ("alfheim".equals(engine)) {
            benchmark.addProperty("completion_adapter", "alfheim_process_light_updates");
            benchmark.addProperty("verification_reader", "alfheim_cached_light");
        } else {
            benchmark.addProperty("completion_adapter", "vanilla_inline");
            benchmark.addProperty("verification_reader", "world_stored_light");
        }

        final JsonObject dimension = new JsonObject();
        dimension.addProperty("id", 0);
        dimension.addProperty("name", "overworld");
        dimension.addProperty("provider_class", "test.WorldProvider");
        dimension.addProperty("has_sky_light", true);
        benchmark.add("dimension", dimension);

        final JsonObject preflight = new JsonObject();
        preflight.addProperty("controlled_environment", true);
        preflight.addProperty("checked_columns", 4096);
        preflight.addProperty("checked_air_blocks", 1_024_000L);
        preflight.addProperty("loaded_chunks", 49);
        preflight.addProperty("floor_y", 3);
        preflight.addProperty("floor_block", "minecraft:grass");
        preflight.addProperty("floor_state", "minecraft:grass[snowy=false]");
        preflight.addProperty("floor_meta", 0);
        preflight.addProperty("platform_normalized", true);
        preflight.addProperty("initial_light_verified", true);
        benchmark.add("preflight", preflight);
        benchmark.add("plan", updatePlan());

        final JsonObject measurementGc = new JsonObject();
        measurementGc.addProperty("collection_count_delta", 0);
        measurementGc.addProperty("collection_time_millis_delta", 0);
        benchmark.add("measurement_gc", measurementGc);
        if ("pulsar".equals(engine)) {
            benchmark.addProperty("pulsar_worker_cpu_nanos", timingBase * 100L);
        } else {
            benchmark.add("pulsar_worker_cpu_nanos", com.google.gson.JsonNull.INSTANCE);
        }

        final JsonArray phases = new JsonArray();
        phases.add(updatePhase("sky_remove", "sky", "remove", 20008, 254, 20008, timingBase, engine));
        phases.add(updatePhase("sky_place", "sky", "place", 20008, 254, 20008, timingBase * 2L, engine));
        phases.add(updatePhase("block_place", "block", "place", 20008, 4, 20008, timingBase * 3L, engine));
        phases.add(updatePhase("block_remove", "block", "remove", 20008, 4, 20008, timingBase * 4L, engine));
        benchmark.add("phases", phases);
        root.add("benchmark", benchmark);

        final JsonObject environment = root.getAsJsonObject("environment");
        final JsonObject worldSettings = environment.getAsJsonObject("world_settings");
        worldSettings.addProperty("terrain_type", "flat");
        worldSettings.addProperty(
                "generator_options", "3;minecraft:bedrock,2*minecraft:dirt,minecraft:grass;1;village");
        final JsonObject server = new JsonObject();
        server.addProperty("dedicated", true);
        server.addProperty("implementation_class", "test.MinecraftServer");
        environment.add("server", server);
        return root;
    }

    private static JsonObject updatePlan() {
        final JsonObject plan = new JsonObject();
        plan.addProperty("coordinate_unit", "block");
        plan.addProperty("logical_side", "server");
        plan.addProperty("measurement_scope", "block_state_change_and_server_light_completion");
        plan.addProperty("primary_metric", "completion_nanos");
        plan.addProperty("timed_interval", "before_set_block_state_to_after_completion_barrier");
        plan.addProperty("submission_interval", "before_to_after_set_block_state");
        plan.addProperty("barrier_interval", "engine_specific_completion_wait");
        plan.addProperty("completion_barrier", "after_each_edit");
        plan.addProperty("update_flags", 16);
        plan.addProperty("warmup_pairs", 20);
        plan.addProperty("measured_pairs", 200);
        plan.addProperty("same_position_each_sample", true);
        plan.addProperty("validation", "after_each_completion_outside_timed_interval");

        final JsonObject platform = new JsonObject();
        platform.addProperty("base_x", 19976);
        platform.addProperty("base_z", 19976);
        platform.addProperty("size_x", 64);
        platform.addProperty("size_z", 64);
        platform.addProperty("top_y", 254);
        platform.addProperty("block", "minecraft:stone");
        platform.addProperty("loaded_chunk_halo", 1);
        platform.addProperty("minimum_clear_height", 240);
        platform.addProperty("minimum_sample_edge_margin", 16);
        plan.add("platform", platform);

        final JsonObject floor = new JsonObject();
        floor.addProperty("y", 3);
        floor.addProperty("block", "minecraft:grass");
        floor.addProperty("state", "minecraft:grass[snowy=false]");
        floor.addProperty("meta", 0);
        plan.add("floor", floor);

        final JsonArray workloads = new JsonArray();
        final JsonObject sky = new JsonObject();
        sky.addProperty("light_type", "sky");
        sky.addProperty("baseline_block", "minecraft:stone");
        sky.addProperty("changed_block", "minecraft:air");
        sky.add("phase_order", stringArray("sky_remove", "sky_place"));
        sky.add("warmup_position", coordinate(19992, 254, 19992));
        sky.add("measured_position", coordinate(20008, 254, 20008));
        final JsonArray openExpected = new JsonArray();
        openExpected.add(expectation(20008, 253, 20008, 15));
        openExpected.add(expectation(20008, 128, 20008, 15));
        openExpected.add(expectation(20008, 4, 20008, 15));
        openExpected.add(expectation(20022, 4, 20008, 1));
        openExpected.add(expectation(20023, 4, 20008, 0));
        sky.add("open_expected_light", openExpected);
        final JsonArray closedExpected = new JsonArray();
        closedExpected.add(expectation(20008, 253, 20008, 0));
        closedExpected.add(expectation(20008, 128, 20008, 0));
        closedExpected.add(expectation(20008, 4, 20008, 0));
        closedExpected.add(expectation(20022, 4, 20008, 0));
        closedExpected.add(expectation(20023, 4, 20008, 0));
        sky.add("closed_expected_light", closedExpected);
        workloads.add(sky);

        final JsonObject block = new JsonObject();
        block.addProperty("light_type", "block");
        block.addProperty("baseline_block", "minecraft:air");
        block.addProperty("changed_block", "minecraft:glowstone");
        block.add("phase_order", stringArray("block_place", "block_remove"));
        block.add("warmup_position", coordinate(19992, 4, 19992));
        block.add("measured_position", coordinate(20008, 4, 20008));
        final JsonArray presentExpected = new JsonArray();
        presentExpected.add(expectation(20008, 4, 20008, 15));
        presentExpected.add(expectation(20022, 4, 20008, 1));
        presentExpected.add(expectation(20023, 4, 20008, 0));
        block.add("present_expected_light", presentExpected);
        final JsonArray absentExpected = new JsonArray();
        absentExpected.add(expectation(20008, 4, 20008, 0));
        absentExpected.add(expectation(20022, 4, 20008, 0));
        absentExpected.add(expectation(20023, 4, 20008, 0));
        block.add("absent_expected_light", absentExpected);
        workloads.add(block);
        plan.add("workloads", workloads);
        return plan;
    }

    private static JsonObject updatePhase(
            final String name,
            final String lightType,
            final String action,
            final int x,
            final int y,
            final int z,
            final long timingBase,
            final String engine) {
        final long[] submission = new long[200];
        final long[] barrier = new long[200];
        final long[] completion = new long[200];
        for (int index = 0; index < completion.length; ++index) {
            submission[index] = timingBase / 10L + index;
            barrier[index] = "vanilla".equals(engine) ? 0 : timingBase / 2L + index;
            completion[index] = submission[index] + barrier[index] + timingBase + index;
        }
        return BenchmarkReport.updatePhaseToJson(
                new UpdatePhaseResult(name, lightType, action, x, y, z, submission, barrier, completion));
    }

    private static JsonObject updatePhase(final JsonObject result, final int phaseIndex) {
        return result.getAsJsonObject("benchmark")
                .getAsJsonArray("phases")
                .get(phaseIndex)
                .getAsJsonObject();
    }

    private static JsonArray coordinate(final int x, final int y, final int z) {
        final JsonArray coordinate = new JsonArray();
        coordinate.add(x);
        coordinate.add(y);
        coordinate.add(z);
        return coordinate;
    }

    private static JsonArray stringArray(final String first, final String second) {
        final JsonArray result = new JsonArray();
        result.add(first);
        result.add(second);
        return result;
    }

    private static JsonObject expectation(final int x, final int y, final int z, final int level) {
        final JsonObject result = new JsonObject();
        result.add("position", coordinate(x, y, z));
        result.addProperty("level", level);
        return result;
    }

    private static JsonObject phase(final String name, final long totalNanos, final String engine) {
        final long firstProvide = totalNanos / 20;
        final long secondProvide = totalNanos / 15;
        final long firstBarrier = totalNanos / 30;
        final long secondBarrier = totalNanos / 25;
        final long firstWall = totalNanos / 4;
        final long secondWall = totalNanos / 3;
        final long provide = firstProvide + secondProvide;
        final long barrier = firstBarrier + secondBarrier;
        return BenchmarkReport.phaseToJson(new BenchmarkPhaseResult(
                name,
                9,
                5,
                provide,
                barrier,
                totalNanos,
                "pulsar".equals(engine) ? totalNanos / 2 : -1,
                new BenchmarkPhaseResult.BatchSamples(
                        new int[] {0, 0},
                        new int[] {0, 5},
                        new int[] {5, 4},
                        new int[] {10000, 10001},
                        new int[] {10000, 10002},
                        new int[] {10001, 10002},
                        new int[] {10001, 10002},
                        new long[] {firstProvide, secondProvide},
                        new long[] {firstBarrier, secondBarrier},
                        new long[] {firstWall, secondWall}),
                new BenchmarkPhaseResult.RegionSamples(new int[] {9}, new int[] {2}, new long[] {totalNanos})));
    }

    private static JsonObject mod(final String id, final String version, final String sourceName) {
        final JsonObject mod = new JsonObject();
        mod.addProperty("id", id);
        mod.addProperty("name", id);
        mod.addProperty("version", version);
        mod.addProperty("source_name", sourceName);
        mod.addProperty("source_type", "file");
        mod.addProperty("source_size_bytes", 100);
        mod.addProperty("source_sha256", SHA_A);
        return mod;
    }

    private static JsonObject runtimeMod(final String id, final String version) {
        final JsonObject mod = new JsonObject();
        mod.addProperty("id", id);
        mod.addProperty("name", id);
        mod.addProperty("version", version);
        return mod;
    }

    private static Path write(final Path target, final JsonObject result) throws Exception {
        Files.write(target, (GSON.toJson(result) + "\n").getBytes(StandardCharsets.UTF_8));
        return target;
    }
}
