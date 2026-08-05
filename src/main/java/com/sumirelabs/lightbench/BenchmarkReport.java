package com.sumirelabs.lightbench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeVersion;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

/** Builds and writes the versioned, machine-readable result for generation benchmarks. */
final class BenchmarkReport {

    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON =
            new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private BenchmarkReport() {}

    static String nowUtc() {
        return Instant.now().toString();
    }

    static Plan generationPlan(
            final int warmupCenter,
            final int warmupRadius,
            final int testCenter,
            final int regionRadius,
            final int regionCount,
            final int regionStride,
            final int batchLimit) {
        final JsonObject plan = new JsonObject();
        plan.addProperty("coordinate_unit", "chunk");
        plan.addProperty("completion_barrier", "after_each_batch");

        final JsonObject warmup = new JsonObject();
        warmup.addProperty("center_x", warmupCenter);
        warmup.addProperty("center_z", warmupCenter);
        warmup.addProperty("radius", warmupRadius);
        warmup.addProperty("chunk_count", squareChunkCount(warmupRadius));
        plan.add("warmup", warmup);

        final JsonObject test = new JsonObject();
        test.addProperty("first_center_x", testCenter);
        test.addProperty("first_center_z", testCenter);
        test.addProperty("region_radius", regionRadius);
        test.addProperty("region_count", regionCount);
        test.addProperty("region_stride_x", regionStride);
        test.addProperty("region_stride_z", regionStride);
        test.addProperty("chunks_per_region", squareChunkCount(regionRadius));
        test.addProperty("chunk_count", squareChunkCount(regionRadius) * regionCount);
        test.addProperty("batch_limit", batchLimit);
        test.addProperty("traversal", "original_lightbench_center_out_rings");
        plan.add("test", test);
        return new Plan(plan);
    }

    static Plan bulkPlan(final int warmupCenter, final int warmupRadius, final int testCenter, final int testRadius) {
        final JsonObject plan = new JsonObject();
        plan.addProperty("coordinate_unit", "chunk");
        plan.addProperty("completion_barrier", "once_after_complete_square");

        if (warmupRadius > 0) {
            final JsonObject warmup = new JsonObject();
            warmup.addProperty("center_x", warmupCenter);
            warmup.addProperty("center_z", warmupCenter);
            warmup.addProperty("radius", warmupRadius);
            warmup.addProperty("chunk_count", squareChunkCount(warmupRadius));
            plan.add("warmup", warmup);
        } else {
            plan.add("warmup", JsonNull.INSTANCE);
        }

        final JsonObject test = new JsonObject();
        test.addProperty("center_x", testCenter);
        test.addProperty("center_z", testCenter);
        test.addProperty("radius", testRadius);
        test.addProperty("chunk_count", squareChunkCount(testRadius));
        plan.add("test", test);
        return new Plan(plan);
    }

