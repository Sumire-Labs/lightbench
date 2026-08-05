package com.sumirelabs.lightbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Validates versioned Lightbench results and builds reports from their raw observations. */
final class BenchmarkComparison {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final int MAX_RENDERED_MISMATCH_VALUE = 240;
    private static final Set<String> KNOWN_ENGINE_MOD_IDS =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("pulsar", "alfheim")));
    private static final Set<String> BUILTIN_RUNTIME_MOD_IDS =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("minecraft", "mcp")));
    private static final List<String> COMMON_STRICT_PATHS = Collections.unmodifiableList(Arrays.asList(
            "/schema_version",
            "/benchmark/mode",
            "/benchmark/lightbench_version",
            "/benchmark/seed",
            "/benchmark/time_unit",
            "/benchmark/reporting_excluded_from_measurements",
            "/benchmark/dimension",
            "/benchmark/plan",
            "/environment/minecraft_version",
            "/environment/forge_version",
            "/environment/mcp_version",
            "/environment/java",
            "/environment/operating_system",
            "/environment/world_settings",
            "/environment/config_fingerprint"));
    private static final List<String> CHUNK_STRICT_PATHS = Collections.unmodifiableList(Arrays.asList(
            "/benchmark/preflight/halo_chunks",
            "/benchmark/preflight/checked_chunks",
            "/benchmark/preflight/all_ungenerated"));
    private static final List<String> UPDATE_STRICT_PATHS =
            Collections.unmodifiableList(Arrays.asList("/benchmark/preflight", "/environment/server"));
    private static final String[] UPDATE_PHASE_NAMES = {"sky_remove", "sky_place", "block_place", "block_remove"};

    private BenchmarkComparison() {}

    static Result compare(final List<Path> files, final Set<String> additionallyIgnoredModIds)
            throws IOException, InvalidResultException, IncompatibleResultsException {
        if (files.size() < 2) {
            throw new InvalidResultException("at least two JSON result files are required");
        }

        final List<Run> runs = new ArrayList<>(files.size());
        for (final Path file : files) {
            runs.add(readRun(file));
        }

        final Set<String> ignoredModIds = new LinkedHashSet<>();
        ignoredModIds.addAll(KNOWN_ENGINE_MOD_IDS);
        for (final Run run : runs) {
            if (!"vanilla".equals(run.engine)) {
                ignoredModIds.add(run.engine);
            }
        }
        for (final String id : additionallyIgnoredModIds) {
            final String normalized = id.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                ignoredModIds.add(normalized);
            }
        }

        final List<String> mismatches = compatibilityMismatches(runs, ignoredModIds);
        if (!mismatches.isEmpty()) {
            throw new IncompatibleResultsException(mismatches);
        }

        runs.sort(Comparator.comparing((Run run) -> engineOrder(run.engine))
                .thenComparing(run -> run.engine)
                .thenComparing(run -> run.startedAtUtc)
                .thenComparing(run -> run.source.toString()));
        return new Result(runs, ignoredModIds);
    }

    private static Run readRun(final Path source) throws IOException, InvalidResultException {
        final Path normalized = source.toAbsolutePath().normalize();
        final JsonObject root;
        try (Reader reader = Files.newBufferedReader(normalized, StandardCharsets.UTF_8)) {
            final JsonElement parsed = new JsonParser().parse(reader);
            if (!parsed.isJsonObject()) {
                throw invalid(normalized, "/", "root must be a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (final JsonParseException | IllegalStateException e) {
            throw invalid(normalized, "/", "malformed JSON: " + e.getMessage());
        }

        final int schemaVersion = requiredInt(root, "/schema_version", normalized);
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw invalid(
                    normalized,
                    "/schema_version",
                    "unsupported schema " + schemaVersion + "; expected " + SUPPORTED_SCHEMA_VERSION);
        }

        for (final String pointer : COMMON_STRICT_PATHS) {
            required(root, pointer, normalized);
        }
        if (!"nanoseconds".equals(requiredString(root, "/benchmark/time_unit", normalized))) {
            throw invalid(normalized, "/benchmark/time_unit", "must be nanoseconds");
        }
        if (!requiredBoolean(root, "/benchmark/reporting_excluded_from_measurements", normalized)) {
            throw invalid(
                    normalized,
                    "/benchmark/reporting_excluded_from_measurements",
                    "must be true before results can be compared");
        }
        final String mode =
                requiredNonEmptyString(root, "/benchmark/mode", normalized).toLowerCase(Locale.ROOT);
        final String engine =
                requiredNonEmptyString(root, "/benchmark/engine", normalized).toLowerCase(Locale.ROOT);
        requiredNonEmptyString(root, "/benchmark/lightbench_version", normalized);
        final String startedAtUtc = requiredNonEmptyString(root, "/benchmark/started_at_utc", normalized);
        final Instant startedAt = parseInstant(startedAtUtc, normalized, "/benchmark/started_at_utc");
        final Instant completedAt = parseInstant(
                requiredNonEmptyString(root, "/benchmark/completed_at_utc", normalized),
                normalized,
                "/benchmark/completed_at_utc");
        if (completedAt.isBefore(startedAt)) {
            throw invalid(normalized, "/benchmark/completed_at_utc", "must not be before started_at_utc");
        }
        final String seed = requiredNonEmptyString(root, "/benchmark/seed", normalized);
        try {
            Long.parseLong(seed);
        } catch (final NumberFormatException e) {
            throw invalid(normalized, "/benchmark/seed", "must be a signed 64-bit integer encoded as a string");
        }
        final int dimensionId = requiredInt(root, "/benchmark/dimension/id", normalized);
        requiredNonEmptyString(root, "/benchmark/dimension/name", normalized);
        requiredNonEmptyString(root, "/benchmark/dimension/provider_class", normalized);
        final boolean hasSkyLight = requiredBoolean(root, "/benchmark/dimension/has_sky_light", normalized);
        validateEnvironment(root, normalized);

        final Map<String, JsonObject> mods = readMods(root, normalized);
        validateDetectedEngine(engine, mods, normalized);
        if ("updates".equals(mode)) {
            if (!hasSkyLight) {
                throw invalid(normalized, "/benchmark/dimension/has_sky_light", "updates results require sky light");
            }
            validateServerEnvironment(root, normalized);
            return readUpdateRun(normalized, root, mods, mode, engine, startedAtUtc, seed, dimensionId);
        }
        if (!"gen".equals(mode) && !"bulk".equals(mode)) {
            throw invalid(normalized, "/benchmark/mode", "only gen, bulk, and updates JSON results are comparable");
        }

        if (!requiredBoolean(root, "/benchmark/preflight/all_ungenerated", normalized)) {
            throw invalid(
                    normalized, "/benchmark/preflight/all_ungenerated", "must be true before results can be compared");
        }
        nonNegativeInt(root, "/benchmark/preflight/halo_chunks", normalized);
        positiveInt(root, "/benchmark/preflight/checked_chunks", normalized);
        nonNegativeLong(root, "/benchmark/preflight/elapsed_nanos", normalized);

        final JsonArray phaseJson = requiredArray(root, "/benchmark/phases", normalized);
        final Map<String, Phase> phases = new LinkedHashMap<>();
        for (int index = 0; index < phaseJson.size(); ++index) {
            final String phasePath = "/benchmark/phases/" + index;
            final JsonObject object = requiredObject(phaseJson.get(index), phasePath, normalized);
            final Phase phase = readPhase(object, phasePath, normalized);
            if (phases.put(phase.name, phase) != null) {
                throw invalid(normalized, phasePath + "/name", "duplicate phase name " + phase.name);
            }
        }

        final JsonObject plan = requiredObject(root, "/benchmark/plan", normalized);
        validatePlan(plan, mode, normalized);
        final Phase test = phases.get(mode + " test");
        if (test == null) {
            throw invalid(normalized, "/benchmark/phases", "missing phase named " + mode + " test");
        }
        final JsonObject testPlan = requiredObject(plan, "/benchmark/plan/test", normalized);
        final int plannedTestChunks = requiredInt(testPlan, "/benchmark/plan/test/chunk_count", normalized);
        if (test.chunkCount != plannedTestChunks) {
            throw invalid(
                    normalized,
                    "/benchmark/phases",
                    "test phase has " + test.chunkCount + " chunks but plan declares " + plannedTestChunks);
        }
        if (testPlan.has("batch_limit")
                && test.batchLimit != requiredInt(testPlan, "/benchmark/plan/test/batch_limit", normalized)) {
            throw invalid(normalized, "/benchmark/phases", "test phase batch limit does not match the plan");
        }

        int expectedPhaseCount = 1;
        final JsonElement warmupPlanElement = plan.get("warmup");
        if (warmupPlanElement == null) {
            throw invalid(normalized, "/benchmark/plan/warmup", "required field is missing");
        }
        if (!warmupPlanElement.isJsonNull()) {
            ++expectedPhaseCount;
            final JsonObject warmupPlan = requiredObject(warmupPlanElement, "/benchmark/plan/warmup", normalized);
            final Phase warmup = phases.get(mode + " warmup");
            if (warmup == null) {
                throw invalid(normalized, "/benchmark/phases", "missing phase named " + mode + " warmup");
            }
            final int plannedWarmupChunks = requiredInt(warmupPlan, "/benchmark/plan/warmup/chunk_count", normalized);
            if (warmup.chunkCount != plannedWarmupChunks) {
                throw invalid(
                        normalized,
                        "/benchmark/phases",
                        "warmup phase has " + warmup.chunkCount + " chunks but plan declares " + plannedWarmupChunks);
            }
        }
        if (phases.size() != expectedPhaseCount) {
            throw invalid(
                    normalized,
                    "/benchmark/phases",
                    "expected " + expectedPhaseCount + " planned phases but found " + phases.size());
        }

        return new Run(
                normalized,
                root,
                mods,
                mode,
                engine,
                startedAtUtc,
                seed,
                dimensionId,
                test,
                Collections.emptyList(),
                null,
                null,
                null);
    }

    private static Run readUpdateRun(
            final Path source,
            final JsonObject root,
            final Map<String, JsonObject> mods,
            final String mode,
            final String engine,
            final String startedAtUtc,
            final String seed,
            final int dimensionId)
            throws InvalidResultException {
        final UpdatePlan plan = validateUpdatePlan(requiredObject(root, "/benchmark/plan", source), source);
        validateUpdatePreflight(root, plan, source);
        validateUpdateAdapters(root, engine, source);

        final NullableMeasurement gcCollections =
                nullableNonNegativeLong(root, "/benchmark/measurement_gc/collection_count_delta", source);
        final NullableMeasurement gcTime =
                nullableNonNegativeLong(root, "/benchmark/measurement_gc/collection_time_millis_delta", source);
        if (gcCollections.present != gcTime.present) {
            throw invalid(
                    source,
                    "/benchmark/measurement_gc",
                    "collection count and time must either both be integers or both be null");
        }
        final NullableMeasurement workerCpu =
                nullableNonNegativeLong(root, "/benchmark/pulsar_worker_cpu_nanos", source);
        if (!"pulsar".equals(engine) && workerCpu.present) {
            throw invalid(source, "/benchmark/pulsar_worker_cpu_nanos", "must be null for engines other than Pulsar");
        }

        final JsonArray phaseJson = requiredArray(root, "/benchmark/phases", source);
        if (phaseJson.size() != plan.phases.size()) {
            throw invalid(
                    source,
                    "/benchmark/phases",
                    "expected " + plan.phases.size() + " fixed update phases but found " + phaseJson.size());
        }
        final List<UpdatePhase> phases = new ArrayList<>(phaseJson.size());
        for (int index = 0; index < phaseJson.size(); ++index) {
            final String path = "/benchmark/phases/" + index;
            phases.add(readUpdatePhase(
                    requiredObject(phaseJson.get(index), path, source),
                    path,
                    source,
                    plan.phases.get(index),
                    plan.measuredPairs,
                    engine));
        }

        return new Run(
                source,
                root,
                mods,
                mode,
                engine,
                startedAtUtc,
                seed,
                dimensionId,
                null,
                phases,
                workerCpu.value,
                gcCollections.value,
                gcTime.value);
    }

    private static void validateEnvironment(final JsonObject root, final Path source) throws InvalidResultException {
        requiredNonEmptyString(root, "/environment/minecraft_version", source);
        requiredNonEmptyString(root, "/environment/forge_version", source);
        requiredNonEmptyString(root, "/environment/mcp_version", source);

        final JsonObject java = requiredObject(root, "/environment/java", source);
        requiredNonEmptyString(java, "/environment/java/version", source);
        requiredNonEmptyString(java, "/environment/java/vendor", source);
        requiredNonEmptyString(java, "/environment/java/vm_name", source);
        requiredNonEmptyString(java, "/environment/java/vm_version", source);
        positiveLong(java, "/environment/java/max_heap_bytes", source);
        positiveInt(java, "/environment/java/logical_processors", source);
        validateStringArray(
                requiredArray(java, "/environment/java/performance_arguments", source),
                "/environment/java/performance_arguments",
                source);
        validateStringArray(
                requiredArray(java, "/environment/java/garbage_collectors", source),
                "/environment/java/garbage_collectors",
                source);

        final JsonObject operatingSystem = requiredObject(root, "/environment/operating_system", source);
        requiredNonEmptyString(operatingSystem, "/environment/operating_system/name", source);
        requiredNonEmptyString(operatingSystem, "/environment/operating_system/version", source);
        requiredNonEmptyString(operatingSystem, "/environment/operating_system/arch", source);
        if (operatingSystem.has("processor_identifier")) {
            requiredNonEmptyString(operatingSystem, "/environment/operating_system/processor_identifier", source);
        }

        final JsonObject worldSettings = requiredObject(root, "/environment/world_settings", source);
        requiredNonEmptyString(worldSettings, "/environment/world_settings/terrain_type", source);
        requiredString(worldSettings, "/environment/world_settings/generator_options", source);
        requiredBoolean(worldSettings, "/environment/world_settings/map_features", source);
        requiredNonEmptyString(worldSettings, "/environment/world_settings/difficulty", source);

        final JsonObject config = requiredObject(root, "/environment/config_fingerprint", source);
        final String status = requiredNonEmptyString(config, "/environment/config_fingerprint/status", source);
        if (!"ok".equals(status)) {
            throw invalid(
                    source,
                    "/environment/config_fingerprint/status",
                    "must be ok; unavailable or missing config data cannot establish comparable settings");
        }
        nonNegativeInt(config, "/environment/config_fingerprint/file_count", source);
        final String sha256 = requiredNonEmptyString(config, "/environment/config_fingerprint/sha256", source);
        if (!isSha256(sha256)) {
            throw invalid(source, "/environment/config_fingerprint/sha256", "must be 64 lowercase hexadecimal digits");
        }
    }

    private static void validateServerEnvironment(final JsonObject root, final Path source)
            throws InvalidResultException {
        final JsonObject server = requiredObject(root, "/environment/server", source);
        requiredBoolean(server, "/environment/server/dedicated", source);
        requiredNonEmptyString(server, "/environment/server/implementation_class", source);
    }

    private static void validateStringArray(final JsonArray values, final String path, final Path source)
            throws InvalidResultException {
        for (int index = 0; index < values.size(); ++index) {
            final JsonElement value = values.get(index);
            if (!value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString()
                    || value.getAsString().trim().isEmpty()) {
                throw invalid(source, path + "/" + index, "must be a non-empty JSON string");
            }
        }
    }

    private static void validatePlan(final JsonObject plan, final String mode, final Path source)
            throws InvalidResultException {
        if (!"chunk".equals(requiredString(plan, "/benchmark/plan/coordinate_unit", source))) {
            throw invalid(source, "/benchmark/plan/coordinate_unit", "must be chunk");
        }
        final String completionBarrier = requiredNonEmptyString(plan, "/benchmark/plan/completion_barrier", source);
        final JsonElement warmup = required(plan, "/benchmark/plan/warmup", source);
        final JsonObject test = requiredObject(plan, "/benchmark/plan/test", source);

        if ("gen".equals(mode)) {
            if (!"after_each_batch".equals(completionBarrier)) {
                throw invalid(source, "/benchmark/plan/completion_barrier", "gen results must use after_each_batch");
            }
            if (warmup.isJsonNull()) {
                throw invalid(source, "/benchmark/plan/warmup", "gen results require the built-in warmup");
            }
            validateSquarePlan(
                    requiredObject(warmup, "/benchmark/plan/warmup", source), "/benchmark/plan/warmup", source);
            requiredInt(test, "/benchmark/plan/test/first_center_x", source);
            requiredInt(test, "/benchmark/plan/test/first_center_z", source);
            final int regionRadius = nonNegativeInt(test, "/benchmark/plan/test/region_radius", source);
            final int regionCount = positiveInt(test, "/benchmark/plan/test/region_count", source);
            requiredInt(test, "/benchmark/plan/test/region_stride_x", source);
            requiredInt(test, "/benchmark/plan/test/region_stride_z", source);
            final int chunksPerRegion = positiveInt(test, "/benchmark/plan/test/chunks_per_region", source);
            final int expectedPerRegion = squareChunkCount(regionRadius, source, "/benchmark/plan/test/region_radius");
            if (chunksPerRegion != expectedPerRegion) {
                throw invalid(
                        source, "/benchmark/plan/test/chunks_per_region", "does not match the declared region radius");
            }
            final int chunkCount = positiveInt(test, "/benchmark/plan/test/chunk_count", source);
            final long expectedTotal = (long) chunksPerRegion * regionCount;
            if (chunkCount != expectedTotal) {
                throw invalid(
                        source, "/benchmark/plan/test/chunk_count", "does not equal chunks_per_region * region_count");
            }
            positiveInt(test, "/benchmark/plan/test/batch_limit", source);
            if (!"original_lightbench_center_out_rings"
                    .equals(requiredString(test, "/benchmark/plan/test/traversal", source))) {
                throw invalid(source, "/benchmark/plan/test/traversal", "is not the supported gen traversal");
            }
            return;
        }

        if ("bulk".equals(mode)) {
            if (!"once_after_complete_square".equals(completionBarrier)) {
                throw invalid(
                        source,
                        "/benchmark/plan/completion_barrier",
                        "bulk results must use once_after_complete_square");
            }
            if (!warmup.isJsonNull()) {
                validateSquarePlan(
                        requiredObject(warmup, "/benchmark/plan/warmup", source), "/benchmark/plan/warmup", source);
            }
            validateSquarePlan(test, "/benchmark/plan/test", source);
            return;
        }

        throw invalid(source, "/benchmark/mode", "only gen and bulk JSON results are comparable");
    }

    private static UpdatePlan validateUpdatePlan(final JsonObject plan, final Path source)
            throws InvalidResultException {
        requireStringValue(plan, "/benchmark/plan/coordinate_unit", "block", source);
        requireStringValue(plan, "/benchmark/plan/logical_side", "server", source);
        requireStringValue(
                plan, "/benchmark/plan/measurement_scope", "block_state_change_and_server_light_completion", source);
        requireStringValue(plan, "/benchmark/plan/primary_metric", "completion_nanos", source);
        requireStringValue(
                plan, "/benchmark/plan/timed_interval", "before_set_block_state_to_after_completion_barrier", source);
        requireStringValue(plan, "/benchmark/plan/submission_interval", "before_to_after_set_block_state", source);
        requireStringValue(plan, "/benchmark/plan/barrier_interval", "engine_specific_completion_wait", source);
        requireStringValue(plan, "/benchmark/plan/completion_barrier", "after_each_edit", source);
        requireIntValue(plan, "/benchmark/plan/update_flags", 16, source);
        requireIntValue(plan, "/benchmark/plan/warmup_pairs", 20, source);
        final int measuredPairs = requiredInt(plan, "/benchmark/plan/measured_pairs", source);
        if (measuredPairs != 200) {
            throw invalid(source, "/benchmark/plan/measured_pairs", "must be 200 for the supported update protocol");
        }
        requireBooleanValue(plan, "/benchmark/plan/same_position_each_sample", true, source);
        requireStringValue(plan, "/benchmark/plan/validation", "after_each_completion_outside_timed_interval", source);

        final JsonObject platform = requiredObject(plan, "/benchmark/plan/platform", source);
        final int baseX = requiredInt(platform, "/benchmark/plan/platform/base_x", source);
        final int baseZ = requiredInt(platform, "/benchmark/plan/platform/base_z", source);
        if (baseX != 19976 || baseZ != 19976) {
            throw invalid(
                    source, "/benchmark/plan/platform", "must use the fixed platform centered on block x/z 20008");
        }
        final int sizeX = positiveInt(platform, "/benchmark/plan/platform/size_x", source);
        final int sizeZ = positiveInt(platform, "/benchmark/plan/platform/size_z", source);
        if (sizeX != 64 || sizeZ != 64) {
            throw invalid(source, "/benchmark/plan/platform", "must be the fixed 64x64 update platform");
        }
        final int topY = requiredInt(platform, "/benchmark/plan/platform/top_y", source);
        if (topY != 254) {
            throw invalid(source, "/benchmark/plan/platform/top_y", "must be 254");
        }
        requireStringValue(platform, "/benchmark/plan/platform/block", "minecraft:stone", source);
        final int chunkHalo = nonNegativeInt(platform, "/benchmark/plan/platform/loaded_chunk_halo", source);
        if (chunkHalo != 1) {
            throw invalid(source, "/benchmark/plan/platform/loaded_chunk_halo", "must be one chunk");
        }
        final int minimumClearHeight = positiveInt(platform, "/benchmark/plan/platform/minimum_clear_height", source);
        if (minimumClearHeight != 240) {
            throw invalid(source, "/benchmark/plan/platform/minimum_clear_height", "must be 240 blocks");
        }
        final int declaredEdgeMargin =
                positiveInt(platform, "/benchmark/plan/platform/minimum_sample_edge_margin", source);
        if (declaredEdgeMargin < 24) {
            throw invalid(
                    source,
                    "/benchmark/plan/platform/minimum_sample_edge_margin",
                    "must leave at least 24 blocks around every sample center");
        }

        final JsonObject floor = requiredObject(plan, "/benchmark/plan/floor", source);
        final int floorY = requiredInt(floor, "/benchmark/plan/floor/y", source);
        if (floorY < 0) {
            throw invalid(source, "/benchmark/plan/floor/y", "must be a valid non-negative world height");
        }
        final String floorBlock = requiredNonEmptyString(floor, "/benchmark/plan/floor/block", source);
        final String floorState = requiredNonEmptyString(floor, "/benchmark/plan/floor/state", source);
        final int floorMeta = requiredInt(floor, "/benchmark/plan/floor/meta", source);
        if (floorMeta < 0 || floorMeta > 15) {
            throw invalid(source, "/benchmark/plan/floor/meta", "must be a Minecraft 1.12 block metadata value");
        }
        final long clearHeight = (long) topY - floorY - 1L;
        if (clearHeight < minimumClearHeight) {
            throw invalid(
                    source,
                    "/benchmark/plan/floor/y",
                    "does not leave the declared minimum clear height below the platform");
        }

        final JsonArray workloads = requiredArray(plan, "/benchmark/plan/workloads", source);
        if (workloads.size() != 2) {
            throw invalid(source, "/benchmark/plan/workloads", "must contain exactly the sky and block workloads");
        }
        final JsonObject sky = requiredObject(workloads.get(0), "/benchmark/plan/workloads/0", source);
        final JsonObject block = requiredObject(workloads.get(1), "/benchmark/plan/workloads/1", source);

        requireStringValue(sky, "/benchmark/plan/workloads/0/light_type", "sky", source);
        requireStringValue(sky, "/benchmark/plan/workloads/0/baseline_block", "minecraft:stone", source);
        requireStringValue(sky, "/benchmark/plan/workloads/0/changed_block", "minecraft:air", source);
        validateStringArray(
                sky, "/benchmark/plan/workloads/0/phase_order", new String[] {"sky_remove", "sky_place"}, source);
        final int[] skyWarmup = requiredBlockCoordinate(sky, "/benchmark/plan/workloads/0/warmup_position", source);
        final int[] skyMeasured = requiredBlockCoordinate(sky, "/benchmark/plan/workloads/0/measured_position", source);
        if (skyWarmup[0] != 20000 || skyWarmup[2] != 20000 || skyMeasured[0] != 20008 || skyMeasured[2] != 20008) {
            throw invalid(
                    source, "/benchmark/plan/workloads/0", "must use the fixed warmup and measured x/z positions");
        }
        if (skyWarmup[1] != topY || skyMeasured[1] != topY) {
            throw invalid(source, "/benchmark/plan/workloads/0", "sky edits must occur in the fixed platform layer");
        }

        requireStringValue(block, "/benchmark/plan/workloads/1/light_type", "block", source);
        requireStringValue(block, "/benchmark/plan/workloads/1/baseline_block", "minecraft:air", source);
        requireStringValue(block, "/benchmark/plan/workloads/1/changed_block", "minecraft:glowstone", source);
        validateStringArray(
                block, "/benchmark/plan/workloads/1/phase_order", new String[] {"block_place", "block_remove"}, source);
        final int[] blockWarmup = requiredBlockCoordinate(block, "/benchmark/plan/workloads/1/warmup_position", source);
        final int[] blockMeasured =
                requiredBlockCoordinate(block, "/benchmark/plan/workloads/1/measured_position", source);
        if (blockWarmup[1] != floorY + 1 || blockMeasured[1] != floorY + 1) {
            throw invalid(
                    source,
                    "/benchmark/plan/workloads/1",
                    "block-light edits must occur immediately above the uniform floor");
        }
        if (skyWarmup[0] != blockWarmup[0]
                || skyWarmup[2] != blockWarmup[2]
                || skyMeasured[0] != blockMeasured[0]
                || skyMeasured[2] != blockMeasured[2]) {
            throw invalid(
                    source,
                    "/benchmark/plan/workloads",
                    "sky and block workloads must share their warmup and measured x/z positions");
        }
        if (Arrays.equals(skyWarmup, skyMeasured)) {
            throw invalid(
                    source, "/benchmark/plan/workloads/0/warmup_position", "must differ from the measured position");
        }

        final int actualEdgeMargin = Math.min(
                platformEdgeMargin(skyWarmup, baseX, baseZ, sizeX, sizeZ, source),
                platformEdgeMargin(skyMeasured, baseX, baseZ, sizeX, sizeZ, source));
        if (declaredEdgeMargin != actualEdgeMargin) {
            throw invalid(
                    source,
                    "/benchmark/plan/platform/minimum_sample_edge_margin",
                    "does not match the warmup and measured positions");
        }

        final int middleY = floorY + (topY - floorY) / 2;
        final int east14 = safeIntAdd(skyMeasured[0], 14, source, "/benchmark/plan/workloads/0");
        final int east15 = safeIntAdd(skyMeasured[0], 15, source, "/benchmark/plan/workloads/0");
        final int[][] skyProbePositions = {
            {skyMeasured[0], topY - 1, skyMeasured[2]},
            {skyMeasured[0], middleY, skyMeasured[2]},
            {skyMeasured[0], floorY + 1, skyMeasured[2]},
            {east14, floorY + 1, skyMeasured[2]},
            {east15, floorY + 1, skyMeasured[2]}
        };
        validateLightExpectations(
                sky,
                "/benchmark/plan/workloads/0/open_expected_light",
                skyProbePositions,
                new int[] {15, 15, 15, 1, 0},
                source);
        validateLightExpectations(
                sky,
                "/benchmark/plan/workloads/0/closed_expected_light",
                skyProbePositions,
                new int[] {0, 0, 0, 0, 0},
                source);

        final int blockEast14 = safeIntAdd(blockMeasured[0], 14, source, "/benchmark/plan/workloads/1");
        final int blockEast15 = safeIntAdd(blockMeasured[0], 15, source, "/benchmark/plan/workloads/1");
        final int[][] blockProbePositions = {
            blockMeasured.clone(),
            {blockEast14, blockMeasured[1], blockMeasured[2]},
            {blockEast15, blockMeasured[1], blockMeasured[2]}
        };
        validateLightExpectations(
                block,
                "/benchmark/plan/workloads/1/present_expected_light",
                blockProbePositions,
                new int[] {15, 1, 0},
                source);
        validateLightExpectations(
                block,
                "/benchmark/plan/workloads/1/absent_expected_light",
                blockProbePositions,
                new int[] {0, 0, 0},
                source);

        final List<UpdatePhaseSpec> phases = Arrays.asList(
                new UpdatePhaseSpec("sky_remove", "sky", "remove", skyMeasured),
                new UpdatePhaseSpec("sky_place", "sky", "place", skyMeasured),
                new UpdatePhaseSpec("block_place", "block", "place", blockMeasured),
                new UpdatePhaseSpec("block_remove", "block", "remove", blockMeasured));
        return new UpdatePlan(
                measuredPairs,
                baseX,
                baseZ,
                sizeX,
                sizeZ,
                topY,
                chunkHalo,
                floorY,
                floorBlock,
                floorState,
                floorMeta,
                phases);
    }

    private static void validateStringArray(
            final JsonObject root, final String path, final String[] expected, final Path source)
            throws InvalidResultException {
        final JsonArray actual = requiredArray(root, path, source);
        if (actual.size() != expected.length) {
            throw invalid(source, path, "must contain exactly " + expected.length + " entries");
        }
        for (int index = 0; index < expected.length; ++index) {
            final JsonElement element = actual.get(index);
            if (!element.isJsonPrimitive()
                    || !element.getAsJsonPrimitive().isString()
                    || !expected[index].equals(element.getAsString())) {
                throw invalid(source, path + "/" + index, "must be " + expected[index]);
            }
        }
    }

    private static void validateLightExpectations(
            final JsonObject workload,
            final String path,
            final int[][] expectedPositions,
            final int[] expectedLevels,
            final Path source)
            throws InvalidResultException {
        final JsonArray expectations = requiredArray(workload, path, source);
        if (expectations.size() != expectedPositions.length || expectedPositions.length != expectedLevels.length) {
            throw invalid(source, path, "has the wrong number of fixed correctness probes");
        }
        for (int index = 0; index < expectations.size(); ++index) {
            final String itemPath = path + "/" + index;
            final JsonObject expectation = requiredObject(expectations.get(index), itemPath, source);
            final int[] position = requiredBlockCoordinate(expectation, itemPath + "/position", source);
            if (!Arrays.equals(expectedPositions[index], position)) {
                throw invalid(source, itemPath + "/position", "does not match the fixed correctness probe");
            }
            final int level = requiredInt(expectation, itemPath + "/level", source);
            if (level != expectedLevels[index]) {
                throw invalid(source, itemPath + "/level", "does not match the fixed expected light level");
            }
        }
    }

    private static int platformEdgeMargin(
            final int[] position, final int baseX, final int baseZ, final int sizeX, final int sizeZ, final Path source)
            throws InvalidResultException {
        final long maximumX = (long) baseX + sizeX - 1L;
        final long maximumZ = (long) baseZ + sizeZ - 1L;
        final long margin = Math.min(
                Math.min((long) position[0] - baseX, maximumX - position[0]),
                Math.min((long) position[2] - baseZ, maximumZ - position[2]));
        if (margin < 0 || margin > Integer.MAX_VALUE) {
            throw invalid(source, "/benchmark/plan/workloads", "sample position is outside the platform");
        }
        return (int) margin;
    }

    private static void validateUpdatePreflight(final JsonObject root, final UpdatePlan plan, final Path source)
            throws InvalidResultException {
        requireBooleanValue(root, "/benchmark/preflight/controlled_environment", true, source);
        requireBooleanValue(root, "/benchmark/preflight/platform_normalized", true, source);
        requireBooleanValue(root, "/benchmark/preflight/initial_light_verified", true, source);

        final long expectedColumns = (long) plan.sizeX * plan.sizeZ;
        final int checkedColumns = positiveInt(root, "/benchmark/preflight/checked_columns", source);
        if (checkedColumns != expectedColumns) {
            throw invalid(source, "/benchmark/preflight/checked_columns", "does not equal the complete platform area");
        }
        final long clearHeight = (long) plan.topY - plan.floorY - 1L;
        final long expectedAirBlocks;
        try {
            expectedAirBlocks = Math.multiplyExact(expectedColumns, clearHeight);
        } catch (final ArithmeticException e) {
            throw invalid(source, "/benchmark/preflight/checked_air_blocks", "expected count overflows");
        }
        final long checkedAirBlocks = positiveLong(root, "/benchmark/preflight/checked_air_blocks", source);
        if (checkedAirBlocks != expectedAirBlocks) {
            throw invalid(
                    source,
                    "/benchmark/preflight/checked_air_blocks",
                    "does not equal every clear-air block below the platform");
        }

        final long maximumX = (long) plan.baseX + plan.sizeX - 1L;
        final long maximumZ = (long) plan.baseZ + plan.sizeZ - 1L;
        if (maximumX > Integer.MAX_VALUE || maximumZ > Integer.MAX_VALUE) {
            throw invalid(source, "/benchmark/plan/platform", "platform coordinates exceed the block range");
        }
        final long minimumChunkX = Math.floorDiv((long) plan.baseX, 16L) - plan.chunkHalo;
        final long maximumChunkX = Math.floorDiv(maximumX, 16L) + plan.chunkHalo;
        final long minimumChunkZ = Math.floorDiv((long) plan.baseZ, 16L) - plan.chunkHalo;
        final long maximumChunkZ = Math.floorDiv(maximumZ, 16L) + plan.chunkHalo;
        final long expectedLoadedChunks;
        try {
            expectedLoadedChunks = Math.multiplyExact(
                    Math.addExact(Math.subtractExact(maximumChunkX, minimumChunkX), 1L),
                    Math.addExact(Math.subtractExact(maximumChunkZ, minimumChunkZ), 1L));
        } catch (final ArithmeticException e) {
            throw invalid(source, "/benchmark/preflight/loaded_chunks", "expected chunk count overflows");
        }
        if (positiveInt(root, "/benchmark/preflight/loaded_chunks", source) != expectedLoadedChunks) {
            throw invalid(
                    source,
                    "/benchmark/preflight/loaded_chunks",
                    "does not equal the platform footprint plus its declared halo");
        }

        if (requiredInt(root, "/benchmark/preflight/floor_y", source) != plan.floorY
                || !requiredNonEmptyString(root, "/benchmark/preflight/floor_block", source)
                        .equals(plan.floorBlock)
                || !requiredNonEmptyString(root, "/benchmark/preflight/floor_state", source)
                        .equals(plan.floorState)
                || requiredInt(root, "/benchmark/preflight/floor_meta", source) != plan.floorMeta) {
            throw invalid(source, "/benchmark/preflight", "floor metadata does not match the benchmark plan");
        }
    }

    private static void validateUpdateAdapters(final JsonObject root, final String engine, final Path source)
            throws InvalidResultException {
        final String expectedBarrier;
        final String expectedReader;
        if ("vanilla".equals(engine)) {
            expectedBarrier = "vanilla_inline";
            expectedReader = "world_stored_light";
        } else if ("alfheim".equals(engine)) {
            expectedBarrier = "alfheim_process_light_updates";
            expectedReader = "alfheim_cached_light";
        } else if ("pulsar".equals(engine)) {
            expectedBarrier = "pulsar_chunk_future_then_global_pending";
            expectedReader = "world_stored_light";
        } else {
            throw invalid(
                    source,
                    "/benchmark/engine",
                    "updates comparison supports only vanilla, Alfheim, and Pulsar completion adapters");
        }
        requireStringValue(root, "/benchmark/completion_adapter", expectedBarrier, source);
        requireStringValue(root, "/benchmark/verification_reader", expectedReader, source);
    }

    private static UpdatePhase readUpdatePhase(
            final JsonObject phase,
            final String path,
            final Path source,
            final UpdatePhaseSpec expected,
            final int measuredPairs,
            final String engine)
            throws InvalidResultException {
        requireStringValue(phase, path + "/name", expected.name, source);
        requireStringValue(phase, path + "/light_type", expected.lightType, source);
        requireStringValue(phase, path + "/action", expected.action, source);
        final int[] position = requiredBlockCoordinate(phase, path + "/position", source);
        if (!Arrays.equals(position, expected.position)) {
            throw invalid(source, path + "/position", "does not match the measured position in the plan");
        }
        final int sampleCount = positiveInt(phase, path + "/sample_count", source);
        if (sampleCount != measuredPairs) {
            throw invalid(source, path + "/sample_count", "does not match plan.measured_pairs");
        }
        if (!requiredBoolean(phase, path + "/all_samples_verified", source)) {
            throw invalid(source, path + "/all_samples_verified", "must be true");
        }

        final JsonArray samples = requiredArray(phase, path + "/samples", source);
        if (samples.size() != sampleCount) {
            throw invalid(source, path + "/samples", "length does not match sample_count");
        }
        final long[] submissionNanos = new long[sampleCount];
        final long[] barrierNanos = new long[sampleCount];
        final long[] completionNanos = new long[sampleCount];
        for (int index = 0; index < samples.size(); ++index) {
            final String samplePath = path + "/samples/" + index;
            final JsonObject sample = requiredObject(samples.get(index), samplePath, source);
            if (requiredInt(sample, samplePath + "/ordinal", source) != index) {
                throw invalid(source, samplePath + "/ordinal", "must equal its zero-based array index");
            }
            submissionNanos[index] = nonNegativeLong(sample, samplePath + "/submission_nanos", source);
            barrierNanos[index] = nonNegativeLong(sample, samplePath + "/barrier_nanos", source);
            completionNanos[index] = nonNegativeLong(sample, samplePath + "/completion_nanos", source);
            final long timedComponents =
                    safeAdd(submissionNanos[index], barrierNanos[index], source, samplePath + "/completion_nanos");
            if (completionNanos[index] < timedComponents) {
                throw invalid(
                        source, samplePath + "/completion_nanos", "is less than submission_nanos + barrier_nanos");
            }
            if ("vanilla".equals(engine) && barrierNanos[index] != 0) {
                throw invalid(source, samplePath + "/barrier_nanos", "must be zero for the vanilla_inline adapter");
            }
            if (!requiredBoolean(sample, samplePath + "/light_verified", source)) {
                throw invalid(source, samplePath + "/light_verified", "must be true");
            }
        }

        validateDistribution(
                requiredObject(phase, path + "/submission_summary_nanos", source),
                path + "/submission_summary_nanos",
                source,
                submissionNanos);
        validateDistribution(
                requiredObject(phase, path + "/barrier_summary_nanos", source),
                path + "/barrier_summary_nanos",
                source,
                barrierNanos);
        validateDistribution(
                requiredObject(phase, path + "/completion_summary_nanos", source),
                path + "/completion_summary_nanos",
                source,
                completionNanos);
        return new UpdatePhase(
                expected.name,
                expected.lightType,
                expected.action,
                position,
                submissionNanos,
                barrierNanos,
                completionNanos);
    }

    private static void validateSquarePlan(final JsonObject square, final String path, final Path source)
            throws InvalidResultException {
        requiredInt(square, path + "/center_x", source);
        requiredInt(square, path + "/center_z", source);
        final int radius = nonNegativeInt(square, path + "/radius", source);
        final int chunkCount = positiveInt(square, path + "/chunk_count", source);
        if (chunkCount != squareChunkCount(radius, source, path + "/radius")) {
            throw invalid(source, path + "/chunk_count", "does not match the declared square radius");
        }
    }

    private static int squareChunkCount(final int radius, final Path source, final String path)
            throws InvalidResultException {
        final long diameter = radius * 2L + 1L;
        final long chunkCount;
        try {
            chunkCount = Math.multiplyExact(diameter, diameter);
        } catch (final ArithmeticException e) {
            throw invalid(source, path, "square chunk count overflows");
        }
        if (chunkCount > Integer.MAX_VALUE) {
            throw invalid(source, path, "square chunk count is outside the supported integer range");
        }
        return (int) chunkCount;
    }

    private static Instant parseInstant(final String value, final Path source, final String path)
            throws InvalidResultException {
        try {
            return Instant.parse(value);
        } catch (final DateTimeParseException e) {
            throw invalid(source, path, "must be an ISO-8601 UTC instant");
        }
    }

    private static Phase readPhase(final JsonObject phase, final String path, final Path source)
            throws InvalidResultException {
        final String name = requiredNonEmptyString(phase, path + "/name", source);
        final int chunkCount = positiveInt(phase, path + "/chunk_count", source);
        final int declaredRegionCount = positiveInt(phase, path + "/region_count", source);
        final int declaredBatchCount = positiveInt(phase, path + "/batch_count", source);
        final int batchLimit = positiveInt(phase, path + "/batch_limit", source);
        final long provideNanos = nonNegativeLong(phase, path + "/provide_nanos", source);
        final long barrierNanos = nonNegativeLong(phase, path + "/barrier_nanos", source);
        final long totalNanos = positiveLong(phase, path + "/total_until_lit_nanos", source);

        final JsonElement workerElement = required(phase, path + "/pulsar_worker_cpu_nanos", source);
        final Long workerCpuNanos;
        if (workerElement.isJsonNull()) {
            workerCpuNanos = null;
        } else {
            workerCpuNanos = nonNegativeLong(phase, path + "/pulsar_worker_cpu_nanos", source);
        }

        final JsonArray batchJson = requiredArray(phase, path + "/batches", source);
        final JsonArray regionJson = requiredArray(phase, path + "/regions", source);
        if (batchJson.size() != declaredBatchCount) {
            throw invalid(source, path + "/batch_count", "does not match batches array length");
        }
        if (regionJson.size() != declaredRegionCount) {
            throw invalid(source, path + "/region_count", "does not match regions array length");
        }

        final int[] chunksByRegion = new int[declaredRegionCount];
        final int[] batchesByRegion = new int[declaredRegionCount];
        final int[] nextIndexByRegion = new int[declaredRegionCount];
        final long[] batchWallByRegion = new long[declaredRegionCount];
        final long[] batchWallNanos = new long[declaredBatchCount];
        long batchChunkSum = 0;
        long batchProvideSum = 0;
        long batchBarrierSum = 0;
        long batchWallSum = 0;

        for (int index = 0; index < batchJson.size(); ++index) {
            final String batchPath = path + "/batches/" + index;
            final JsonObject batch = requiredObject(batchJson.get(index), batchPath, source);
            if (requiredInt(batch, batchPath + "/ordinal", source) != index) {
                throw invalid(source, batchPath + "/ordinal", "must equal its zero-based array index");
            }
            final int regionIndex = requiredInt(batch, batchPath + "/region_index", source);
            if (regionIndex < 0 || regionIndex >= declaredRegionCount) {
                throw invalid(source, batchPath + "/region_index", "is outside the regions array");
            }
            final int firstIndex = nonNegativeInt(batch, batchPath + "/first_index_in_region", source);
            if (firstIndex != nextIndexByRegion[regionIndex]) {
                throw invalid(
                        source, batchPath + "/first_index_in_region", "is not contiguous within region " + regionIndex);
            }
            final int batchChunks = positiveInt(batch, batchPath + "/chunk_count", source);
            if (batchChunks > batchLimit) {
                throw invalid(source, batchPath + "/chunk_count", "exceeds phase batch_limit " + batchLimit);
            }
            requiredCoordinate(batch, batchPath + "/first_chunk", source);
            requiredCoordinate(batch, batchPath + "/last_chunk", source);
            final long batchProvide = nonNegativeLong(batch, batchPath + "/provide_nanos", source);
            final long batchBarrier = nonNegativeLong(batch, batchPath + "/barrier_nanos", source);
            final long batchWall = positiveLong(batch, batchPath + "/wall_nanos", source);
            if (batchWall < safeAdd(batchProvide, batchBarrier, source, batchPath)) {
                throw invalid(source, batchPath + "/wall_nanos", "is less than provide_nanos + barrier_nanos");
            }

            nextIndexByRegion[regionIndex] =
                    safeIntAdd(nextIndexByRegion[regionIndex], batchChunks, source, batchPath + "/chunk_count");
            chunksByRegion[regionIndex] =
                    safeIntAdd(chunksByRegion[regionIndex], batchChunks, source, batchPath + "/chunk_count");
            batchesByRegion[regionIndex] =
                    safeIntAdd(batchesByRegion[regionIndex], 1, source, batchPath + "/region_index");
            batchWallByRegion[regionIndex] =
                    safeAdd(batchWallByRegion[regionIndex], batchWall, source, batchPath + "/wall_nanos");
            batchChunkSum = safeAdd(batchChunkSum, batchChunks, source, batchPath + "/chunk_count");
            batchProvideSum = safeAdd(batchProvideSum, batchProvide, source, batchPath + "/provide_nanos");
            batchBarrierSum = safeAdd(batchBarrierSum, batchBarrier, source, batchPath + "/barrier_nanos");
            batchWallSum = safeAdd(batchWallSum, batchWall, source, batchPath + "/wall_nanos");
            batchWallNanos[index] = batchWall;
        }

        final long[] regionWallNanos = new long[declaredRegionCount];
        long regionChunkSum = 0;
        long regionBatchSum = 0;
        long regionWallSum = 0;
        for (int index = 0; index < regionJson.size(); ++index) {
            final String regionPath = path + "/regions/" + index;
            final JsonObject region = requiredObject(regionJson.get(index), regionPath, source);
            if (requiredInt(region, regionPath + "/index", source) != index) {
                throw invalid(source, regionPath + "/index", "must equal its zero-based array index");
            }
            final int regionChunks = positiveInt(region, regionPath + "/chunk_count", source);
            final int regionBatches = positiveInt(region, regionPath + "/batch_count", source);
            final long regionWall = positiveLong(region, regionPath + "/wall_nanos", source);
            if (regionChunks != chunksByRegion[index]) {
                throw invalid(source, regionPath + "/chunk_count", "does not match its batch samples");
            }
            if (regionBatches != batchesByRegion[index]) {
                throw invalid(source, regionPath + "/batch_count", "does not match its batch samples");
            }
            if (regionWall < batchWallByRegion[index]) {
                throw invalid(source, regionPath + "/wall_nanos", "is less than the sum of its batch wall times");
            }
            regionChunkSum = safeAdd(regionChunkSum, regionChunks, source, regionPath + "/chunk_count");
            regionBatchSum = safeAdd(regionBatchSum, regionBatches, source, regionPath + "/batch_count");
            regionWallSum = safeAdd(regionWallSum, regionWall, source, regionPath + "/wall_nanos");
            regionWallNanos[index] = regionWall;
        }

        requireEqual(chunkCount, batchChunkSum, source, path + "/chunk_count", "sum of batch chunk counts");
        requireEqual(chunkCount, regionChunkSum, source, path + "/chunk_count", "sum of region chunk counts");
        requireEqual(declaredBatchCount, regionBatchSum, source, path + "/batch_count", "sum of region batch counts");
        requireEqual(provideNanos, batchProvideSum, source, path + "/provide_nanos", "sum of batch provide times");
        requireEqual(barrierNanos, batchBarrierSum, source, path + "/barrier_nanos", "sum of batch barrier times");
        if (totalNanos < batchWallSum) {
            throw invalid(source, path + "/total_until_lit_nanos", "is less than the sum of batch wall times");
        }
        if (totalNanos < regionWallSum) {
            throw invalid(source, path + "/total_until_lit_nanos", "is less than the sum of region wall times");
        }
        if (totalNanos < safeAdd(provideNanos, barrierNanos, source, path)) {
            throw invalid(source, path + "/total_until_lit_nanos", "is less than provide_nanos + barrier_nanos");
        }

        validateDistribution(
                requiredObject(phase, path + "/batch_wall_summary_nanos", source),
                path + "/batch_wall_summary_nanos",
                source,
                batchWallNanos);
        final double recordedThroughput = requiredDouble(phase, path + "/chunks_per_second", source);
        final double calculatedThroughput = chunkCount / (totalNanos * 1.0e-9);
        if (!Double.isFinite(recordedThroughput)
                || Math.abs(recordedThroughput - calculatedThroughput)
                        > Math.max(1.0e-9, Math.abs(calculatedThroughput) * 1.0e-12)) {
            throw invalid(source, path + "/chunks_per_second", "does not match chunk_count / total_until_lit_nanos");
        }

        return new Phase(
                name,
                chunkCount,
                declaredBatchCount,
                batchLimit,
                provideNanos,
                barrierNanos,
                totalNanos,
                workerCpuNanos,
                batchWallNanos,
                regionWallNanos);
    }

    private static void validateDistribution(
            final JsonObject summary, final String path, final Path source, final long[] samples)
            throws InvalidResultException {
        final long[] sorted = samples.clone();
        Arrays.sort(sorted);
        long sum = 0;
        for (final long value : sorted) {
            sum = safeAdd(sum, value, source, path);
        }
        requireEqual(
                sorted[0], requiredLong(summary, path + "/minimum", source), source, path + "/minimum", "raw minimum");
        requireEqual(
                percentile(sorted, 0.50),
                requiredLong(summary, path + "/p50", source),
                source,
                path + "/p50",
                "raw p50");
        requireEqual(
                percentile(sorted, 0.95),
                requiredLong(summary, path + "/p95", source),
                source,
                path + "/p95",
                "raw p95");
        requireEqual(
                percentile(sorted, 0.99),
                requiredLong(summary, path + "/p99", source),
                source,
                path + "/p99",
                "raw p99");
        requireEqual(
                sorted[sorted.length - 1],
                requiredLong(summary, path + "/maximum", source),
                source,
                path + "/maximum",
                "raw maximum");
        final double recordedAverage = requiredDouble(summary, path + "/average", source);
        final double calculatedAverage = sum / (double) sorted.length;
        if (!Double.isFinite(recordedAverage)
                || Math.abs(recordedAverage - calculatedAverage)
                        > Math.max(1.0e-9, Math.abs(calculatedAverage) * 1.0e-12)) {
            throw invalid(source, path + "/average", "does not match the raw samples");
        }
    }

    private static Map<String, JsonObject> readMods(final JsonObject root, final Path source)
            throws InvalidResultException {
        final JsonArray mods = requiredArray(root, "/mods", source);
        final Map<String, JsonObject> result = new TreeMap<>();
        for (int index = 0; index < mods.size(); ++index) {
            final String path = "/mods/" + index;
            final JsonObject mod = requiredObject(mods.get(index), path, source);
            final String id = requiredNonEmptyString(mod, path + "/id", source).toLowerCase(Locale.ROOT);
            requiredNonEmptyString(mod, path + "/name", source);
            requiredNonEmptyString(mod, path + "/version", source);
            if (mod.has("source_type")) {
                final String sourceType = requiredNonEmptyString(mod, path + "/source_type", source);
                requiredNonEmptyString(mod, path + "/source_name", source);
                if ("directory".equals(sourceType)) {
                    throw invalid(
                            source,
                            path + "/source_type",
                            "directory-backed mods cannot be content-verified; benchmark packaged JARs instead");
                }
                if (!"file".equals(sourceType)) {
                    throw invalid(source, path + "/source_type", "must be file or directory");
                }
                nonNegativeLong(mod, path + "/source_size_bytes", source);
                final String sha256 = requiredNonEmptyString(mod, path + "/source_sha256", source);
                if (!isSha256(sha256)) {
                    throw invalid(source, path + "/source_sha256", "must be 64 lowercase hexadecimal digits");
                }
            } else if (mod.has("source_name") || mod.has("source_size_bytes") || mod.has("source_sha256")) {
                throw invalid(source, path + "/source_type", "is required when source metadata is present");
            } else if (!BUILTIN_RUNTIME_MOD_IDS.contains(id)) {
                throw invalid(
                        source,
                        path + "/source_type",
                        "packaged mod source metadata is required for content verification");
            }
            if (result.put(id, mod) != null) {
                throw invalid(source, path + "/id", "duplicate mod id " + id);
            }
        }
        return result;
    }

    private static void validateDetectedEngine(
            final String engine, final Map<String, JsonObject> mods, final Path source) throws InvalidResultException {
        if ("vanilla".equals(engine)) {
            for (final String engineModId : KNOWN_ENGINE_MOD_IDS) {
                if (mods.containsKey(engineModId)) {
                    throw invalid(
                            source,
                            "/benchmark/engine",
                            "detected vanilla while engine mod " + engineModId + " is active");
                }
            }
            return;
        }
        if (!mods.containsKey(engine)) {
            throw invalid(
                    source, "/benchmark/engine", "detected engine " + engine + " but no active mod has that exact id");
        }
        for (final String engineModId : KNOWN_ENGINE_MOD_IDS) {
            if (!engine.equals(engineModId) && mods.containsKey(engineModId)) {
                throw invalid(
                        source,
                        "/benchmark/engine",
                        "detected " + engine + " while engine mod " + engineModId + " is also active");
            }
        }
    }

    private static List<String> compatibilityMismatches(final List<Run> runs, final Set<String> ignoredModIds) {
        final List<String> mismatches = new ArrayList<>();
        final Run reference = runs.get(0);
        for (int index = 1; index < runs.size(); ++index) {
            final Run candidate = runs.get(index);
            compareStrictPaths(reference, candidate, COMMON_STRICT_PATHS, mismatches);
            if (reference.mode.equals(candidate.mode)) {
                compareStrictPaths(
                        reference,
                        candidate,
                        "updates".equals(reference.mode) ? UPDATE_STRICT_PATHS : CHUNK_STRICT_PATHS,
                        mismatches);
                comparePhaseLayout(reference, candidate, mismatches);
            }
            compareMods(reference, candidate, ignoredModIds, mismatches);
        }
        compareRepeatedEngineBuilds(runs, mismatches);
        return mismatches;
    }

    private static void compareStrictPaths(
            final Run reference, final Run candidate, final List<String> paths, final List<String> mismatches) {
        for (final String path : paths) {
            final JsonElement expected = valueAt(reference.root, path);
            final JsonElement actual = valueAt(candidate.root, path);
            if (!expected.equals(actual)) {
                mismatches.add(reference.label() + " vs " + candidate.label() + ": " + path + " is "
                        + renderValue(expected) + " vs " + renderValue(actual));
            }
        }
    }

    private static void compareRepeatedEngineBuilds(final List<Run> runs, final List<String> mismatches) {
        final Map<String, Run> references = new LinkedHashMap<>();
        for (final Run run : runs) {
            if ("vanilla".equals(run.engine)) {
                continue;
            }
            final Run reference = references.putIfAbsent(run.engine, run);
            if (reference == null) {
                continue;
            }
            final JsonObject expected = reference.mods.get(run.engine);
            final JsonObject actual = run.mods.get(run.engine);
            if (!expected.equals(actual)) {
                mismatches.add(reference.label() + " vs " + run.label() + ": engine mod /mods/" + run.engine
                        + " differs between repeated runs (version, source metadata, or hash)");
            }
        }
    }

    private static void comparePhaseLayout(final Run reference, final Run candidate, final List<String> mismatches) {
        final JsonArray expectedPhases =
                valueAt(reference.root, "/benchmark/phases").getAsJsonArray();
        final JsonArray actualPhases =
                valueAt(candidate.root, "/benchmark/phases").getAsJsonArray();
        if (expectedPhases.size() != actualPhases.size()) {
            mismatches.add(reference.label() + " vs " + candidate.label() + ": /benchmark/phases length is "
                    + expectedPhases.size() + " vs " + actualPhases.size());
            return;
        }
        if ("updates".equals(reference.mode)) {
            compareUpdatePhaseLayout(reference, candidate, expectedPhases, actualPhases, mismatches);
            return;
        }
        final String[] layoutFields = {"name", "chunk_count", "region_count", "batch_count", "batch_limit"};
        for (int phaseIndex = 0; phaseIndex < expectedPhases.size(); ++phaseIndex) {
            final JsonObject expected = expectedPhases.get(phaseIndex).getAsJsonObject();
            final JsonObject actual = actualPhases.get(phaseIndex).getAsJsonObject();
            boolean phaseHeaderMatches = true;
            for (final String field : layoutFields) {
                if (!expected.get(field).equals(actual.get(field))) {
                    phaseHeaderMatches = false;
                    mismatches.add(reference.label() + " vs " + candidate.label() + ": /benchmark/phases/"
                            + phaseIndex + "/" + field + " is " + renderValue(expected.get(field)) + " vs "
                            + renderValue(actual.get(field)));
                }
            }
            if (!phaseHeaderMatches) {
                continue;
            }
            if (!sameBatchLayout(
                    expected.getAsJsonArray("batches"),
                    actual.getAsJsonArray("batches"),
                    reference,
                    candidate,
                    phaseIndex,
                    mismatches)) {
                continue;
            }
            sameRegionLayout(
                    expected.getAsJsonArray("regions"),
                    actual.getAsJsonArray("regions"),
                    reference,
                    candidate,
                    phaseIndex,
                    mismatches);
        }
    }

    private static void compareUpdatePhaseLayout(
            final Run reference,
            final Run candidate,
            final JsonArray expectedPhases,
            final JsonArray actualPhases,
            final List<String> mismatches) {
        final String[] layoutFields = {
            "name", "light_type", "action", "position", "sample_count", "all_samples_verified"
        };
        for (int phaseIndex = 0; phaseIndex < expectedPhases.size(); ++phaseIndex) {
            final JsonObject expected = expectedPhases.get(phaseIndex).getAsJsonObject();
            final JsonObject actual = actualPhases.get(phaseIndex).getAsJsonObject();
            for (final String field : layoutFields) {
                if (!expected.get(field).equals(actual.get(field))) {
                    mismatches.add(reference.label() + " vs " + candidate.label() + ": /benchmark/phases/"
                            + phaseIndex + "/" + field + " is " + renderValue(expected.get(field)) + " vs "
                            + renderValue(actual.get(field)));
                }
            }
        }
    }

    private static boolean sameBatchLayout(
            final JsonArray expectedBatches,
            final JsonArray actualBatches,
            final Run reference,
            final Run candidate,
            final int phaseIndex,
            final List<String> mismatches) {
        final String[] fields = {
            "ordinal", "region_index", "first_index_in_region", "chunk_count", "first_chunk", "last_chunk"
        };
        for (int batchIndex = 0; batchIndex < expectedBatches.size(); ++batchIndex) {
            final JsonObject expected = expectedBatches.get(batchIndex).getAsJsonObject();
            final JsonObject actual = actualBatches.get(batchIndex).getAsJsonObject();
            for (final String field : fields) {
                if (!expected.get(field).equals(actual.get(field))) {
                    mismatches.add(reference.label() + " vs " + candidate.label() + ": /benchmark/phases/"
                            + phaseIndex + "/batches/" + batchIndex + "/" + field + " is "
                            + renderValue(expected.get(field)) + " vs " + renderValue(actual.get(field)));
                    return false;
                }
            }
        }
        return true;
    }

    private static void sameRegionLayout(
            final JsonArray expectedRegions,
            final JsonArray actualRegions,
            final Run reference,
            final Run candidate,
            final int phaseIndex,
            final List<String> mismatches) {
        final String[] fields = {"index", "chunk_count", "batch_count"};
        for (int regionIndex = 0; regionIndex < expectedRegions.size(); ++regionIndex) {
            final JsonObject expected = expectedRegions.get(regionIndex).getAsJsonObject();
            final JsonObject actual = actualRegions.get(regionIndex).getAsJsonObject();
            for (final String field : fields) {
                if (!expected.get(field).equals(actual.get(field))) {
                    mismatches.add(reference.label() + " vs " + candidate.label() + ": /benchmark/phases/"
                            + phaseIndex + "/regions/" + regionIndex + "/" + field + " is "
                            + renderValue(expected.get(field)) + " vs " + renderValue(actual.get(field)));
                    return;
                }
            }
        }
    }

    private static void compareMods(
            final Run reference, final Run candidate, final Set<String> ignoredModIds, final List<String> mismatches) {
        final Set<String> ids = new LinkedHashSet<>();
        ids.addAll(reference.mods.keySet());
        ids.addAll(candidate.mods.keySet());
        for (final String id : ids) {
            if (ignoredModIds.contains(id)) {
                continue;
            }
            final JsonObject expected = reference.mods.get(id);
            final JsonObject actual = candidate.mods.get(id);
            if (expected == null) {
                mismatches.add(reference.label() + " vs " + candidate.label() + ": /mods/" + id
                        + " is missing from the reference but present in the candidate");
            } else if (actual == null) {
                mismatches.add(reference.label() + " vs " + candidate.label() + ": /mods/" + id
                        + " is present in the reference but missing from the candidate");
            } else if (!expected.equals(actual)) {
                mismatches.add(reference.label() + " vs " + candidate.label() + ": /mods/" + id
                        + " differs (version, source metadata, or hash)");
            }
        }
    }

    private static String renderValue(final JsonElement value) {
        final String rendered = value.toString();
        if (rendered.length() <= MAX_RENDERED_MISMATCH_VALUE) {
            return rendered;
        }
        return rendered.substring(0, MAX_RENDERED_MISMATCH_VALUE - 3) + "...";
    }

    private static boolean isSha256(final String value) {
        return value.matches("[0-9a-f]{64}");
    }

    private static int engineOrder(final String engine) {
        return "vanilla".equals(engine) ? 0 : 1;
    }

    private static JsonElement valueAt(final JsonObject root, final String pointer) {
        JsonElement current = root;
        final String[] segments = pointer.substring(1).split("/");
        for (final String segment : segments) {
            current = current.getAsJsonObject().get(segment);
        }
        return current;
    }

    private static JsonElement required(final JsonObject root, final String pointer, final Path source)
            throws InvalidResultException {
        JsonElement current = root;
        final String[] segments = pointer.substring(1).split("/");
        int start = 0;
        while (start < segments.length && !root.has(segments[start])) {
            ++start;
        }
        if (start == segments.length) {
            throw invalid(source, pointer, "required field is missing");
        }
        for (int index = start; index < segments.length; ++index) {
            if (!current.isJsonObject()) {
                throw invalid(source, joinPointer(segments, index), "must be a JSON object");
            }
            final JsonElement next = current.getAsJsonObject().get(segments[index]);
            if (next == null) {
                throw invalid(source, joinPointer(segments, index + 1), "required field is missing");
            }
            current = next;
        }
        return current;
    }

    private static String joinPointer(final String[] segments, final int length) {
        final StringBuilder result = new StringBuilder();
        for (int index = 0; index < length; ++index) {
            result.append('/').append(segments[index]);
        }
        return result.length() == 0 ? "/" : result.toString();
    }

    private static JsonObject requiredObject(final JsonObject root, final String pointer, final Path source)
            throws InvalidResultException {
        return requiredObject(required(root, pointer, source), pointer, source);
    }

    private static JsonObject requiredObject(final JsonElement value, final String pointer, final Path source)
            throws InvalidResultException {
        if (!value.isJsonObject()) {
            throw invalid(source, pointer, "must be a JSON object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray requiredArray(final JsonObject root, final String pointer, final Path source)
            throws InvalidResultException {
        final JsonElement value = required(root, pointer, source);
        if (!value.isJsonArray()) {
            throw invalid(source, pointer, "must be a JSON array");
        }
        return value.getAsJsonArray();
    }

    private static String requiredString(final JsonObject root, final String pointer, final Path source)
            throws InvalidResultException {
        final JsonElement value = required(root, pointer, source);
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw invalid(source, pointer, "must be a JSON string");
            }
            return value.getAsString();
        } catch (final RuntimeException e) {
            throw invalid(source, pointer, "must be a JSON string");
        }
    }

    private static String requiredNonEmptyString(final JsonObject root, final String pointer, final Path source)
            throws InvalidResultException {
        final String result = requiredString(root, pointer, source);
        if (result.trim().isEmpty()) {
            throw invalid(source, pointer, "must not be empty");
        }
        return result;
    }

    private static boolean requiredBoolean(final JsonObject root, final String pointer, final Path source)
            throws InvalidResultException {
        final JsonElement value = required(root, pointer, source);
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                throw invalid(source, pointer, "must be a JSON boolean");
            }
            return value.getAsBoolean();
        } catch (final RuntimeException e) {
            throw invalid(source, pointer, "must be a JSON boolean");
        }
    }

    private static int positiveInt(final JsonObject root, final String pointer, final Path source)
            throws InvalidResultException {
        final int value = requiredInt(root, pointer, source);
        if (value <= 0) {
            throw invalid(source, pointer, "must be greater than zero");
        }
        return value;
    }

    private static int nonNegativeInt(final JsonObject root, final String pointer, final Path source)
            throws InvalidResultException {
        final int value = requiredInt(root, pointer, source);
        if (value < 0) {
            throw invalid(source, pointer, "must not be negative");
        }
        return value;
    }

    private static int requiredInt(final JsonObject root, final String pointer, final Path source)
            throws InvalidResultException {
        final long value = requiredLong(root, pointer, source);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw invalid(source, pointer, "is outside the 32-bit integer range");
        }
        return (int) value;
    }

    private static long positiveLong(final JsonObject root, final String pointer, final Path source)
            throws InvalidResultException {
        final long value = requiredLong(root, pointer, source);
        if (value <= 0) {
            throw invalid(source, pointer, "must be greater than zero");
        }
        return value;
    }

    private static long nonNegativeLong(final JsonObject root, final String pointer, final Path source)
            throws InvalidResultException {
        final long value = requiredLong(root, pointer, source);
        if (value < 0) {
            throw invalid(source, pointer, "must not be negative");
        }
        return value;
    }

    private static long requiredLong(final JsonObject root, final String pointer, final Path source)
            throws InvalidResultException {
        final JsonElement value = required(root, pointer, source);
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw invalid(source, pointer, "must be a JSON integer");
            }
            final String literal = value.getAsString();
            if (literal.indexOf('.') >= 0 || literal.indexOf('e') >= 0 || literal.indexOf('E') >= 0) {
                throw invalid(source, pointer, "must be a JSON integer");
            }
            return Long.parseLong(literal);
        } catch (final NumberFormatException | IllegalStateException e) {
            throw invalid(source, pointer, "must be a JSON integer in the signed 64-bit range");
        }
    }

    private static double requiredDouble(final JsonObject root, final String pointer, final Path source)
            throws InvalidResultException {
        final JsonElement value = required(root, pointer, source);
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw invalid(source, pointer, "must be a JSON number");
            }
            return value.getAsDouble();
        } catch (final RuntimeException e) {
            throw invalid(source, pointer, "must be a JSON number");
        }
    }

    private static NullableMeasurement nullableNonNegativeLong(
            final JsonObject root, final String pointer, final Path source) throws InvalidResultException {
        final JsonElement element = required(root, pointer, source);
        if (element.isJsonNull()) {
            return new NullableMeasurement(false, null);
        }
        return new NullableMeasurement(true, nonNegativeLong(root, pointer, source));
    }

    private static void requireStringValue(
            final JsonObject root, final String pointer, final String expected, final Path source)
            throws InvalidResultException {
        final String actual = requiredString(root, pointer, source);
        if (!expected.equals(actual)) {
            throw invalid(source, pointer, "must be " + expected);
        }
    }

    private static void requireIntValue(
            final JsonObject root, final String pointer, final int expected, final Path source)
            throws InvalidResultException {
        if (requiredInt(root, pointer, source) != expected) {
            throw invalid(source, pointer, "must be " + expected);
        }
    }

    private static void requireBooleanValue(
            final JsonObject root, final String pointer, final boolean expected, final Path source)
            throws InvalidResultException {
        if (requiredBoolean(root, pointer, source) != expected) {
            throw invalid(source, pointer, "must be " + expected);
        }
    }

    private static int[] requiredCoordinate(final JsonObject root, final String pointer, final Path source)
            throws InvalidResultException {
        final JsonArray coordinate = requiredArray(root, pointer, source);
        if (coordinate.size() != 2) {
            throw invalid(source, pointer, "must contain exactly two integer coordinates");
        }
        final int[] result = new int[2];
        for (int index = 0; index < 2; ++index) {
            final JsonElement value = coordinate.get(index);
            try {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                    throw invalid(source, pointer + "/" + index, "must be a JSON integer");
                }
                final String literal = value.getAsString();
                if (literal.indexOf('.') >= 0 || literal.indexOf('e') >= 0 || literal.indexOf('E') >= 0) {
                    throw invalid(source, pointer + "/" + index, "must be a JSON integer");
                }
                result[index] = Integer.parseInt(literal);
            } catch (final NumberFormatException | IllegalStateException e) {
                throw invalid(source, pointer + "/" + index, "must be a 32-bit JSON integer");
            }
        }
        return result;
    }

    private static int[] requiredBlockCoordinate(final JsonObject root, final String pointer, final Path source)
            throws InvalidResultException {
        final JsonArray coordinate = requiredArray(root, pointer, source);
        if (coordinate.size() != 3) {
            throw invalid(source, pointer, "must contain exactly three integer block coordinates");
        }
        final int[] result = new int[3];
        for (int index = 0; index < result.length; ++index) {
            final JsonElement value = coordinate.get(index);
            try {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                    throw invalid(source, pointer + "/" + index, "must be a JSON integer");
                }
                final String literal = value.getAsString();
                if (literal.indexOf('.') >= 0 || literal.indexOf('e') >= 0 || literal.indexOf('E') >= 0) {
                    throw invalid(source, pointer + "/" + index, "must be a JSON integer");
                }
                result[index] = Integer.parseInt(literal);
            } catch (final NumberFormatException | IllegalStateException e) {
                throw invalid(source, pointer + "/" + index, "must be a 32-bit JSON integer");
            }
        }
        return result;
    }

    private static void requireEqual(
            final long expected,
            final long actual,
            final Path source,
            final String path,
            final String expectedDescription)
            throws InvalidResultException {
        if (expected != actual) {
            throw invalid(source, path, "is " + expected + " but " + expectedDescription + " is " + actual);
        }
    }

    private static int safeIntAdd(final int left, final int right, final Path source, final String path)
            throws InvalidResultException {
        try {
            return Math.addExact(left, right);
        } catch (final ArithmeticException e) {
            throw invalid(source, path, "integer sum overflows");
        }
    }

    private static long safeAdd(final long left, final long right, final Path source, final String path)
            throws InvalidResultException {
        try {
            return Math.addExact(left, right);
        } catch (final ArithmeticException e) {
            throw invalid(source, path, "integer sum overflows");
        }
    }

    private static long percentile(final long[] sorted, final double quantile) {
        final int index = Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * quantile) - 1));
        return sorted[index];
    }

    private static InvalidResultException invalid(final Path source, final String path, final String message) {
        return new InvalidResultException(source + ": " + path + ": " + message);
    }

    static final class Result {

        private final List<Run> runs;
        private final Set<String> ignoredModIds;

        private Result(final List<Run> runs, final Set<String> ignoredModIds) {
            this.runs = Collections.unmodifiableList(new ArrayList<>(runs));
            this.ignoredModIds = Collections.unmodifiableSet(new LinkedHashSet<>(ignoredModIds));
        }

        String renderCsv() {
            if ("updates".equals(this.runs.get(0).mode)) {
                return renderUpdateCsv();
            }
            final StringBuilder output = new StringBuilder();
            output.append("run,source_file,engine,started_at_utc,mode,seed,dimension_id,chunk_count,batch_count,")
                    .append("total_nanos,provide_nanos,barrier_nanos,worker_cpu_nanos,chunks_per_second,")
                    .append("batch_p50_nanos,batch_p95_nanos,batch_p99_nanos,batch_max_nanos,")
                    .append("region_p50_nanos,region_p95_nanos,region_p99_nanos,region_max_nanos\n");
            final Map<String, Integer> engineOrdinals = new TreeMap<>();
            for (final Run run : this.runs) {
                final int ordinal = engineOrdinals.merge(run.engine, 1, Integer::sum);
                final Phase phase = run.test;
                appendCsv(output, run.engine + "-" + ordinal);
                appendCsv(output, run.source.getFileName().toString());
                appendCsv(output, run.engine);
                appendCsv(output, run.startedAtUtc);
                appendCsv(output, run.mode);
                appendCsv(output, run.seed);
                appendCsv(output, Integer.toString(run.dimensionId));
                appendCsv(output, Integer.toString(phase.chunkCount));
                appendCsv(output, Integer.toString(phase.batchCount));
                appendCsv(output, Long.toString(phase.totalNanos));
                appendCsv(output, Long.toString(phase.provideNanos));
                appendCsv(output, Long.toString(phase.barrierNanos));
                appendCsv(output, phase.workerCpuNanos == null ? "" : Long.toString(phase.workerCpuNanos));
                appendCsv(output, String.format(Locale.ROOT, "%.6f", phase.throughput()));
                appendCsv(output, Long.toString(phase.batchPercentile(0.50)));
                appendCsv(output, Long.toString(phase.batchPercentile(0.95)));
                appendCsv(output, Long.toString(phase.batchPercentile(0.99)));
                appendCsv(output, Long.toString(phase.batchMaximum()));
                appendCsv(output, Long.toString(phase.regionPercentile(0.50)));
                appendCsv(output, Long.toString(phase.regionPercentile(0.95)));
                appendCsv(output, Long.toString(phase.regionPercentile(0.99)));
                appendCsv(output, Long.toString(phase.regionMaximum()), true);
            }
            return output.toString();
        }

        private String renderUpdateCsv() {
            final StringBuilder output = new StringBuilder();
            output.append("run,source_file,engine,started_at_utc,mode,seed,dimension_id,phase,light_type,action,")
                    .append("position_x,position_y,position_z,sample_count,completion_average_nanos,")
                    .append("completion_p50_nanos,completion_p95_nanos,completion_p99_nanos,completion_max_nanos,")
                    .append("submission_average_nanos,submission_p50_nanos,submission_p95_nanos,")
                    .append("submission_p99_nanos,submission_max_nanos,barrier_average_nanos,barrier_p50_nanos,")
                    .append("barrier_p95_nanos,barrier_p99_nanos,barrier_max_nanos,")
                    .append("pulsar_worker_cpu_nanos_all_phases,")
                    .append("gc_collection_count_delta,gc_collection_time_millis_delta\n");
            final Map<String, Integer> engineOrdinals = new TreeMap<>();
            for (final Run run : this.runs) {
                final int ordinal = engineOrdinals.merge(run.engine, 1, Integer::sum);
                final String runName = run.engine + "-" + ordinal;
                for (final UpdatePhase phase : run.updatePhases) {
                    appendCsv(output, runName);
                    appendCsv(output, run.source.getFileName().toString());
                    appendCsv(output, run.engine);
                    appendCsv(output, run.startedAtUtc);
                    appendCsv(output, run.mode);
                    appendCsv(output, run.seed);
                    appendCsv(output, Integer.toString(run.dimensionId));
                    appendCsv(output, phase.name);
                    appendCsv(output, phase.lightType);
                    appendCsv(output, phase.action);
                    appendCsv(output, Integer.toString(phase.position[0]));
                    appendCsv(output, Integer.toString(phase.position[1]));
                    appendCsv(output, Integer.toString(phase.position[2]));
                    appendCsv(output, Integer.toString(phase.sampleCount()));
                    appendCsv(output, decimalNanos(phase.completionAverage()));
                    appendCsv(output, Long.toString(phase.completionPercentile(0.50)));
                    appendCsv(output, Long.toString(phase.completionPercentile(0.95)));
                    appendCsv(output, Long.toString(phase.completionPercentile(0.99)));
                    appendCsv(output, Long.toString(phase.completionMaximum()));
                    appendCsv(output, decimalNanos(phase.submissionAverage()));
                    appendCsv(output, Long.toString(phase.submissionPercentile(0.50)));
                    appendCsv(output, Long.toString(phase.submissionPercentile(0.95)));
                    appendCsv(output, Long.toString(phase.submissionPercentile(0.99)));
                    appendCsv(output, Long.toString(phase.submissionMaximum()));
                    appendCsv(output, decimalNanos(phase.barrierAverage()));
                    appendCsv(output, Long.toString(phase.barrierPercentile(0.50)));
                    appendCsv(output, Long.toString(phase.barrierPercentile(0.95)));
                    appendCsv(output, Long.toString(phase.barrierPercentile(0.99)));
                    appendCsv(output, Long.toString(phase.barrierMaximum()));
                    appendCsv(output, nullableLong(run.updateWorkerCpuNanos));
                    appendCsv(output, nullableLong(run.gcCollectionCountDelta));
                    appendCsv(output, nullableLong(run.gcCollectionTimeMillisDelta), true);
                }
            }
            return output.toString();
        }

        String renderMarkdown() {
            if ("updates".equals(this.runs.get(0).mode)) {
                return renderUpdateMarkdown();
            }
            final Run reference = this.runs.get(0);
            final StringBuilder output = new StringBuilder();
            output.append("# Lightbench comparison\n\n")
                    .append("All ")
                    .append(this.runs.size())
                    .append(" result files passed schema and raw-sample validation. Strict comparison found matching ")
                    .append("benchmark plans, seed, dimension, Lightbench/runtime environment, world settings, config ")
                    .append("fingerprint and non-engine mods.\n\n")
                    .append("- Mode: `")
                    .append(markdown(reference.mode))
                    .append("`\n- Seed: `")
                    .append(markdown(reference.seed))
                    .append("`\n- Dimension: `")
                    .append(reference.dimensionId)
                    .append("`\n- Test chunks per run: ")
                    .append(reference.test.chunkCount)
                    .append("\n- Engine mod IDs excluded from mod-list equality: ")
                    .append(this.ignoredModIds.isEmpty() ? "none" : backtickList(this.ignoredModIds))
                    .append("\n- Aggregate p50 uses the same nearest-rank definition as Lightbench.\n\n")
                    .append("## Engine summary\n\n")
                    .append("| Engine | Runs | Median total (s) | Total range (s) | Median chunks/s | ")
                    .append("Median batch p99 (ms) | Median region p95 (s) | vs vanilla |\n")
                    .append("|---|---:|---:|---:|---:|---:|---:|---:|\n");

            final Map<String, List<Run>> grouped = groupedRuns();
            final Long vanillaMedian = grouped.containsKey("vanilla") ? medianTotals(grouped.get("vanilla")) : null;
            for (final Map.Entry<String, List<Run>> entry : grouped.entrySet()) {
                final List<Run> engineRuns = entry.getValue();
                final long medianTotal = medianTotals(engineRuns);
                final long minimumTotal = engineRuns.stream()
                        .mapToLong(run -> run.test.totalNanos)
                        .min()
                        .orElseThrow(AssertionError::new);
                final long maximumTotal = engineRuns.stream()
                        .mapToLong(run -> run.test.totalNanos)
                        .max()
                        .orElseThrow(AssertionError::new);
                final long medianThroughputMicros = nearestRank(
                        engineRuns.stream()
                                .mapToLong(run -> Math.round(run.test.throughput() * 1_000_000.0))
                                .toArray(),
                        0.50);
                final long medianBatchP99 = nearestRank(
                        engineRuns.stream()
                                .mapToLong(run -> run.test.batchPercentile(0.99))
                                .toArray(),
                        0.50);
                final long medianRegionP95 = nearestRank(
                        engineRuns.stream()
                                .mapToLong(run -> run.test.regionPercentile(0.95))
                                .toArray(),
                        0.50);
                output.append("| ")
                        .append(markdown(entry.getKey()))
                        .append(" | ")
                        .append(engineRuns.size())
                        .append(" | ")
                        .append(seconds(medianTotal))
                        .append(" | ")
                        .append(seconds(minimumTotal))
                        .append("–")
                        .append(seconds(maximumTotal))
                        .append(" | ")
                        .append(String.format(Locale.ROOT, "%.1f", medianThroughputMicros / 1_000_000.0))
                        .append(" | ")
                        .append(milliseconds(medianBatchP99))
                        .append(" | ")
                        .append(seconds(medianRegionP95))
                        .append(" | ")
                        .append(
                                vanillaMedian == null
                                        ? "n/a"
                                        : String.format(Locale.ROOT, "%.2fx", vanillaMedian / (double) medianTotal))
                        .append(" |\n");
            }

            output.append("\n## Individual runs\n\n")
                    .append("| Run | Source file | Engine | Total (s) | Chunks/s | Batch p99 (ms) | ")
                    .append("Region p95 (s) | Provide (s) | Barrier (s) |\n")
                    .append("|---|---|---|---:|---:|---:|---:|---:|---:|\n");
            final Map<String, Integer> engineOrdinals = new TreeMap<>();
            for (final Run run : this.runs) {
                final int ordinal = engineOrdinals.merge(run.engine, 1, Integer::sum);
                output.append("| ")
                        .append(markdown(run.engine))
                        .append("-")
                        .append(ordinal)
                        .append(" | ")
                        .append(markdown(run.source.getFileName().toString()))
                        .append(" | ")
                        .append(markdown(run.engine))
                        .append(" | ")
                        .append(seconds(run.test.totalNanos))
                        .append(" | ")
                        .append(String.format(Locale.ROOT, "%.1f", run.test.throughput()))
                        .append(" | ")
                        .append(milliseconds(run.test.batchPercentile(0.99)))
                        .append(" | ")
                        .append(seconds(run.test.regionPercentile(0.95)))
                        .append(" | ")
                        .append(seconds(run.test.provideNanos))
                        .append(" | ")
                        .append(seconds(run.test.barrierNanos))
                        .append(" |\n");
            }
            output.append("\nThe CSV beside this file contains integer nanoseconds for every run. Aggregates are ")
                    .append(
                            "descriptive summaries, not confidence intervals; keep individual runs when publishing results.\n");
            return output.toString();
        }

        private String renderUpdateMarkdown() {
            final Run reference = this.runs.get(0);
            final JsonObject plan = valueAt(reference.root, "/benchmark/plan").getAsJsonObject();
            final JsonObject platform = plan.getAsJsonObject("platform");
            final StringBuilder output = new StringBuilder();
            output.append("# Lightbench light-update comparison\n\n")
                    .append("All ")
                    .append(this.runs.size())
                    .append(" result files passed schema, fixed-protocol, raw-sample and per-edit correctness ")
                    .append("validation. Strict comparison found matching plans, seed, dimension, server/runtime ")
                    .append(
                            "environment, world settings, controlled preflight, config fingerprint and non-engine mods.\n\n")
                    .append("- Mode: `updates`\n- Seed: `")
                    .append(markdown(reference.seed))
                    .append("`\n- Dimension: `")
                    .append(reference.dimensionId)
                    .append("`\n- Controlled platform: ")
                    .append(platform.get("size_x").getAsInt())
                    .append("x")
                    .append(platform.get("size_z").getAsInt())
                    .append(" stone blocks at y=")
                    .append(platform.get("top_y").getAsInt())
                    .append("\n- Warmup: ")
                    .append(plan.get("warmup_pairs").getAsInt())
                    .append(" pairs per workload\n- Measured samples: ")
                    .append(plan.get("measured_pairs").getAsInt())
                    .append(
                            " per phase\n- Primary metric: completion time from immediately before the block edit until ")
                    .append(
                            "the engine-specific server-light completion barrier returns\n- Engine mod IDs excluded from ")
                    .append("mod-list equality: ")
                    .append(this.ignoredModIds.isEmpty() ? "none" : backtickList(this.ignoredModIds))
                    .append("\n- Aggregate medians use Lightbench's nearest-rank definition across runs.\n\n")
                    .append("## Engine summary\n");

            final Map<String, List<Run>> grouped = groupedRuns();
            for (final String phaseName : UPDATE_PHASE_NAMES) {
                output.append("\n### `")
                        .append(phaseName)
                        .append("`\n\n")
                        .append("| Engine | Runs | Median completion p50 (ms) | p50 range (ms) | ")
                        .append("Median p95 (ms) | Median p99 (ms) | vs vanilla |\n")
                        .append("|---|---:|---:|---:|---:|---:|---:|\n");
                final Long vanillaMedian = grouped.containsKey("vanilla")
                        ? medianUpdatePercentile(grouped.get("vanilla"), phaseName, 0.50)
                        : null;
                for (final Map.Entry<String, List<Run>> entry : grouped.entrySet()) {
                    final List<Run> engineRuns = entry.getValue();
                    final long medianP50 = medianUpdatePercentile(engineRuns, phaseName, 0.50);
                    final long minimumP50 = engineRuns.stream()
                            .mapToLong(run -> run.updatePhase(phaseName).completionPercentile(0.50))
                            .min()
                            .orElseThrow(AssertionError::new);
                    final long maximumP50 = engineRuns.stream()
                            .mapToLong(run -> run.updatePhase(phaseName).completionPercentile(0.50))
                            .max()
                            .orElseThrow(AssertionError::new);
                    output.append("| ")
                            .append(markdown(entry.getKey()))
                            .append(" | ")
                            .append(engineRuns.size())
                            .append(" | ")
                            .append(milliseconds(medianP50))
                            .append(" | ")
                            .append(milliseconds(minimumP50))
                            .append("–")
                            .append(milliseconds(maximumP50))
                            .append(" | ")
                            .append(milliseconds(medianUpdatePercentile(engineRuns, phaseName, 0.95)))
                            .append(" | ")
                            .append(milliseconds(medianUpdatePercentile(engineRuns, phaseName, 0.99)))
                            .append(" | ")
                            .append(speedRatio(vanillaMedian, medianP50))
                            .append(" |\n");
                }
            }

            output.append("\n## Individual phase results\n\n")
                    .append("| Run | Source file | Engine | Phase | Completion p50 (ms) | Completion p95 (ms) | ")
                    .append("Completion p99 (ms) | Completion max (ms) | Submission p50 (ms) | Barrier p50 (ms) |\n")
                    .append("|---|---|---|---|---:|---:|---:|---:|---:|---:|\n");
            final Map<String, Integer> engineOrdinals = new TreeMap<>();
            final Map<Run, String> runNames = new LinkedHashMap<>();
            for (final Run run : this.runs) {
                final String runName = run.engine + "-" + engineOrdinals.merge(run.engine, 1, Integer::sum);
                runNames.put(run, runName);
                for (final UpdatePhase phase : run.updatePhases) {
                    output.append("| ")
                            .append(markdown(runName))
                            .append(" | ")
                            .append(markdown(run.source.getFileName().toString()))
                            .append(" | ")
                            .append(markdown(run.engine))
                            .append(" | ")
                            .append(markdown(phase.name))
                            .append(" | ")
                            .append(milliseconds(phase.completionPercentile(0.50)))
                            .append(" | ")
                            .append(milliseconds(phase.completionPercentile(0.95)))
                            .append(" | ")
                            .append(milliseconds(phase.completionPercentile(0.99)))
                            .append(" | ")
                            .append(milliseconds(phase.completionMaximum()))
                            .append(" | ")
                            .append(milliseconds(phase.submissionPercentile(0.50)))
                            .append(" | ")
                            .append(milliseconds(phase.barrierPercentile(0.50)))
                            .append(" |\n");
                }
            }

            output.append("\n## Run-level observations\n\n")
                    .append(
                            "| Run | Measurement GC collections | Measurement GC time (ms) | Pulsar worker CPU (ms) |\n")
                    .append("|---|---:|---:|---:|\n");
            for (final Run run : this.runs) {
                output.append("| ")
                        .append(markdown(runNames.get(run)))
                        .append(" | ")
                        .append(nullableLongOrNa(run.gcCollectionCountDelta))
                        .append(" | ")
                        .append(nullableLongOrNa(run.gcCollectionTimeMillisDelta))
                        .append(" | ")
                        .append(run.updateWorkerCpuNanos == null ? "n/a" : milliseconds(run.updateWorkerCpuNanos))
                        .append(" |\n");
            }
            output.append("\nCompletion is the primary cross-engine metric. Submission and barrier columns are ")
                    .append(
                            "diagnostics: engines divide the same end-to-end interval differently, and vanilla performs ")
                    .append(
                            "lighting inline with a zero-duration completion adapter. GC and worker CPU are observations, ")
                    .append("not compatibility conditions. The CSV beside this file contains raw-derived nanosecond ")
                    .append("metrics for every phase. Aggregates are descriptive summaries, not confidence intervals; ")
                    .append("keep the validated JSON files and individual runs when publishing results.\n");
            return output.toString();
        }

        int runCount() {
            return this.runs.size();
        }

        private Map<String, List<Run>> groupedRuns() {
            final Map<String, List<Run>> grouped = new LinkedHashMap<>();
            for (final Run run : this.runs) {
                grouped.computeIfAbsent(run.engine, ignored -> new ArrayList<>())
                        .add(run);
            }
            return grouped;
        }

        private static long medianTotals(final List<Run> runs) {
            return nearestRank(
                    runs.stream().mapToLong(run -> run.test.totalNanos).toArray(), 0.50);
        }

        private static long medianUpdatePercentile(
                final List<Run> runs, final String phaseName, final double sampleQuantile) {
            return nearestRank(
                    runs.stream()
                            .mapToLong(run -> run.updatePhase(phaseName).completionPercentile(sampleQuantile))
                            .toArray(),
                    0.50);
        }

        private static long nearestRank(final long[] values, final double quantile) {
            final long[] sorted = values.clone();
            Arrays.sort(sorted);
            return percentile(sorted, quantile);
        }

        private static void appendCsv(final StringBuilder output, final String value) {
            appendCsv(output, value, false);
        }

        private static void appendCsv(final StringBuilder output, final String value, final boolean last) {
            output.append('"').append(value.replace("\"", "\"\"")).append('"').append(last ? '\n' : ',');
        }

        private static String seconds(final long nanos) {
            return String.format(Locale.ROOT, "%.3f", nanos * 1.0e-9);
        }

        private static String milliseconds(final long nanos) {
            return String.format(Locale.ROOT, "%.3f", nanos * 1.0e-6);
        }

        private static String decimalNanos(final double nanos) {
            return String.format(Locale.ROOT, "%.6f", nanos);
        }

        private static String nullableLong(final Long value) {
            return value == null ? "" : Long.toString(value);
        }

        private static String nullableLongOrNa(final Long value) {
            return value == null ? "n/a" : Long.toString(value);
        }

        private static String speedRatio(final Long vanillaMedian, final long engineMedian) {
            if (vanillaMedian == null || vanillaMedian <= 0 || engineMedian <= 0) {
                return "n/a";
            }
            return String.format(Locale.ROOT, "%.2fx", vanillaMedian / (double) engineMedian);
        }

        private static String markdown(final String value) {
            return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
        }

        private static String backtickList(final Set<String> values) {
            final StringBuilder result = new StringBuilder();
            for (final String value : values) {
                if (result.length() > 0) {
                    result.append(", ");
                }
                result.append('`').append(markdown(value)).append('`');
            }
            return result.toString();
        }
    }

    static final class InvalidResultException extends Exception {

        private InvalidResultException(final String message) {
            super(message);
        }
    }

    static final class IncompatibleResultsException extends Exception {

        private final List<String> mismatches;

        private IncompatibleResultsException(final List<String> mismatches) {
            super(mismatches.size() + " compatibility mismatch(es)");
            this.mismatches = Collections.unmodifiableList(new ArrayList<>(mismatches));
        }

        List<String> mismatches() {
            return this.mismatches;
        }
    }

    private static final class NullableMeasurement {

        private final boolean present;
        private final Long value;

        private NullableMeasurement(final boolean present, final Long value) {
            this.present = present;
            this.value = value;
        }
    }

    private static final class UpdatePlan {

        private final int measuredPairs;
        private final int baseX;
        private final int baseZ;
        private final int sizeX;
        private final int sizeZ;
        private final int topY;
        private final int chunkHalo;
        private final int floorY;
        private final String floorBlock;
        private final String floorState;
        private final int floorMeta;
        private final List<UpdatePhaseSpec> phases;

        private UpdatePlan(
                final int measuredPairs,
                final int baseX,
                final int baseZ,
                final int sizeX,
                final int sizeZ,
                final int topY,
                final int chunkHalo,
                final int floorY,
                final String floorBlock,
                final String floorState,
                final int floorMeta,
                final List<UpdatePhaseSpec> phases) {
            this.measuredPairs = measuredPairs;
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
            this.topY = topY;
            this.chunkHalo = chunkHalo;
            this.floorY = floorY;
            this.floorBlock = floorBlock;
            this.floorState = floorState;
            this.floorMeta = floorMeta;
            this.phases = Collections.unmodifiableList(new ArrayList<>(phases));
        }
    }

    private static final class UpdatePhaseSpec {

        private final String name;
        private final String lightType;
        private final String action;
        private final int[] position;

        private UpdatePhaseSpec(final String name, final String lightType, final String action, final int[] position) {
            this.name = name;
            this.lightType = lightType;
            this.action = action;
            this.position = position.clone();
        }
    }

    private static final class Run {

        private final Path source;
        private final JsonObject root;
        private final Map<String, JsonObject> mods;
        private final String mode;
        private final String engine;
        private final String startedAtUtc;
        private final String seed;
        private final int dimensionId;
        private final Phase test;
        private final List<UpdatePhase> updatePhases;
        private final Long updateWorkerCpuNanos;
        private final Long gcCollectionCountDelta;
        private final Long gcCollectionTimeMillisDelta;

        private Run(
                final Path source,
                final JsonObject root,
                final Map<String, JsonObject> mods,
                final String mode,
                final String engine,
                final String startedAtUtc,
                final String seed,
                final int dimensionId,
                final Phase test,
                final List<UpdatePhase> updatePhases,
                final Long updateWorkerCpuNanos,
                final Long gcCollectionCountDelta,
                final Long gcCollectionTimeMillisDelta) {
            this.source = source;
            this.root = root;
            this.mods = mods;
            this.mode = mode;
            this.engine = engine;
            this.startedAtUtc = startedAtUtc;
            this.seed = seed;
            this.dimensionId = dimensionId;
            this.test = test;
            this.updatePhases = Collections.unmodifiableList(new ArrayList<>(updatePhases));
            this.updateWorkerCpuNanos = updateWorkerCpuNanos;
            this.gcCollectionCountDelta = gcCollectionCountDelta;
            this.gcCollectionTimeMillisDelta = gcCollectionTimeMillisDelta;
        }

        private String label() {
            return this.source.getFileName() + " (" + this.engine + ")";
        }

        private UpdatePhase updatePhase(final String name) {
            for (final UpdatePhase phase : this.updatePhases) {
                if (name.equals(phase.name)) {
                    return phase;
                }
            }
            throw new AssertionError("validated update phase is missing: " + name);
        }
    }

    private static final class UpdatePhase {

        private final String name;
        private final String lightType;
        private final String action;
        private final int[] position;
        private final long[] sortedSubmissionNanos;
        private final long[] sortedBarrierNanos;
        private final long[] sortedCompletionNanos;
        private final long submissionSumNanos;
        private final long barrierSumNanos;
        private final long completionSumNanos;

        private UpdatePhase(
                final String name,
                final String lightType,
                final String action,
                final int[] position,
                final long[] submissionNanos,
                final long[] barrierNanos,
                final long[] completionNanos) {
            this.name = name;
            this.lightType = lightType;
            this.action = action;
            this.position = position.clone();
            this.sortedSubmissionNanos = submissionNanos.clone();
            this.sortedBarrierNanos = barrierNanos.clone();
            this.sortedCompletionNanos = completionNanos.clone();
            Arrays.sort(this.sortedSubmissionNanos);
            Arrays.sort(this.sortedBarrierNanos);
            Arrays.sort(this.sortedCompletionNanos);
            this.submissionSumNanos = sum(this.sortedSubmissionNanos);
            this.barrierSumNanos = sum(this.sortedBarrierNanos);
            this.completionSumNanos = sum(this.sortedCompletionNanos);
        }

        private int sampleCount() {
            return this.sortedCompletionNanos.length;
        }

        private double submissionAverage() {
            return this.submissionSumNanos / (double) sampleCount();
        }

        private double barrierAverage() {
            return this.barrierSumNanos / (double) sampleCount();
        }

        private double completionAverage() {
            return this.completionSumNanos / (double) sampleCount();
        }

        private long submissionPercentile(final double quantile) {
            return percentile(this.sortedSubmissionNanos, quantile);
        }

        private long barrierPercentile(final double quantile) {
            return percentile(this.sortedBarrierNanos, quantile);
        }

        private long completionPercentile(final double quantile) {
            return percentile(this.sortedCompletionNanos, quantile);
        }

        private long submissionMaximum() {
            return this.sortedSubmissionNanos[this.sortedSubmissionNanos.length - 1];
        }

        private long barrierMaximum() {
            return this.sortedBarrierNanos[this.sortedBarrierNanos.length - 1];
        }

        private long completionMaximum() {
            return this.sortedCompletionNanos[this.sortedCompletionNanos.length - 1];
        }

        private static long sum(final long[] values) {
            long result = 0;
            for (final long value : values) {
                result = Math.addExact(result, value);
            }
            return result;
        }
    }

    private static final class Phase {

        private final String name;
        private final int chunkCount;
        private final int batchCount;
        private final int batchLimit;
        private final long provideNanos;
        private final long barrierNanos;
        private final long totalNanos;
        private final Long workerCpuNanos;
        private final long[] sortedBatchWallNanos;
        private final long[] sortedRegionWallNanos;

        private Phase(
                final String name,
                final int chunkCount,
                final int batchCount,
                final int batchLimit,
                final long provideNanos,
                final long barrierNanos,
                final long totalNanos,
                final Long workerCpuNanos,
                final long[] batchWallNanos,
                final long[] regionWallNanos) {
            this.name = name;
            this.chunkCount = chunkCount;
            this.batchCount = batchCount;
            this.batchLimit = batchLimit;
            this.provideNanos = provideNanos;
            this.barrierNanos = barrierNanos;
            this.totalNanos = totalNanos;
            this.workerCpuNanos = workerCpuNanos;
            this.sortedBatchWallNanos = batchWallNanos.clone();
            this.sortedRegionWallNanos = regionWallNanos.clone();
            Arrays.sort(this.sortedBatchWallNanos);
            Arrays.sort(this.sortedRegionWallNanos);
        }

        private double throughput() {
            return this.chunkCount / (this.totalNanos * 1.0e-9);
        }

        private long batchPercentile(final double quantile) {
            return percentile(this.sortedBatchWallNanos, quantile);
        }

        private long batchMaximum() {
            return this.sortedBatchWallNanos[this.sortedBatchWallNanos.length - 1];
        }

        private long regionPercentile(final double quantile) {
            return percentile(this.sortedRegionWallNanos, quantile);
        }

        private long regionMaximum() {
            return this.sortedRegionWallNanos[this.sortedRegionWallNanos.length - 1];
        }
    }
}
