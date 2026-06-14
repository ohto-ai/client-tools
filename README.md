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

- **`/ccraft`** — 自动合成链执行，支持多源材料扫描与最优合成计划生成
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
/ccraft product minecraft:diamond_pickaxe   — Set the item you want to craft
/ccraft station ~ ~ ~                        — Set crafting table position (or look at it)
/ccraft input ~1 ~ ~                         — Set input container position
/ccraft output ~-1 ~ ~                       — Set output container position
/ccraft count infinite                       — Set target count (or use a number)
/ccraft run                                  — Start crafting!
/ccraft status                               — Check current configuration
/ccraft stop                                 — Stop crafting
```

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