    static Path write(
            final World world,
            final String mode,
            final LightProbe.Engine engine,
            final String startedAtUtc,
            final int preflightHalo,
            final int preflightChunks,
            final long preflightNanos,
            final Plan plan,
            final List<BenchmarkPhaseResult> phases)
            throws IOException {
        final JsonObject root = new JsonObject();
        root.addProperty("schema_version", SCHEMA_VERSION);

        final JsonObject benchmark = new JsonObject();
        benchmark.addProperty("mode", mode);
        benchmark.addProperty("started_at_utc", startedAtUtc);
        benchmark.addProperty("completed_at_utc", nowUtc());
        benchmark.addProperty("lightbench_version", Tags.VERSION);
        benchmark.addProperty("engine", engine.name().toLowerCase(Locale.ROOT));
        benchmark.addProperty("seed", Long.toString(world.getSeed()));
        benchmark.addProperty("time_unit", "nanoseconds");
        benchmark.addProperty("reporting_excluded_from_measurements", true);

        final JsonObject dimension = new JsonObject();
        dimension.addProperty("id", world.provider.getDimension());
        dimension.addProperty("name", world.provider.getDimensionType().getName());
        dimension.addProperty("provider_class", world.provider.getClass().getName());
        dimension.addProperty("has_sky_light", world.provider.hasSkyLight());
        benchmark.add("dimension", dimension);

        final JsonObject preflight = new JsonObject();
        preflight.addProperty("halo_chunks", preflightHalo);
        preflight.addProperty("checked_chunks", preflightChunks);
        preflight.addProperty("elapsed_nanos", preflightNanos);
        preflight.addProperty("all_ungenerated", true);
        benchmark.add("preflight", preflight);
        benchmark.add("plan", plan.json);

        final JsonArray phaseArray = new JsonArray();
        for (final BenchmarkPhaseResult phase : phases) {
            phaseArray.add(phaseToJson(phase));
        }
        benchmark.add("phases", phaseArray);
        root.add("benchmark", benchmark);
        root.add("environment", environmentToJson(world));
        root.add("mods", modsToJson());

        final Path directory =
                world.getSaveHandler().getWorldDirectory().toPath().resolve("lightbench-results");
        final String prefix = FILE_TIME.format(Instant.now()) + "-" + mode + "-"
                + engine.name().toLowerCase(Locale.ROOT) + "-dim" + world.provider.getDimension();
        final byte[] json = serializeJson(root);
        return writeUniqueJson(directory, prefix, json);
    }

    static Path writeUpdates(
            final World world,
            final LightProbe.Engine engine,
            final String startedAtUtc,
            final String completionAdapter,
            final String verificationReader,
            final JsonObject preflight,
            final Plan plan,
            final List<UpdatePhaseResult> phases,
            final long workerCpuNanos,
            final long gcCollectionsDelta,
            final long gcTimeMillisDelta)
            throws IOException {
        final JsonObject root = new JsonObject();
        root.addProperty("schema_version", SCHEMA_VERSION);

        final JsonObject benchmark = new JsonObject();
        benchmark.addProperty("mode", "updates");
        benchmark.addProperty("started_at_utc", startedAtUtc);
        benchmark.addProperty("completed_at_utc", nowUtc());
        benchmark.addProperty("lightbench_version", Tags.VERSION);
        benchmark.addProperty("engine", engine.name().toLowerCase(Locale.ROOT));
        benchmark.addProperty("seed", Long.toString(world.getSeed()));
        benchmark.addProperty("time_unit", "nanoseconds");
        benchmark.addProperty("reporting_excluded_from_measurements", true);
        benchmark.addProperty("completion_adapter", completionAdapter);
        benchmark.addProperty("verification_reader", verificationReader);

        final JsonObject dimension = new JsonObject();
        dimension.addProperty("id", world.provider.getDimension());
        dimension.addProperty("name", world.provider.getDimensionType().getName());
        dimension.addProperty("provider_class", world.provider.getClass().getName());
        dimension.addProperty("has_sky_light", world.provider.hasSkyLight());
        benchmark.add("dimension", dimension);
        benchmark.add("preflight", preflight);
        benchmark.add("plan", plan.json);

        final JsonObject measurementGc = new JsonObject();
        if (gcCollectionsDelta >= 0 && gcTimeMillisDelta >= 0) {
            measurementGc.addProperty("collection_count_delta", gcCollectionsDelta);
            measurementGc.addProperty("collection_time_millis_delta", gcTimeMillisDelta);
        } else {
            measurementGc.add("collection_count_delta", JsonNull.INSTANCE);
            measurementGc.add("collection_time_millis_delta", JsonNull.INSTANCE);
        }
        benchmark.add("measurement_gc", measurementGc);
        if (workerCpuNanos >= 0) {
            benchmark.addProperty("pulsar_worker_cpu_nanos", workerCpuNanos);
        } else {
            benchmark.add("pulsar_worker_cpu_nanos", JsonNull.INSTANCE);
        }

        final JsonArray phaseArray = new JsonArray();
        for (final UpdatePhaseResult phase : phases) {
            phaseArray.add(updatePhaseToJson(phase));
        }
        benchmark.add("phases", phaseArray);
        root.add("benchmark", benchmark);
        root.add("environment", environmentToJson(world));
        root.add("mods", modsToJson());

        final Path directory =
                world.getSaveHandler().getWorldDirectory().toPath().resolve("lightbench-results");
        final String prefix = FILE_TIME.format(Instant.now()) + "-updates-"
                + engine.name().toLowerCase(Locale.ROOT) + "-dim" + world.provider.getDimension();
        return writeUniqueJson(directory, prefix, serializeJson(root));
    }

