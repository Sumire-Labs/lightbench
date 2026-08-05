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

## [0.1.0]

### Added

- first release.
