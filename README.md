# Client Tools

[![Development Builds](https://github.com/ohto-ai/client-tools/actions/workflows/build.yml/badge.svg)](https://github.com/ohto-ai/client-tools/actions/workflows/build.yml)
[![Publish Release](https://github.com/ohto-ai/client-tools/actions/workflows/release.yml/badge.svg?event=release)](https://github.com/ohto-ai/client-tools/actions/workflows/release.yml)

Support Minecraft 1.21 | [Release](https://github.com/ohto-ai/client-tools/releases)

**客户端自动化工具集：自动合成、定时调度、快捷聊天、飞行控制、蛇形扫掠 / A collection of client-side automation tools: auto crafting, timed scheduler, quick chat, flight toggle, and snake-pattern area traversal.**

---

## Introduction

`Client Tools` is a Fabric client-side mod for Minecraft 1.21. It provides five practical automation tools:

- **`/ccraft`** — Automated crafting chain execution with multi-source material scanning and optimal crafting plan generation
- **`/ctimer`** — Timed command scheduler supporting repetitive execution with configurable intervals and counts
- **`/cchat`** — Quick chat message sender
- **`/cfly`** — Flight toggle with jump and status display
- **`/csweep`** — Snake-pattern area traversal for automated clearing (works with projection printers and auto-mining mods)

All features run entirely on the client side. No server-side installation is required.

---

## 简介

`Client Tools` 是一个适用于 Minecraft 1.21 的 Fabric 客户端模组，提供五个实用工具：

- **`/ccraft`** — 自动合成链执行，支持多源材料扫描与最优合成计划生成
- **`/ctimer`** — 定时命令调度器，支持按配置的时间间隔和次数重复执行命令
- **`/cchat`** — 快捷聊天消息发送
- **`/cfly`** — 飞行开关，支持起跳和状态查看
- **`/csweep`** — 蛇形扫掠区域遍历，搭配投影打印机和自动挖掘 mod 实现区域清空

所有功能均完全在客户端运行，无需服务端安装。

---

## Features

### `/ccraft` — Automated Crafting

- Auto-detect crafting station and input/output containers from the block you are looking at
- Smart multi-source material scanning — detects available materials in the input chest and builds an optimal crafting plan
- Full crafting chain analysis — automatically handles intermediate crafting steps (e.g., planks → sticks → tools)
- Configurable target count with infinite mode (crafts until materials run out or output is full)
- **Per-setting clear** — individually clear source, product, station, input, output, or count without resetting everything
- **Enhanced block highlight (`/ccraft show`)** — customizable duration and per-position colors with tab-completion for named colors (red, green, blue...) and hex values
- Real-time status with contextual hints — shows stop command when running, run command when ready
- Container timeout and retry handling
- Real-time progress tracking during execution

### `/ctimer` — Timed Command Scheduler

- Execute any command repeatedly at configurable intervals
- Support for human-readable durations: `1s`, `5m`, `1h`, `500ms`, `20t` (ticks)
- Configurable repeat count or infinite loop
- List, stop all, or stop by pattern matching
- Multiple independent timers run concurrently

### `/cfly` — Flight Toggle

- Toggle flight on/off with a single command
- `jump` subcommand to enable flight and jump simultaneously
- `status` subcommand to check flight permission, active state, and gamemode
- Automatically syncs abilities to the server

### `/csweep` — Snake-Pattern Area Traversal

- Select a cuboid area with two corner positions (`pos1` / `pos2`)
- Snake-pattern path generation with configurable station spacing (based on radius)
- **Continuous smooth movement** — linear interpolation between stations at constant speed, no stop-and-go
- **Dynamic cuboid adjustment** — expand or contract the cuboid boundary based on your viewing direction
- **Does not lock camera** — uses position-only packets, you keep full view control
- Independent highlight toggles: green outline wireframe, cyan path preview (gray for visited segments)
- **Pause & resume with full persistence** — progress survives game restarts, pause acts as toggle
- Live speed adjustment — change flight speed mid-operation
- Designed to work alongside projection printers and auto-mining mods

### `/cchat` — Quick Chat Sender

- Send chat messages directly from the command line
- Useful for automation scripts and quick communication

---

## How to Use

### Prerequisites

1. Install [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api)
2. Place the built `client-tools-x.y.z.jar` into your `mods/` directory
3. Launch the game

### `/ccraft` Quick Start

```
--- Basic setup ---
/ccraft product minecraft:diamond_pickaxe   — Set the item you want to craft
/ccraft input                                — Set input chest (look at it)
/ccraft output                               — Set output chest (look at it)
/ccraft station                              — Set crafting table (look at it, or auto-detected)

--- Target count ---
/ccraft count 64                             — Craft 64 products
/ccraft count infinite                       — Craft until materials run out or output is full

--- Control ---
/ccraft run                                  — Start crafting!
/ccraft stop                                 — Stop the running craft
/ccraft status                               — Check runtime progress and configuration

--- Block highlight ---
/ccraft show                                 — Highlight positions for 3s (default colors)
/ccraft show 10s                             — Highlight for 10 seconds
/ccraft show 5s red blue gold                — 5s, custom colors (station/input/output)
/ccraft show 30s FF5555 55FF55 FFAA00        — 30s, hex colors
/ccraft show 1m green cyan magenta           — 1 minute with named colors

--- Clear settings ---
/ccraft source clear                         — Clear only the source item
/ccraft product clear                        — Clear only the product item
/ccraft station clear                        — Clear only station position
/ccraft input clear                          — Clear only input box position
/ccraft output clear                         — Clear only output box position
/ccraft count clear                          — Reset count to default (1)
/ccraft clear                                — Clear all settings at once
```

**Duration formats:** `3s` (seconds), `10t` (ticks), `1m` (minutes), `500ms` (milliseconds)

**Color formats:** named (`red`, `green`, `blue`, `gold`, `yellow`, `cyan`, `magenta`, `white`, `orange`, `purple`, `pink`, `lime`, `aqua`, `navy`, `teal`, `brown`, `gray`, `black`) or hex (`FF5555`, `#FF5555`, `0xFF5555`)

Use **Tab** to auto-complete durations and colors in the `show` command.

### `/ctimer` Quick Start

```
/ctimer 5s /say Hello         — Send "/say Hello" every 5 seconds (infinite)
/ctimer 30s /tp ~ ~10 ~ 10   — Teleport every 30 seconds, 10 times
/ctimer list                  — List all active timers
/ctimer stop                  — Stop all timers
/ctimer stop Hello            — Stop timers whose command matches "Hello"
```

### `/cfly` Quick Start

```
/cfly                    — Toggle flight on/off
/cfly jump               — Enable flight and jump
/cfly status             — Check flight state
```

### `/csweep` Quick Start

```
--- Setup ---
/csweep pos1              — Set first corner (look at block, or use player position)
/csweep pos1 100 64 200   — Set first corner manually
/csweep pos2              — Set opposite corner
/csweep radius 5          — Set station spacing radius (default 4, range 1–64)
/csweep speed 15          — Set flight speed in blocks/sec (default 10, range 0.5–100)

--- Adjust cuboid ---
/csweep expand 5          — Expand the cuboid 5 blocks along the face you're looking at
/csweep contract 3        — Shrink the cuboid 3 blocks along the face you're looking at

--- Highlight ---
/csweep show              — Check current show settings
/csweep show outline      — Toggle green cuboid wireframe
/csweep show path         — Toggle cyan snake-path preview (works before starting!)

--- Control ---
/csweep start             — Start sweep (or resume if paused state exists)
/csweep pause             — Pause at current station / resume if paused (toggle)
/csweep stop              — Stop and clear pause state
/csweep status            — View config, progress, and pause state
/csweep reset             — Clear all settings
```

**Persistence:** Pause state (current station index) is saved per-world to `config/client-tools/sweep/<world-id>.json`. You can disconnect, restart the game, and resume from where you left off with `/csweep start`.

**Speed adjustment:** Change speed anytime with `/csweep speed <value>` — takes effect immediately even while running.

**Cuboid adjustment:** Use `expand`/`contract` to adjust the cuboid boundary after setup. The mod determines which face to adjust based on your viewing direction — look at the face you want to move and run the command. Works for all six faces (±X, ±Y, ±Z).

**Smooth movement:** The sweep no longer stops at each station. The player moves continuously along the path at constant speed with linear interpolation between waypoints — faster and more natural.

### `/cchat` Quick Start

```
/cchat Hello everyone!         — Send "Hello everyone!" in chat
```

---

## Supported Languages

| Language | Status |
|----------|--------|
| English (en_us) | ✅ Complete |
| Simplified Chinese (zh_cn) | ✅ Complete |

---

## Build

```bash
./gradlew build
```

Output JAR: `build/libs/client-tools-*.jar`

---

## Dependencies

- Minecraft 1.21
- Fabric Loader >= 0.16.10
- Fabric API
- Java >= 21

---

## License

MIT License
