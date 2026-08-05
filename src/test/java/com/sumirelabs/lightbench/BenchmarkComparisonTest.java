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

    private static Path write(final Path target, final JsonObject result) throws Exception {
        Files.write(target, (GSON.toJson(result) + "\n").getBytes(StandardCharsets.UTF_8));
        return target;
    }
}