    static byte[] serializeJson(final JsonObject root) {
        return (GSON.toJson(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    static Path writeUniqueJson(final Path directory, final String prefix, final byte[] json) throws IOException {
        Files.createDirectories(directory);
        final Path temporary = Files.createTempFile(directory, ".lightbench-", ".tmp");
        try {
            Files.write(temporary, json, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            for (int suffix = 0; suffix < 1000; ++suffix) {
                final String numberedSuffix = suffix == 0 ? "" : "-" + suffix;
                final Path output = directory.resolve(prefix + numberedSuffix + ".json");
                try {
                    Files.move(temporary, output);
                    return output;
                } catch (final FileAlreadyExistsException ignored) {
                    // Try the next deterministic suffix without overwriting an earlier run.
                }
            }
            throw new IOException("could not allocate a unique Lightbench result filename");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static JsonObject phaseToJson(final BenchmarkPhaseResult phase) {
        final JsonObject json = new JsonObject();
        json.addProperty("name", phase.name);
        json.addProperty("chunk_count", phase.chunkCount);
        json.addProperty("region_count", phase.regions.size());
        json.addProperty("batch_count", phase.batches.size());
        json.addProperty("batch_limit", phase.batchLimit);
        json.addProperty("provide_nanos", phase.provideNanos);
        json.addProperty("barrier_nanos", phase.barrierNanos);
        json.addProperty("total_until_lit_nanos", phase.totalNanos);
        json.addProperty("chunks_per_second", phase.chunkCount / (phase.totalNanos * 1.0e-9));
        if (phase.workerCpuNanos >= 0) {
            json.addProperty("pulsar_worker_cpu_nanos", phase.workerCpuNanos);
        } else {
            json.add("pulsar_worker_cpu_nanos", JsonNull.INSTANCE);
        }

        final JsonArray batches = new JsonArray();
        for (int index = 0; index < phase.batches.size(); ++index) {
            final JsonObject item = new JsonObject();
            item.addProperty("ordinal", index);
            item.addProperty("region_index", phase.batches.regionIndices[index]);
            item.addProperty("first_index_in_region", phase.batches.firstIndicesInRegion[index]);
            item.addProperty("chunk_count", phase.batches.chunkCounts[index]);
            item.add("first_chunk", coordinate(phase.batches.firstChunkX[index], phase.batches.firstChunkZ[index]));
            item.add("last_chunk", coordinate(phase.batches.lastChunkX[index], phase.batches.lastChunkZ[index]));
            item.addProperty("provide_nanos", phase.batches.provideNanos[index]);
            item.addProperty("barrier_nanos", phase.batches.barrierNanos[index]);
            item.addProperty("wall_nanos", phase.batches.wallNanos[index]);
            batches.add(item);
        }
        json.add("batch_wall_summary_nanos", distribution(phase.batches.wallNanos));
        json.add("batches", batches);

        final JsonArray regions = new JsonArray();
        for (int index = 0; index < phase.regions.size(); ++index) {
            final JsonObject item = new JsonObject();
            item.addProperty("index", index);
            item.addProperty("chunk_count", phase.regions.chunkCounts[index]);
            item.addProperty("batch_count", phase.regions.batchCounts[index]);
            item.addProperty("wall_nanos", phase.regions.wallNanos[index]);
            regions.add(item);
        }
        json.add("regions", regions);
        return json;
    }

    static JsonObject updatePhaseToJson(final UpdatePhaseResult phase) {
        final JsonObject json = new JsonObject();
        json.addProperty("name", phase.name);
        json.addProperty("light_type", phase.lightType);
        json.addProperty("action", phase.action);
        json.add("position", coordinate(phase.x, phase.y, phase.z));
        json.addProperty("sample_count", phase.size());
        json.addProperty("all_samples_verified", true);
        json.add("submission_summary_nanos", distribution(phase.submissionNanos));
        json.add("barrier_summary_nanos", distribution(phase.barrierNanos));
        json.add("completion_summary_nanos", distribution(phase.completionNanos));

        final JsonArray samples = new JsonArray();
        for (int index = 0; index < phase.size(); ++index) {
            final JsonObject sample = new JsonObject();
            sample.addProperty("ordinal", index);
            sample.addProperty("submission_nanos", phase.submissionNanos[index]);
            sample.addProperty("barrier_nanos", phase.barrierNanos[index]);
            sample.addProperty("completion_nanos", phase.completionNanos[index]);
            sample.addProperty("light_verified", true);
            samples.add(sample);
        }
        json.add("samples", samples);
        return json;
    }

    private static JsonObject environmentToJson(final World world) {
        final JsonObject environment = new JsonObject();
        environment.addProperty("minecraft_version", ForgeVersion.mcVersion);
        environment.addProperty("forge_version", ForgeVersion.getVersion());
        environment.addProperty("mcp_version", ForgeVersion.mcpVersion);

        final JsonObject java = new JsonObject();
        java.addProperty("version", System.getProperty("java.version"));
        java.addProperty("vendor", System.getProperty("java.vendor"));
        java.addProperty("vm_name", System.getProperty("java.vm.name"));
        java.addProperty("vm_version", System.getProperty("java.vm.version"));
        java.addProperty("max_heap_bytes", Runtime.getRuntime().maxMemory());
        java.addProperty("logical_processors", Runtime.getRuntime().availableProcessors());

        final JsonArray arguments = new JsonArray();
        for (final String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (isPerformanceJvmArgument(argument)) {
                arguments.add(new JsonPrimitive(argument));
            }
        }
        java.add("performance_arguments", arguments);

        final JsonArray collectors = new JsonArray();
        for (final GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            collectors.add(new JsonPrimitive(collector.getName()));
        }
        java.add("garbage_collectors", collectors);
        environment.add("java", java);

        final JsonObject operatingSystem = new JsonObject();
        operatingSystem.addProperty("name", System.getProperty("os.name"));
        operatingSystem.addProperty("version", System.getProperty("os.version"));
        operatingSystem.addProperty("arch", System.getProperty("os.arch"));
        final String processorIdentifier = System.getenv("PROCESSOR_IDENTIFIER");
        if (processorIdentifier != null && !processorIdentifier.isEmpty()) {
            operatingSystem.addProperty("processor_identifier", processorIdentifier);
        }
        environment.add("operating_system", operatingSystem);

        final JsonObject worldSettings = new JsonObject();
        worldSettings.addProperty(
                "terrain_type", world.getWorldInfo().getTerrainType().getName());
        worldSettings.addProperty("generator_options", world.getWorldInfo().getGeneratorOptions());
        worldSettings.addProperty("map_features", world.getWorldInfo().isMapFeaturesEnabled());
        worldSettings.addProperty("difficulty", world.getDifficulty().name().toLowerCase(Locale.ROOT));
        environment.add("world_settings", worldSettings);

        final JsonObject server = new JsonObject();
        if (world.getMinecraftServer() != null) {
            server.addProperty("dedicated", world.getMinecraftServer().isDedicatedServer());
            server.addProperty(
                    "implementation_class",
                    world.getMinecraftServer().getClass().getName());
        } else {
            server.add("dedicated", JsonNull.INSTANCE);
            server.add("implementation_class", JsonNull.INSTANCE);
        }
        environment.add("server", server);

        environment.add("config_fingerprint", configFingerprint(world));
        return environment;
    }

    private static JsonArray modsToJson() {
        final List<ModContainer> mods = new ArrayList<>(Loader.instance().getActiveModList());
        mods.sort(Comparator.comparing(ModContainer::getModId));
        final Path gameDirectory = Loader.instance()
                .getConfigDir()
                .toPath()
                .toAbsolutePath()
                .normalize()
                .getParent();
        final Map<String, Path> packagedSources = new LinkedHashMap<>();
        try {
            packagedSources.putAll(findPackagedModSources(gameDirectory));
        } catch (final IOException | SecurityException ignored) {
            // Directory-backed entries remain explicit, so strict validation will reject unverifiable runs.
        }
        final JsonArray result = new JsonArray();
        for (final ModContainer mod : mods) {
            final JsonObject item = new JsonObject();
            item.addProperty("id", mod.getModId());
            item.addProperty("name", mod.getName());
            item.addProperty("version", mod.getVersion());
            final Path packagedSource = packagedSources.get(mod.getModId().toLowerCase(Locale.ROOT));
            if (packagedSource != null) {
                addFileSource(item, packagedSource);
            } else if (mod.getSource() != null && mod.getSource().isFile()) {
                addFileSource(item, mod.getSource().toPath());
            } else if (mod.getSource() != null && !isBuiltinRuntimeMod(mod.getModId())) {
                item.addProperty("source_name", mod.getSource().getName());
                item.addProperty("source_type", "directory");
            }
            result.add(item);
        }
        return result;
    }

    static Map<String, Path> findPackagedModSources(final Path gameDirectory) throws IOException {
        final Path modsDirectory = gameDirectory.resolve("mods");
        final List<Path> artifacts = new ArrayList<>();
        collectPackagedMods(modsDirectory, artifacts);
        collectPackagedMods(modsDirectory.resolve(ForgeVersion.mcVersion), artifacts);
        artifacts.sort(Comparator.comparing(path -> normalizedRelativePath(gameDirectory, path)));

        final Map<String, Path> result = new LinkedHashMap<>();
        final Set<String> ambiguousIds = new LinkedHashSet<>();
        for (final Path artifact : artifacts) {
            for (final String id : readPackagedModIds(artifact)) {
                if (ambiguousIds.contains(id)) {
                    continue;
                }
                final Path previous = result.put(id, artifact);
                if (previous != null && !previous.equals(artifact)) {
                    result.remove(id);
                    ambiguousIds.add(id);
                }
            }
        }
        return result;
    }

    private static void collectPackagedMods(final Path directory, final List<Path> result) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.list(directory)) {
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(BenchmarkReport::isPackagedMod)
                    .map(path -> path.toAbsolutePath().normalize())
                    .forEach(result::add);
        }
    }

    private static boolean isPackagedMod(final Path path) {
        final String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    private static Set<String> readPackagedModIds(final Path artifact) {
        final Set<String> result = new LinkedHashSet<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            final JarEntry metadata = jar.getJarEntry("mcmod.info");
            if (metadata == null) {
                return result;
            }
            try (InputStreamReader reader =
                    new InputStreamReader(jar.getInputStream(metadata), StandardCharsets.UTF_8)) {
                final JsonElement parsed = new JsonParser().parse(reader);
                final JsonArray entries;
                if (parsed.isJsonArray()) {
                    entries = parsed.getAsJsonArray();
                } else if (parsed.isJsonObject()
                        && parsed.getAsJsonObject().has("modList")
                        && parsed.getAsJsonObject().get("modList").isJsonArray()) {
                    entries = parsed.getAsJsonObject().getAsJsonArray("modList");
                } else {
                    return result;
                }
                for (final JsonElement entry : entries) {
                    if (!entry.isJsonObject() || !entry.getAsJsonObject().has("modid")) {
                        continue;
                    }
                    final String id =
                            entry.getAsJsonObject().get("modid").getAsString().trim();
                    if (!id.isEmpty()) {
                        result.add(id.toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (final IOException | JsonParseException | IllegalStateException | SecurityException ignored) {
            // FML may accept artifacts without mcmod.info. Their ModContainer source remains the fallback.
        }
        return result;
    }

    private static void addFileSource(final JsonObject item, final Path source) {
        final Path normalized = source.toAbsolutePath().normalize();
        item.addProperty("source_name", normalized.getFileName().toString());
        item.addProperty("source_type", "file");
        try {
            item.addProperty("source_size_bytes", Files.size(normalized));
            item.addProperty("source_sha256", sha256(normalized));
        } catch (final IOException | SecurityException e) {
            item.addProperty("source_size_bytes", 0);
            item.addProperty("source_sha256", "unavailable");
        }
    }

    private static boolean isBuiltinRuntimeMod(final String id) {
        return "minecraft".equalsIgnoreCase(id) || "mcp".equalsIgnoreCase(id);
    }

    private static JsonObject configFingerprint(final World world) {
        final JsonObject result = new JsonObject();
        if (world.getMinecraftServer() == null) {
            result.addProperty("status", "unavailable");
            return result;
        }
        final Path config = world.getMinecraftServer().getFile("config").toPath();
        if (!Files.isDirectory(config)) {
            result.addProperty("status", "missing");
            return result;
        }

        try {
            final List<Path> files = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(config)) {
                stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .forEach(files::add);
            }
            files.sort(Comparator.comparing(path -> normalizedRelativePath(config, path)));

            final MessageDigest digest = newDigest();
            final byte[] buffer = new byte[65536];
            for (final Path file : files) {
                digest.update(normalizedRelativePath(config, file).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                updateDigest(digest, file, buffer);
                digest.update((byte) 0);
            }
            result.addProperty("status", "ok");
            result.addProperty("file_count", files.size());
            result.addProperty("sha256", hex(digest.digest()));
        } catch (final IOException | UncheckedIOException | SecurityException e) {
            result.addProperty("status", "unavailable");
        }
        return result;
    }

    private static String sha256(final Path file) throws IOException {
        final MessageDigest digest = newDigest();
        updateDigest(digest, file, new byte[65536]);
        return hex(digest.digest());
    }

    private static void updateDigest(final MessageDigest digest, final Path file, final byte[] buffer)
            throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String normalizedRelativePath(final Path root, final Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static boolean isPerformanceJvmArgument(final String argument) {
        return argument.equals("-server")
                || argument.equals("-client")
                || argument.startsWith("-Xms")
                || argument.startsWith("-Xmx")
                || argument.startsWith("-Xmn")
                || argument.startsWith("-Xss")
                || argument.startsWith("-XX:")
                || argument.startsWith("-Xlog:gc")
                || argument.startsWith("-Xloggc:");
    }

    private static JsonObject distribution(final long[] values) {
        final JsonObject result = new JsonObject();
        if (values.length == 0) {
            return result;
        }
        final long[] sorted = values.clone();
        Arrays.sort(sorted);
        long sum = 0;
        for (final long value : sorted) {
            sum += value;
        }
        result.addProperty("minimum", sorted[0]);
        result.addProperty("average", sum / (double) sorted.length);
        result.addProperty("p50", percentile(sorted, 0.50));
        result.addProperty("p95", percentile(sorted, 0.95));
        result.addProperty("p99", percentile(sorted, 0.99));
        result.addProperty("maximum", sorted[sorted.length - 1]);
        return result;
    }

    private static long percentile(final long[] sorted, final double quantile) {
        final int index = Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * quantile) - 1));
        return sorted[index];
    }

    private static JsonArray coordinate(final int x, final int z) {
        final JsonArray coordinate = new JsonArray();
        coordinate.add(new JsonPrimitive(x));
        coordinate.add(new JsonPrimitive(z));
        return coordinate;
    }

    private static JsonArray coordinate(final int x, final int y, final int z) {
        final JsonArray coordinate = new JsonArray();
        coordinate.add(new JsonPrimitive(x));
        coordinate.add(new JsonPrimitive(y));
        coordinate.add(new JsonPrimitive(z));
        return coordinate;
    }

    private static String hex(final byte[] bytes) {
        final StringBuilder result = new StringBuilder(bytes.length * 2);
        for (final byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0xf, 16));
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }

    private static int squareChunkCount(final int radius) {
        final int diameter = radius * 2 + 1;
        return diameter * diameter;
    }

    static final class Plan {

        private final JsonObject json;

        Plan(final JsonObject json) {
            this.json = json;
        }
    }
}
