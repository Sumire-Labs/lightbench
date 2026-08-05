package com.sumirelabs.lightbench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import net.minecraftforge.common.ForgeVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkReportTest {

    @Test
    void configFingerprintIgnoresRuntimeTimingsAndCanonicalizesSplashComments(@TempDir final Path temporary)
            throws Exception {
        assertTrue(BenchmarkReport.isVolatileConfigArtifact("cleanroom_load_timings.dat"));
        assertFalse(BenchmarkReport.isVolatileConfigArtifact("pulsar.cfg"));
        assertTrue(BenchmarkReport.isCanonicalPropertiesFile("splash.properties"));

        final Path first = temporary.resolve("first.properties");
        final Path second = temporary.resolve("second.properties");
        final Path changed = temporary.resolve("changed.properties");
        Files.write(
                first,
                Arrays.asList("#Thu Aug 06 04:51:21 JST 2026", "enabled=true", "background=0xFFFFFF"),
                StandardCharsets.ISO_8859_1);
        Files.write(
                second,
                Arrays.asList("#Thu Aug 06 04:56:47 JST 2026", "background=0xFFFFFF", "enabled=true"),
                StandardCharsets.ISO_8859_1);
        Files.write(
                changed,
                Arrays.asList("#Thu Aug 06 04:56:47 JST 2026", "background=0xFFFFFF", "enabled=false"),
                StandardCharsets.ISO_8859_1);

        assertArrayEquals(BenchmarkReport.canonicalProperties(first), BenchmarkReport.canonicalProperties(second));
        assertFalse(Arrays.equals(
                BenchmarkReport.canonicalProperties(first), BenchmarkReport.canonicalProperties(changed)));
    }

    @Test
    void serializerKeepsExplicitNullSchemaFields() {
        final JsonObject result = new JsonObject();
        result.add("optional_measurement", JsonNull.INSTANCE);

        final String json = new String(BenchmarkReport.serializeJson(result), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"optional_measurement\": null"));
    }

    @Test
    void phaseJsonKeepsIntegerRawSamplesCoordinatesAndDistribution() {
        final BenchmarkPhaseResult phase = new BenchmarkPhaseResult(
                "gen test",
                9,
                5,
                220,
                50,
                310,
                42,
                new BenchmarkPhaseResult.BatchSamples(
                        new int[] {0, 0},
                        new int[] {0, 5},
                        new int[] {5, 4},
                        new int[] {10000, 10001},
                        new int[] {10000, 10002},
                        new int[] {10001, 10002},
                        new int[] {10001, 10002},
                        new long[] {70, 150},
                        new long[] {20, 30},
                        new long[] {100, 200}),
                new BenchmarkPhaseResult.RegionSamples(new int[] {9}, new int[] {2}, new long[] {310}));

        final JsonObject json = BenchmarkReport.phaseToJson(phase);
        assertEquals(310, json.get("total_until_lit_nanos").getAsLong());
        assertEquals(42, json.get("pulsar_worker_cpu_nanos").getAsLong());

        final JsonObject distribution = json.getAsJsonObject("batch_wall_summary_nanos");
        assertEquals(100, distribution.get("minimum").getAsLong());
        assertEquals(150.0, distribution.get("average").getAsDouble());
        assertEquals(100, distribution.get("p50").getAsLong());
        assertEquals(200, distribution.get("p95").getAsLong());
        assertEquals(200, distribution.get("p99").getAsLong());

        final JsonArray batches = json.getAsJsonArray("batches");
        assertEquals(2, batches.size());
        assertEquals(4, batches.get(1).getAsJsonObject().get("chunk_count").getAsInt());
        assertEquals(
                10001,
                batches.get(1)
                        .getAsJsonObject()
                        .getAsJsonArray("first_chunk")
                        .get(0)
                        .getAsInt());
        assertEquals(30, batches.get(1).getAsJsonObject().get("barrier_nanos").getAsLong());
    }

    @Test
    void rawSampleColumnsMustHaveMatchingLengths() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BenchmarkPhaseResult.BatchSamples(
                        new int[] {0},
                        new int[] {0},
                        new int[] {5},
                        new int[] {0},
                        new int[] {0},
                        new int[] {1},
                        new int[] {1},
                        new long[] {10},
                        new long[] {20},
                        new long[0]));
    }

    @Test
    void updatePhaseJsonKeepsEveryRawTimingAndVerificationMarker() {
        final UpdatePhaseResult phase = new UpdatePhaseResult(
                "sky_remove",
                "sky",
                "remove",
                20008,
                254,
                20008,
                new long[] {10, 20},
                new long[] {90, 180},
                new long[] {110, 220});

        final JsonObject json = BenchmarkReport.updatePhaseToJson(phase);
        assertEquals(2, json.get("sample_count").getAsInt());
        assertTrue(json.get("all_samples_verified").getAsBoolean());
        assertEquals(254, json.getAsJsonArray("position").get(1).getAsInt());
        assertEquals(
                15.0,
                json.getAsJsonObject("submission_summary_nanos").get("average").getAsDouble());
        assertEquals(
                220, json.getAsJsonObject("completion_summary_nanos").get("p99").getAsLong());

        final JsonObject second = json.getAsJsonArray("samples").get(1).getAsJsonObject();
        assertEquals(1, second.get("ordinal").getAsInt());
        assertEquals(20, second.get("submission_nanos").getAsLong());
        assertEquals(180, second.get("barrier_nanos").getAsLong());
        assertEquals(220, second.get("completion_nanos").getAsLong());
        assertTrue(second.get("light_verified").getAsBoolean());
    }

    @Test
    void updateSampleColumnsMustHaveMatchingNonEmptyLengths() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new UpdatePhaseResult(
                        "block_place", "block", "place", 0, 1, 0, new long[] {1}, new long[0], new long[] {2}));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UpdatePhaseResult(
                        "block_place", "block", "place", 0, 1, 0, new long[0], new long[0], new long[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UpdatePhaseResult(
                        "block_place", "block", "place", 0, 1, 0, new long[] {3}, new long[] {9}, new long[] {8}));
    }

    @Test
    void fixedUpdateSamplesHaveDisjointLightFootprintsAndStayInsidePlatform() {
        final int baseX = UpdateBenchmark.CENTER_X - UpdateBenchmark.PLATFORM_SIZE / 2;
        final int baseZ = UpdateBenchmark.CENTER_Z - UpdateBenchmark.PLATFORM_SIZE / 2;

        assertEquals(
                31,
                UpdateBenchmark.minimumEdgeMargin(
                        UpdateBenchmark.CENTER_X,
                        UpdateBenchmark.CENTER_Z,
                        baseX,
                        baseZ,
                        UpdateBenchmark.PLATFORM_SIZE));
        assertEquals(
                16,
                UpdateBenchmark.minimumEdgeMargin(
                        UpdateBenchmark.CENTER_X + UpdateBenchmark.WARMUP_OFFSET,
                        UpdateBenchmark.CENTER_Z + UpdateBenchmark.WARMUP_OFFSET,
                        baseX,
                        baseZ,
                        UpdateBenchmark.PLATFORM_SIZE));
        assertTrue(Math.abs(UpdateBenchmark.WARMUP_OFFSET) * 2 > 28);
    }

    @Test
    void resultWriterPublishesCompleteFilesWithoutOverwritingEarlierRuns(@TempDir final Path temporary)
            throws Exception {
        final Path directory = temporary.resolve("results");
        final byte[] firstJson = "{\"run\":1}\n".getBytes(StandardCharsets.UTF_8);
        final byte[] secondJson = "{\"run\":2}\n".getBytes(StandardCharsets.UTF_8);

        final Path first = BenchmarkReport.writeUniqueJson(directory, "same-run", firstJson);
        final Path second = BenchmarkReport.writeUniqueJson(directory, "same-run", secondJson);

        assertEquals("same-run.json", first.getFileName().toString());
        assertEquals("same-run-1.json", second.getFileName().toString());
        assertArrayEquals(firstJson, Files.readAllBytes(first));
        assertArrayEquals(secondJson, Files.readAllBytes(second));
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void packagedModSourcesAreResolvedFromForgeModDirectories(@TempDir final Path temporary) throws Exception {
        final Path scalar = temporary.resolve("mods").resolve("Scalar Legacy.jar");
        final Path relauncher =
                temporary.resolve("mods").resolve(ForgeVersion.mcVersion).resolve("cleanroom-relauncher.jar");
        writeModJar(scalar, "[{\"modid\":\"scalar\"}]");
        writeModJar(relauncher, "{\"modList\":[{\"modid\":\"cleanroom-relauncher\"}]}");
        writeModJar(temporary.resolve("mods").resolve("ignored.jar.disabled"), "[{\"modid\":\"ignored\"}]");
        writeModJar(temporary.resolve("mods").resolve("memory_repo").resolve("nested.jar"), "[{\"modid\":\"nested\"}]");

        final Map<String, Path> sources = BenchmarkReport.findPackagedModSources(temporary);

        assertEquals(scalar.toAbsolutePath().normalize(), sources.get("scalar"));
        assertEquals(relauncher.toAbsolutePath().normalize(), sources.get("cleanroom-relauncher"));
        assertFalse(sources.containsKey("ignored"));
        assertFalse(sources.containsKey("nested"));
    }

    @Test
    void ambiguousPackagedModIdsAreNotGuessed(@TempDir final Path temporary) throws Exception {
        writeModJar(temporary.resolve("mods").resolve("first.jar"), "[{\"modid\":\"duplicate\"}]");
        writeModJar(temporary.resolve("mods").resolve("second.jar"), "[{\"modid\":\"duplicate\"}]");

        assertFalse(BenchmarkReport.findPackagedModSources(temporary).containsKey("duplicate"));
    }

    private static void writeModJar(final Path target, final String metadata) throws Exception {
        Files.createDirectories(target.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target))) {
            output.putNextEntry(new JarEntry("mcmod.info"));
            output.write(metadata.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }
}
