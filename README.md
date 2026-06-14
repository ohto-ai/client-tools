# Client Tools

[![Development Builds](https://github.com/ohto-ai/client-tools/actions/workflows/build.yml/badge.svg)](https://github.com/ohto-ai/client-tools/actions/workflows/build.yml)
[![Publish Release](https://github.com/ohto-ai/client-tools/actions/workflows/release.yml/badge.svg?event=release)](https://github.com/ohto-ai/client-tools/actions/workflows/release.yml)

Support Minecraft 1.21 | [Release](https://github.com/ohto-ai/client-tools/releases)

**客户端自动化工具集：自动合成链执行、定时命令调度、快捷聊天发送 / A collection of client-side automation tools: automated crafting chain execution, timed command scheduler, and quick chat sender.**

---

## Introduction

`Client Tools` is a Fabric client-side mod for Minecraft 1.21. It provides three practical automation tools to improve your gameplay efficiency:

- **`/ccraft`** — Automated crafting chain execution with multi-source material scanning and optimal crafting plan generation
- **`/ctimer`** — Timed command scheduler supporting repetitive execution with configurable intervals and counts
- **`/cchat`** — Quick chat message sender

All features run entirely on the client side. No server-side installation is required.

---

## 简介

`Client Tools` 是一个适用于 Minecraft 1.21 的 Fabric 客户端模组，提供三个实用的自动化工具：

- **`/ccraft`** — 自动合成链执行，支持多源材料扫描与最优合成计划生成，单项设置清除，自定义高亮时长与颜色
- **`/ctimer`** — 定时命令调度器，支持按配置的时间间隔和次数重复执行命令
- **`/cchat`** — 快捷聊天消息发送

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
