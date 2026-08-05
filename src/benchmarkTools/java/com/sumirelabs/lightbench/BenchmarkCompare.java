package com.sumirelabs.lightbench;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/** Command-line entry point for strict, offline comparison of Lightbench JSON results. */
public final class BenchmarkCompare {

    private static final DateTimeFormatter DIRECTORY_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private BenchmarkCompare() {}

    public static void main(final String[] arguments) {
        final int exitCode = run(arguments, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(final String[] arguments, final PrintStream output, final PrintStream error) {
        try {
            final Options options = Options.parse(arguments);
            if (options.help) {
                output.print(usage());
                return 0;
            }

            final List<Path> resultFiles = discoverResults(options.resultRoots);
            output.println("Validating " + resultFiles.size() + " Lightbench result files...");
            final BenchmarkComparison.Result comparison =
                    BenchmarkComparison.compare(resultFiles, options.ignoredModIds);

            final Path comparisonDirectory = createUniqueComparisonDirectory(options.outputRoot);
            final Path markdown = comparisonDirectory.resolve("comparison.md");
            final Path csv = comparisonDirectory.resolve("comparison.csv");
            Files.write(
                    markdown,
                    comparison.renderMarkdown().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            Files.write(
                    csv,
                    comparison.renderCsv().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);

            output.println("Compatibility checks passed for " + comparison.runCount() + " runs.");
            output.println("Markdown: " + markdown.toAbsolutePath().normalize());
            output.println("CSV: " + csv.toAbsolutePath().normalize());
            return 0;
        } catch (final BenchmarkComparison.IncompatibleResultsException e) {
            error.println("Results are not comparable; no report was written.");
            for (final String mismatch : e.mismatches()) {
                error.println("- " + mismatch);
            }
            return 2;
        } catch (final BenchmarkComparison.InvalidResultException | IllegalArgumentException e) {
            error.println("Invalid Lightbench comparison input: " + e.getMessage());
            error.println("Use --help for command syntax. No report was written.");
            return 1;
        } catch (final IOException e) {
            error.println("Could not read or write Lightbench results: " + e.getMessage());
            return 1;
        }
    }

    private static List<Path> discoverResults(final List<String> roots) throws IOException {
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("at least one --results path is required");
        }
        final Set<Path> files = new LinkedHashSet<>();
        for (final String rootSpecification : roots) {
            for (final Path root : expandPathList(rootSpecification)) {
                final Path normalized = root.toAbsolutePath().normalize();
                if (Files.isRegularFile(normalized)) {
                    if (!isJson(normalized)) {
                        throw new IllegalArgumentException("result file must end in .json: " + normalized);
                    }
                    files.add(normalized);
                } else if (Files.isDirectory(normalized)) {
                    try (Stream<Path> paths = Files.walk(normalized)) {
                        paths.filter(Files::isRegularFile)
                                .filter(BenchmarkCompare::isJson)
                                .map(path -> path.toAbsolutePath().normalize())
                                .forEach(files::add);
                    }
                } else {
                    throw new IllegalArgumentException("result path does not exist: " + normalized);
                }
            }
        }
        final List<Path> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(Path::toString));
        if (sorted.size() < 2) {
            throw new IllegalArgumentException(
                    "found " + sorted.size() + " JSON result file(s); at least two are required");
        }
        return sorted;
    }

    private static List<Path> expandPathList(final String specification) {
        final Path literal = Paths.get(specification);
        if (Files.exists(literal) || specification.indexOf(File.pathSeparatorChar) < 0) {
            return Collections.singletonList(literal);
        }
        final List<Path> paths = new ArrayList<>();
        for (final String component : specification.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!component.trim().isEmpty()) {
                paths.add(Paths.get(component.trim()));
            }
        }
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("empty --results path list");
        }
        return paths;
    }

    private static boolean isJson(final Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
    }

    private static Path createUniqueComparisonDirectory(final Path outputRoot) throws IOException {
        final Path normalizedRoot = outputRoot.toAbsolutePath().normalize();
        Files.createDirectories(normalizedRoot);
        final String prefix = "comparison-" + DIRECTORY_TIME.format(Instant.now());
        for (int suffix = 0; suffix < 1000; ++suffix) {
            final String numberedSuffix = suffix == 0 ? "" : "-" + suffix;
            final Path candidate = normalizedRoot.resolve(prefix + numberedSuffix);
            try {
                return Files.createDirectory(candidate);
            } catch (final FileAlreadyExistsException ignored) {
                // Retain earlier comparisons and choose the next deterministic suffix.
            }
        }
        throw new IOException("could not allocate a unique comparison directory below " + normalizedRoot);
    }

    private static String usage() {
        return "Strictly validate and compare Lightbench schema-1 JSON results.\n\n"
                + "Usage:\n"
                + "  BenchmarkCompare --results <file-or-directory> [--results <path> ...]\n"
                + "                   [--output <directory>] [--ignore-mods <id,id,...>]\n\n"
                + "Directories are searched recursively for .json files. A --results value may also contain\n"
                + "multiple paths separated by the platform path separator ('"
                + File.pathSeparator
                + "' on this system). The default output root is build/lightbench-comparisons.\n\n"
                + "Exit codes: 0 = reports written, 1 = invalid input or I/O failure,\n"
                + "            2 = valid result files with incompatible benchmark conditions.\n";
    }

    private static final class Options {

        private final List<String> resultRoots;
        private final Path outputRoot;
        private final Set<String> ignoredModIds;
        private final boolean help;

        private Options(
                final List<String> resultRoots,
                final Path outputRoot,
                final Set<String> ignoredModIds,
                final boolean help) {
            this.resultRoots = resultRoots;
            this.outputRoot = outputRoot;
            this.ignoredModIds = ignoredModIds;
            this.help = help;
        }

        private static Options parse(final String[] arguments) {
            final List<String> resultRoots = new ArrayList<>();
            Path outputRoot = Paths.get("build", "lightbench-comparisons");
            final Set<String> ignoredModIds = new LinkedHashSet<>();
            boolean help = false;
            for (int index = 0; index < arguments.length; ++index) {
                final String argument = arguments[index];
                switch (argument) {
                    case "--results":
                        resultRoots.add(requireValue(arguments, ++index, argument));
                        break;
                    case "--output":
                        outputRoot = Paths.get(requireValue(arguments, ++index, argument));
                        break;
                    case "--ignore-mods":
                        for (final String id :
                                requireValue(arguments, ++index, argument).split(",")) {
                            final String normalized = id.trim().toLowerCase(Locale.ROOT);
                            if (!normalized.isEmpty()) {
                                ignoredModIds.add(normalized);
                            }
                        }
                        break;
                    case "--help":
                    case "-h":
                        help = true;
                        break;
                    default:
                        throw new IllegalArgumentException("unknown argument: " + argument);
                }
            }
            return new Options(resultRoots, outputRoot, ignoredModIds, help);
        }

        private static String requireValue(final String[] arguments, final int index, final String option) {
            if (index >= arguments.length || arguments[index].startsWith("--")) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return arguments[index];
        }
    }
}
