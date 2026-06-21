# Changelog

All notable changes to Client Tools will be documented in this file.

## [1.3.0-beta] — Anti-Penalty Floor & Code Quality

### Added
- **`/csweep antiground`** — Anti-penalty floor path using client-side ghost blocks
  - Pre-fills ghost blocks (same technique as clientcommands' cghostblock) below the entire sweep path
  - Eliminates floating mining penalty during horizontal movement
  - Auto flight toggle: ON during vertical descents (smooth `setPos`), OFF on horizontal layers (standing on ghost blocks)
  - Configurable block type with `/csweep antiground block <id>` (default: `minecraft:redstone_ore`)
  - Distance-based cleanup — only removes ghost blocks safely behind the player
  - Descent pre-cleaning to prevent TP loops between Y layers
  - Blacklist reminder to add floor blocks to printer blacklist
- **`/cbuy` / `/csell`** — Shop automation for container-based shop GUIs
  - Support for Chinese item names and Minecraft item IDs
  - Configurable buy/sell count with "all" support
- **`/cplacement`** — Move Litematica schematic placement along X/Y/Z axes
- **`ClientToolsConfig`** — Global cross-session settings (autoJump now persists across restarts)
- **`ContainerUtils`** — Shared container utility class (eliminates code duplication)

### Fixed
- `avoidWater` no longer incorrectly gated behind `autoSpeed` — now works independently
- `parseDuration` unified — `CcraftCommand` delegates to `CtimerCommand` (fixes hour/decimal mismatches)
- Reflection failure for `stateId` now logs a warning instead of failing silently
- `autoJump` now persists in `config/client-tools/global.json`
- Backward blockage detection now excludes anti-penalty floor ghost blocks

### Changed
- **Code quality overhaul** — eliminated ~95% duplication between `CbuyCommand`/`CsellCommand`
- Extracted shared `ShopCommandHelper`, `ContainerUtils`
- Standardized on `List.of()` throughout the codebase
- Per-world config persistence for sweep, craft, and global settings

## [1.2.9-beta] — Shop & Sweep Drain Mode

### Added
- **`/cbuy` / `/csell`** — buy and sell items through container-based shops
- **`csweep drain mode`** — auto-detect low sand inventory and restock from shop
- **`/cplacement`** — move Litematica schematic placements along individual axes
- Help subcommands for all commands (`/csweep help pos1`, `/ccraft help station`, etc.)

## [1.2.8-beta] — Sweep Stability Fixes

### Fixed
- Prevent rubberbanding by tracking sent position instead of position delta
- Eliminate floating mining penalty in sweep movement
- Improved stuck recovery during sweep

## [1.0.0] — Initial Release

### Added
- **`/ccraft`** — Automated crafting chain execution
- **`/ctimer`** — Timed command scheduler
- **`/cchat`** — Quick chat message sender
- **`/cfly`** — Flight toggle
- **`/csweep`** — Snake-pattern area traversal
- **`/csequence`** — mcfunction sequence executor
- Full i18n: English (en_us) and Simplified Chinese (zh_cn)
- Licensed under MIT
