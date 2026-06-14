# Changelog

All notable changes to Client Tools will be documented in this file.

## [1.0.0] — Initial Release

### Added
- **`/ccraft`** — Automated crafting chain execution
  - Auto-detect crafting station and input/output containers from looked-at block
  - Smart multi-source material scanning with optimal crafting plan generation
  - Full crafting chain analysis with intermediate step handling
  - Configurable target count with infinite mode
  - Container timeout and retry handling
  - Real-time progress tracking
- **`/ctimer`** — Timed command scheduler
  - Repetitive command execution with configurable intervals (`s`, `m`, `h`, `ms`, `t`)
  - Configurable repeat count or infinite loop
  - List, stop all, and pattern-based stop
- **`/cchat`** — Quick chat message sender
- Full i18n support: English (en_us) and Simplified Chinese (zh_cn)
- Licensed under MIT

### Dependencies
- Minecraft 1.21
- Fabric Loader >= 0.16.10
- Fabric API
- Java >= 21
