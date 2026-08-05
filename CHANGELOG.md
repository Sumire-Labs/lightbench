# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The section matching `mod_version` is what the `publish_with_changelog` option feeds into the
CurseForge / Modrinth changelog — only when publishing is enabled (no shipped workflow does this by
default). The GitHub release notes are auto-generated from commits/PRs by the release workflow
(`generateReleaseNotes`) and are not sourced from this file.

## [1.0.0]

### Changed

- Reworked the default generation benchmark to warm up on a 101x101 region, then measure 36 separate
  17x17 regions in batches of at most five chunks with a full lighting-completion barrier after every
  batch.
- Moved the previous contiguous-square benchmark behind the explicit `bulk` subcommand and clarified
  that it measures queue throughput rather than per-batch convergence latency.
- Replaced misleading per-`provideChunk` latency percentiles with batch and region wall-time
  distributions, and corrected percentile indexing to use the nearest-rank definition.
- Enabled JVM thread CPU accounting before reporting Pulsar worker time instead of silently reporting
  zero when the supported counter started disabled.

### Added

- Added regression tests for the warmup size, measured-region count, chunk uniqueness, batch count and
  percentile selection.
- Added a non-generating fresh-world preflight for `gen` and `bulk`. The command now checks every
  target plus a one-chunk border and aborts before requesting chunks if any part of that footprint
  already exists.
- Added versioned JSON result files for completed `gen` and `bulk` runs, including lossless run
  metadata, every raw batch and region observation, environment details, mod JAR hashes and a combined
  config-file fingerprint.
- Raw observations are captured in preallocated primitive arrays, while JSON construction, hashing and
  disk output happen after measured phases so reporting work does not inflate benchmark timings.
- Added an offline comparison task that validates schema-1 files and their raw batch/region
  consistency, rejects different benchmark conditions, and writes per-run CSV plus engine-grouped
  Markdown summaries only for comparable results.
- Added checks that repeated measurements use the same engine JAR and that raw chunk traversal is
  identical across runs, while allowing the selected light-engine mod to differ between groups.

### Fixed

- Preserve explicit JSON `null` values for unavailable worker CPU measurements and disabled bulk
  warmup instead of silently omitting those schema fields.
- Run the offline comparison task with the configured Azul Java toolchain instead of inheriting the
  Gradle daemon runtime.
- Resolve packaged mod artifacts from Forge's top-level and version-specific `mods` directories so
  coremods and Cleanroom-provided mods receive reproducible file hashes; strict comparison now rejects
  missing artifact metadata for every non-platform mod.
- Accept platform-separated lists of absolute benchmark-result paths on Windows without treating drive
  letters as invalid separators.

## [0.1.0]

### Added

- first release.
