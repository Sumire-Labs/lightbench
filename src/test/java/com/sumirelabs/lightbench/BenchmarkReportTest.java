package com.sumirelabs.lightbench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkReportTest {

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
}
