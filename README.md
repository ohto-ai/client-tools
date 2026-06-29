# Client Tools

[![Development Builds](https://github.com/ohto-ai/client-tools/actions/workflows/build.yml/badge.svg)](https://github.com/ohto-ai/client-tools/actions/workflows/build.yml)
[![Publish Release](https://github.com/ohto-ai/client-tools/actions/workflows/release.yml/badge.svg?event=release)](https://github.com/ohto-ai/client-tools/actions/workflows/release.yml)

Support Minecraft 1.21～1.21.8

**[Download on Modrinth](https://modrinth.com/mod/client-tools)** | **[Download on CurseForge](https://www.curseforge.com/minecraft/mc-mods/client-tools)**

**客户端自动化工具集 / A collection of client-side automation tools.**

![Screenshot 1](https://github.com/user-attachments/assets/135edae1-570c-40a3-9c61-c5179f33fb2f)
*Sweep path preview with highlight overlay*

![Screenshot 2](https://github.com/user-attachments/assets/185dd107-1aa6-43c7-88ce-6ce60d59d488)
*Arrow trajectory prediction with landing marker*

![Screenshot 3](https://github.com/user-attachments/assets/decb09da-84e7-4769-98fd-bcdb259610b7)
*Crafting station highlight with input/output containers*

![Screenshot 4](https://github.com/user-attachments/assets/74f70c4d-55c5-4713-8bdf-fa5ed3db4862)
*Furina doll 3D model applied to items via /cdoll*

---

## Download

| Platform | Link |
|----------|------|
| Modrinth | [modrinth.com/mod/client-tools](https://modrinth.com/mod/client-tools) |
| CurseForge | [curseforge.com/minecraft/mc-mods/client-tools](https://www.curseforge.com/minecraft/mc-mods/client-tools) |
| GitHub Releases | [github.com/ohto-ai/client-tools/releases](https://github.com/ohto-ai/client-tools/releases) |

---

## Introduction

**`Client Tools` is a Fabric client-side mod for Minecraft 1.21. It provides 13 client commands (grouped into 12 functional modules):**

- **`/ccraft`** — Automated crafting chain execution with multi-source material scanning and optimal crafting plan generation
- **`/ctimer`** — Timed command scheduler supporting repetitive execution with configurable intervals and counts
- **`/cchat`** — Quick chat message sender
- **`/cfly`** — Flight toggle with jump and status display
- **`/csweep`** — Snake-pattern area traversal for automated clearing (works with Litematica-printer)
- **`/csequence`** — mcfunction sequence editor and executor with external editor support, nesting, and looping
- **`/cbuy` / `/csell`** — Shop automation for buying/selling items through container-based shops
- **`/clitematica`** — Litematica selection tools: shift placement along axes, or make it follow player movement in real time
- **`/cbow`** — Real-time arrow trajectory prediction with parabola, landing markers, Multishot support, entity-hit detection, and auto-aim targeting
- **`/criptide`** — Override the water/rain requirement for Riptide tridents, enabling flight in deserts, caves, and under roofs
- **`/cdoll`** — Dynamically apply the Furina doll 3D model to any item, with built-in resource pack auto-enable
- **`/cencrypt`** — AES-256-GCM encrypted private chat via `/msg`, with delivery confirmation, group messaging, per-player keys, and command alias

All features run entirely on the client side. No server-side installation is required.

---

## 简介

`Client Tools` 是一个适用于 Minecraft 1.21 的 Fabric 客户端模组，提供 13 个客户端命令（功能上分为 12 个模块）：

- **`/ccraft`** — 自动合成链执行，支持多源材料扫描与最优合成计划生成
- **`/ctimer`** — 定时命令调度器，支持按配置的时间间隔和次数重复执行命令
- **`/cchat`** — 快捷聊天消息发送
- **`/cfly`** — 飞行开关，支持起跳和状态查看
- **`/csweep`** — 蛇形扫掠区域遍历，搭配投影打印机实现区域清空
- **`/csequence`** — mcfunction 序列编辑器与执行器，支持外部编辑器、嵌套调用和循环
- **`/cbuy` / `/csell`** — 商店自动化买卖，操作容器界面商店
- **`/clitematica`** — Litematica 选区工具：沿轴移动投影，或使选区实时跟随玩家移动
- **`/cbow`** — 实时箭矢轨迹预测，显示抛物线、落点标记，支持多重射击、实体命中检测和自动瞄准
- **`/criptide`** — 覆盖激流三叉戟的水/雨限制，在沙漠、洞穴、屋檐下等任意位置均可飞行
- **`/cdoll`** — 动态将 Furina 娃娃 3D 模型应用到任意物品，内置资源包自动启用
- **`/cencrypt`** — AES-256-GCM 加密私聊，通过 `/msg` 发送加密消息，支持送达确认、群发、玩家专属密钥和命令别名

所有功能均完全在客户端运行，无需服务端安装。

---

## Features

### `/ccraft` — Automated Crafting

- Auto-detect crafting station and input/output containers from the block you are looking at
- Smart multi-source material scanning — detects available materials in the input chest and builds an optimal crafting plan
- Full crafting chain analysis — automatically handles intermediate crafting steps (e.g., planks → sticks → tools)
- **Legacy source mode** — specify a source item with `/ccraft source <item>` for fixed chain crafting
- Configurable target count with infinite mode (crafts until materials run out or output is full)
- **Per-setting clear** — individually clear source, product, station, input, output, or count without resetting everything
- **Multi-station management** — save, load, list, and delete named station configurations; find the nearest saved station
- **Enhanced block highlight (`/ccraft show`)** — customizable duration and per-position colors with tab-completion for named colors (red, green, blue...) and hex values; default colors: station green, input blue, output gold
- Real-time status with contextual hints — shows stop command when running, run command when ready
- **`/ccraft help [subcommand]`** — detailed help for individual subcommands
- Container timeout and retry handling
- Real-time progress tracking during execution

### `/ctimer` — Timed Command Scheduler

- Execute any command repeatedly at configurable intervals
- Support for human-readable durations: `1s`, `5m`, `1h`, `500ms`, `20t` (ticks)
- Configurable repeat count or infinite loop
- List, stop by pattern matching, or stop all
- Multiple independent timers run concurrently
- **`/ctimer help [subcommand]`** — detailed help for `start`, `stop`, `stop-all`, `list`

### `/cfly` — Flight Toggle

- Toggle flight on/off with a single command
- **`enable` / `disable`** — explicitly set flight state
- **`jump`** — toggle auto-jump mode (automatically jumps when flight is enabled)
- **`jump enable` / `jump disable`** — explicitly set auto-jump
- **`status`** — check flight permission, active state, gamemode, and auto-jump setting
- **`/cfly help [subcommand]`** — detailed help for individual subcommands
- Automatically syncs abilities to the server
- Auto-jump setting persists in `config/client-tools/global.json` (along with `/cbow` and `/criptide` toggle states)

### `/csweep` — Snake-Pattern Area Traversal

- Select a cuboid area with two corner positions (`pos1` / `pos2`)
- **Litematica integration** — auto-sync sweep area from Litematica schematic sub-regions (enabled by default); sweep across multiple regions sequentially
- Snake-pattern path generation with configurable station spacing (based on radius)
- **Continuous smooth movement** — linear interpolation between stations at constant speed, no stop-and-go
- **Dual mode** — `mining` mode for block clearing, `drain` mode for liquid scanning
- **maxspeed** — set an upper speed limit for adaptive auto-speed mode
- **autospeed** — adaptive speed based on block density, varies between `speed` and `maxspeed`
- **avoidwater** — automatically adjust Y-level to stay above water during traversal
- **blockdetect** — backward hemisphere blockage detection with two response modes: `wait` (stop and wait) or `slow` (reduce speed to 20%)
- **Dynamic cuboid adjustment** — expand or contract the cuboid boundary based on your viewing direction
- **Does not lock camera** — uses position-only packets, you keep full view control
- Independent highlight toggles: green outline wireframe, cyan path preview (gray for visited segments)
- Y-level layer emphasis and nearest-station direction arrows are enabled by default
- **Pause & resume with full persistence** — progress survives game restarts, pause acts as toggle
- **Auto-save on disconnect** — sweep progress is automatically saved when you disconnect, with a reconnect reminder
- **Smooth approach** — when resuming far from the path, flies smoothly to the target instead of teleporting
- **Real-time nearest station tracking** — `/csweep nearest` toggles live tracking; nearest station updates as you move, with path direction arrows showing which way the sweep will go
- **Y-level layer emphasis** — `/csweep show layer` highlights the current Y-level path with multi-offset thick lines, dims other Y-levels (follows sweep Y when running, player Y when idle)
- **Multi-region layer emphasis** — thickens the current sub-region outline, dims inactive regions for clarity
- **Sub-region skip** — skip to the next Litematica sub-region with `/csweep next`
- Live speed adjustment — change flight speed mid-operation
- Progress bar with ETA in status display
- **Anti-penalty floor (`/csweep antiground`)** — client-side ghost block path below stations eliminates the floating mining penalty; auto flight toggle on descent for smooth layer transitions; configurable block type (default: redstone ore)
- **`/csweep penalty`** — debug command showing full movement penalty status (flight, ground, water, cobweb, stuck, blockage, speed)
- **`/csweep help [subcommand]`** — detailed help for all subcommands with tab-completion

### `/cchat` — Quick Chat Sender

- Send chat messages directly from the command line
- Useful for automation scripts and quick communication
- **`/cchat help`** — show help information

### `/csequence` — mcfunction Sequence Executor

- Create and manage `.mcfunction` files with click-to-copy file paths for external editing
- Execute commands sequentially with configurable delay between each line
- Supports **nested sequences** — a sequence can call another, which can call another (up to 10 levels deep)
- **Cycle detection** — prevents infinite recursion (A→B→A is blocked automatically)
- Loop mode for repeating sequences
- All sequence files are stored in `config/client-tools/sequences/`
- **`/csequence help [subcommand]`** — detailed help for individual subcommands

### `/cbuy` / `/csell` — Shop Automation

- Automate buying and selling items through container-based shop GUIs
- Supports Chinese item names and Minecraft item IDs
- Default count is `all` (maximum possible) or specify a number
- Blocked while crafting or another shop operation is running
- Tab-completion for item names (Chinese and English/Minecraft IDs)
- **`/cbuy help`** / **`/csell help`** — show help information

### `/clitematica` — Litematica Selection Tools

- **`shift x <amount>` / `y <amount>` / `z <amount>`** — move the Litematica selection along individual axes
- **`shift status`** — display current selection information including region count and coordinates
- **`follow start`** — begin real-time following: records the player→selection offset and moves the selection as the player moves
- **`follow stop`** — stop following; the selection stays at its current position
- **`follow status`** — show current follow state: offset, reference positions, axis locks
- **`follow axis <x|y|z> <lock|unlock>`** — lock or unlock individual axes to constrain selection movement (e.g., lock Y+Z to only move on X)
- **`/clitematica help [subcommand]`** — detailed help for `shift` and `follow`
- Requires Litematica to be installed

### `/cbow` — Arrow Trajectory Prediction & Auto-Aim

- Real-time parabolic trajectory display while charging a bow or holding a loaded crossbow
- Clean dotted trajectory line with configurable color
- **Multishot support** — shows 3 trajectories fanned at ±10° for both crossbows and custom server bows
- **Entity-hit detection** — trajectory and marker turn green when an arrow would hit a living entity
- **Auto-aim targeting (`/cbow target`)** — lock bow aim onto a specific entity or enable auto-acquire mode
  - `auto` mode: raycasts from the player's eyes along the look direction, locks onto the first entity hit
  - Manual mode: target a specific player by name or an entity via selector (`@e[distance=..50,limit=1]`)
  - Mod controls the player's yaw/pitch in real-time, adjusting for gravity and drag to ensure the arrow passes through the target
  - Lock persists until the bow is released; target config is kept across draw/release cycles
- **Face-aligned landing markers** — markers orient perpendicular to the hit surface (horizontal on ground, vertical on walls)
- 3D landing marker with crosshair, ring, and beacon line for high visibility
- Distance label at the landing point
- Simple toggle command with status display showing weapon info, charge %, and enchantments
- Toggle state persists in `config/client-tools/global.json` across game restarts

### `/criptide` — Riptide Trident Flight Override

- Overrides the `isInWaterOrRain()` check so Riptide tridents work in any biome or location
- Works in deserts, caves, under roofs — anywhere without water or rain access
- Uses the vanilla `TridentItem` code path for authentic Riptide velocity and spin animation
- Simple toggle command with status display showing held trident and Riptide enchantment level
- **`/criptide help [subcommand]`** — detailed help for `on`, `off`, `status`
- Toggle state persists in `config/client-tools/global.json` across game restarts

### Furina Doll Resource Pack & `/cdoll` — Dynamic Item Appearance Override

- **Built-in resource pack** — includes a 3D Furina doll model (template_doll) that replaces armor stand, totem of undying, and compass appearances; auto-enabled on mod install
- **Dynamic item override** — `/cdoll add <item>` applies the doll 3D model to any item, using the original item's texture as the card face
- Persisted globally to `config/client-tools/dolls.json`; model files auto-generated to `resourcepacks/client-tools-dolls/`
- Auto-generates resource pack with correct pack format for the current Minecraft version
- **`/cdoll remove <item>`** — unregister an item
- **`/cdoll list`** — show all doll-ified items
- **`/cdoll clear`** — remove all assignments
- **`/cdoll help [subcommand]`** — detailed help with tab-completion
- The doll model system uses a parent-child architecture: `template_doll` defines the 3D geometry, child models only need to specify `parent` and `layer0` texture

---

## ModMenu 与 Cloth Config 配置界面 / ModMenu & Cloth Config GUI

本模组集成 ModMenu，安装后可获得图形化配置界面，无需手动编辑 JSON。

### 使用方法

1. 安装 [ModMenu](https://modrinth.com/mod/modmenu) 和 [Cloth Config](https://modrinth.com/mod/cloth-config)（均为可选依赖）
2. 打开「模组」界面
3. 找到 **Client Tools**
4. 点击齿轮按钮 ⚙️，打开配置界面

### 配置分类

| 分类 | 设置项 |
|------|--------|
| **Flight** | 飞行开关、自动起跳 |
| **Craft** | 合成产品（product）、合成原料（source）、工位/输入/输出容器位置、重复合成数量 |
| **Sweep** | 扫掠半径、移动速度、自适应速度（autospeed）、速度上限（maxspeed）、避水（avoidwater）、方块检测（blockdetect）、Litematica 同步 |
| **Display** | 描边线框（outline）、路径预览（path）、层级强调（layer）、方向箭头（dir） |
| **Timer** | 当前活跃的定时器列表 |
| **Status** | 当前扫掠进度、合成运行状态 |

所有修改实时生效，关闭界面时自动保存。

### Usage

1. Install [ModMenu](https://modrinth.com/mod/modmenu) and [Cloth Config](https://modrinth.com/mod/cloth-config) (both optional)
2. Open the Mods screen
3. Find **Client Tools**
4. Click the gear icon ⚙️ to open the config GUI

Categories: **Flight**, **Craft**, **Sweep**, **Display**, **Timer**, **Status**. All changes take effect immediately.

---

## 配置文件参考 / Configuration Files

所有配置文件均位于 `config/client-tools/` 目录下。无需手动编辑 —— 绝大多数设置可通过命令或 ModMenu 图形界面修改。

| 文件 | 关联命令 | 用途 |
|------|----------|------|
| `global.json` | `/cfly`, `/cbow`, `/criptide`, `/cencrypt` | 全局持久化设置 |
| `sequences/` | `/csequence` | mcfunction 序列文件（`.mcfunction`） |
| `dolls.json` | `/cdoll` | 物品-娃娃模型映射列表 |
| `sweep/<world-id>.json` | `/csweep` | 扫掠暂停进度（按世界分别保存） |

### global.json

跨会话持久化的全局设置：

```json
{
  "autoJump": false,
  "cbowEnabled": false,
  "criptideEnabled": false,
  "p2pPlayerPasswords": {},
  "p2pAlias": "em"
}
```

| 字段 | 对应命令 | 说明 |
|------|----------|------|
| `autoJump` | `/cfly jump` | 飞行时自动起跳 |
| `cbowEnabled` | `/cbow` | 箭矢轨迹预测开关状态 |
| `criptideEnabled` | `/criptide` | 激流三叉戟覆盖开关状态 |
| `p2pPlayerPasswords` | `/cencrypt set` | 玩家专属密钥映射（玩家名 → 密码） |
| `p2pAlias` | `/cencrypt alias` | `/cencrypt msg` 的快捷别名（默认 `em`） |

每次通过命令修改这些设置时，`global.json` 会自动更新。重启游戏后设置保持不变。

---

## How to Use

### Prerequisites

1. Install [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api)
2. Place the built `client-tools-x.y.z.jar` into your `mods/` directory
3. Launch the game

### `/ccraft` Quick Start

```
--- Help ---
/ccraft                                      — Show brief help
/ccraft help                                 — Show full help
/ccraft help <subcommand>                    — Show detailed help for a subcommand

--- Basic setup ---
/ccraft product minecraft:diamond_pickaxe    — Set the item you want to craft
/ccraft input                                — Set input chest (look at it)
/ccraft output                               — Set output chest (look at it)
/ccraft station                              — Set crafting table (look at it, or auto-detected)

--- Legacy source mode (fixed chain) ---
/ccraft source minecraft:diamond             — Set the source material to craft from

--- Target count ---
/ccraft count 64                             — Craft 64 products
/ccraft count infinite                       — Craft until materials run out or output is full

--- Control ---
/ccraft run                                  — Start crafting!
/ccraft stop                                 — Stop the running craft
/ccraft status                               — Check runtime progress and configuration

--- Block highlight ---
/ccraft show                                 — Highlight positions for 3s (default colors: station green, input blue, output gold)
/ccraft show 10s                             — Highlight for 10 seconds
/ccraft show 5s red blue gold                — 5s, custom colors (station/input/output)
/ccraft show 30s FF5555 55FF55 FFAA00        — 30s, hex colors
/ccraft show 1m green cyan magenta           — 1 minute with named colors

--- Multi-station management ---
/ccraft save myfarm                          — Save current station config as "myfarm"
/ccraft load myfarm                          — Load saved station config "myfarm"
/ccraft list                                 — List all saved station configs
/ccraft delete myfarm                        — Delete saved config "myfarm"
/ccraft nearest                              — Find and load the nearest saved station

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

**Color formats:** named (`red`, `green`, `blue`, `gold`, `yellow`, `cyan`, `magenta`, `white`, `orange`, `purple`, `pink`, `lime`, `aqua`, `navy`, `teal`, `brown`, `gray`, `grey`, `black`) or hex (`FF5555`, `#FF5555`, `0xFF5555`)

Use **Tab** to auto-complete durations and colors in the `show` command.

### `/ctimer` Quick Start

```
--- Help ---
/ctimer                                      — Show brief help
/ctimer help                                 — Show full help
/ctimer help <subcommand>                    — Show detailed help for a subcommand

--- Start timers ---
/ctimer start infinite interval 5s /say Hello   — Send "/say Hello" every 5 seconds forever
/ctimer start 10 interval 30s /tp ~ ~10 ~       — Teleport every 30 seconds, 10 times
/ctimer start 5 interval 1m /weather clear       — Run "/weather clear" every minute, 5 times

--- Manage timers ---
/ctimer list                                     — List all active timers
/ctimer stop Hello                               — Stop timers whose command matches "Hello"
/ctimer stop-all                                 — Stop all timers
```

**Syntax:** `/ctimer start <count|infinite> interval <duration> <command>`
**Duration formats:** `1s`, `5m`, `1h`, `500ms`, `20t` (ticks), plain number = ticks

### `/cfly` Quick Start

```
--- Help ---
/cfly                    — Toggle flight on/off
/cfly help               — Show full help
/cfly help <subcommand>  — Show detailed help for a subcommand

--- Flight control ---
/cfly enable             — Enable flight
/cfly disable            — Disable flight

--- Auto-jump ---
/cfly jump               — Toggle auto-jump mode
/cfly jump enable        — Enable auto-jump (jumps when flight is enabled)
/cfly jump disable       — Disable auto-jump

--- Status ---
/cfly status             — Check flight state, gamemode, and auto-jump setting
```

### `/csweep` Quick Start

```
--- Help ---
/csweep                                      — Show brief help
/csweep help                                 — Show full help
/csweep help <subcommand>                    — Show detailed help for a subcommand

--- Setup ---
/csweep pos1              — Set first corner (look at block, or use player position)
/csweep pos1 100 64 200   — Set first corner manually
/csweep pos2              — Set opposite corner
/csweep radius 5          — Set station spacing radius (default 4, range 1–64)
/csweep speed 15          — Set flight speed in blocks/sec (default 10, range 0.5–100)

--- Adaptive speed ---
/csweep maxspeed          — Show current maximum speed for autospeed mode
/csweep maxspeed 30       — Set max speed (default 20, range 0.5–100)
/csweep autospeed         — Toggle adaptive speed based on block density
/csweep autospeed on/off  — Explicitly enable/disable autospeed

--- Check current values ---
/csweep radius            — Show current radius
/csweep speed             — Show current speed

--- Adjust cuboid ---
/csweep expand 5          — Expand the cuboid 5 blocks along the face you're looking at
/csweep contract 3        — Shrink the cuboid 3 blocks along the face you're looking at

--- Highlight ---
/csweep show              — Check current show settings
/csweep show outline      — Toggle green cuboid wireframe
/csweep show path         — Toggle cyan snake-path preview (works before starting!)
/csweep show layer        — Toggle Y-level emphasis (thick current layer, dim others; on by default)
/csweep show dir          — Toggle path direction arrows at nearest station (on by default)

--- Mode ---
/csweep mode              — Show current mode (MINING or DRAIN)
/csweep mode mining       — Set mining mode (block clearing)
/csweep mode drain        — Set drain mode (liquid scanning)

--- Water & Blockage ---
/csweep avoidwater        — Toggle automatic Y-level adjustment to stay out of water
/csweep avoidwater on/off — Explicitly enable/disable
/csweep blockdetect       — Toggle backward hemisphere blockage detection
/csweep blockdetect on/off— Explicitly enable/disable detection
/csweep blockdetect wait  — Stop and wait when blockage detected
/csweep blockdetect slow  — Reduce speed to 20% when blockage detected

--- Litematica Integration ---
/csweep litematica on     — Sync sweep area with Litematica schematic sub-regions (enabled by default)
/csweep litematica off    — Use manual positions instead
/csweep litematica sync   — Force re-sync with Litematica placement (refreshes regions)
/csweep litematica        — Show Litematica sync status and sub-region list

--- Navigation ---
/csweep nearest           — Toggle real-time nearest station tracking (updates as you move)
/csweep next              — Skip current sub-region and jump to the next one

--- Control ---
/csweep start             — Start sweep (or resume if paused state exists)
/csweep pause             — Pause at current station / resume if paused (toggle)
/csweep stop              — Stop and clear pause state
/csweep status            — View config, progress, and pause state
/csweep reset             — Clear all settings

--- Debug ---
/csweep penalty           — Show movement penalty status (flight, water, cobweb, stuck, blockage, speed)

--- Anti-penalty floor ---
/csweep antiground             — Show anti-penalty floor settings
/csweep antiground on/off      — Enable/disable ghost block floor
/csweep antiground block <id>  — Set floor block type (default: minecraft:redstone_ore)
```

**Persistence:** Pause state (current station index) is saved per-world to `config/client-tools/sweep/<world-id>.json`. You can disconnect, restart the game, and resume from where you left off with `/csweep start`. If you disconnect mid-sweep, progress is automatically saved and you'll be reminded on rejoin.

**Speed adjustment:** Change speed anytime with `/csweep speed <value>` — takes effect immediately even while running. With `autospeed` enabled, actual speed varies between `speed` and `maxspeed` based on block density.

**Cuboid adjustment:** Use `expand`/`contract` to adjust the cuboid boundary after setup. The mod determines which face to adjust based on your viewing direction — look at the face you want to move and run the command. Works for all six faces (±X, ±Y, ±Z).

**Smooth movement:** The sweep no longer stops at each station. The player moves continuously along the path at constant speed with linear interpolation between waypoints — faster and more natural.

**Litematica integration:** Litematica sync is enabled by default. If Litematica is installed and a schematic is loaded, the sweep area automatically syncs to the schematic's sub-regions. Each sub-region is swept sequentially. Setting manual `pos1`/`pos2` auto-disables the sync. Use `/csweep litematica` to view detected regions and their coordinates.

**Anti-penalty floor:** `/csweep antiground on` enables a ghost block strip below the sweep path. The player stands on these blocks during horizontal movement, eliminating the floating mining penalty. Flight automatically toggles ON during vertical descents to avoid TP loops, then OFF on the next layer. Uses client-side ghost blocks (no items consumed). Configure block type with `/csweep antiground block <id>` (default: `minecraft:redstone_ore`). Remember to blacklist this block in your printer!

**Mid-sweep recovery & real-time tracking:** `/csweep nearest` toggles real-time nearest station tracking. When enabled, the nearest station updates as you move, with path direction arrows showing the sweep direction at that station. Move around to find the best starting point, then `/csweep start` to begin from there. The executor flies you smoothly to the target station — no instant teleport. `/csweep nearest` again to turn off tracking. If a paused state exists, using `/csweep nearest` clears it (you're choosing a new start point).

### `/cchat` Quick Start

```
/cchat Hello everyone!         — Send "Hello everyone!" in chat
/cchat help                    — Show help information
```

### `/csequence` Quick Start

```
--- Help ---
/csequence                                      — Show brief help
/csequence help                                 — Show full help
/csequence help <subcommand>                    — Show detailed help for a subcommand

--- Editing ---
/csequence new daily           — Create an empty daily.mcfunction and show its file path
/csequence edit daily          — Show daily.mcfunction file path (click to copy; creates if missing)
/csequence folder              — Show sequences folder path (click to copy)
/csequence list                — List all saved sequences
/csequence delete daily        — Delete daily.mcfunction

--- Execution ---
/csequence run daily           — Run daily.mcfunction (1-tick delay, no loop)
/csequence run daily 1s        — Run with 1-second delay between commands
/csequence run daily 500ms loop— Run with 500ms delay, repeat forever
/csequence run farm 5t loop    — Run with 5-tick delay, loop

--- Control ---
/csequence stop                — Stop the running sequence
/csequence status              — Show current progress, nesting depth, and call stack
```

**mcfunction format:** Standard Minecraft function syntax — one command per line, `#` for comments, blank lines ignored.

**Nested sequences:** Use `/csequence run <name>` inside a sequence file to call another sequence. The parent pauses, the child runs to completion, then the parent resumes. Cycle detection prevents infinite recursion (max 10 levels deep).

**File paths:** File paths are shown as clickable text in chat — click to copy the path, then paste into your file manager or editor to open the file directly. This approach ensures CurseForge compliance by not launching external programs.

### `/cbuy` / `/csell` Quick Start

```
--- Help ---
/cbuy help                    — Show help information
/csell help                   — Show help information

--- Usage ---
/cbuy minecraft:sand          — Buy sand (max/all)
/cbuy sand 64                 — Buy 64 sand (English name)
/cbuy 沙子 64                 — Buy 64 sand (Chinese name)
/csell minecraft:diamond 10   — Sell 10 diamonds
/csell 钻石 all               — Sell all diamonds (Chinese name)
```

**Item names:** Supports Minecraft IDs (`minecraft:sand`), English names (`sand`), and Chinese names (`沙子`). Tab-completion available for both English and Chinese names.

**Count:** Default is `all` (maximum possible). Can be a specific number. Count is parsed from the last space-separated token if it's `all` or a number.

**Requirements:** A shop GUI must be open. Blocked while crafting or another shop operation is running.

### `/clitematica` Quick Start

```
--- Help ---
/clitematica help              — Show full help
/clitematica help shift        — Show shift subcommand details
/clitematica help follow       — Show follow subcommand details

--- Shift (move selection along axes) ---
/clitematica shift x 5         — Move selection 5 blocks east (+X)
/clitematica shift x -3        — Move selection 3 blocks west (-X)
/clitematica shift y 2         — Move selection 2 blocks up (+Y)
/clitematica shift y -1        — Move selection 1 block down (-Y)
/clitematica shift z 4         — Move selection 4 blocks south (+Z)
/clitematica shift z -2        — Move selection 2 blocks north (-Z)
/clitematica shift status      — Show current selection info (region count, names, coordinates)

--- Follow (selection follows player in real time) ---
/clitematica follow start      — Begin following; records player→selection offset
/clitematica follow stop       — Stop following; selection stays in place
/clitematica follow status     — Show offset, reference positions, axis locks
/clitematica follow axis y lock   — Lock Y axis (selection only moves on X and Z)
/clitematica follow axis z lock   — Lock Z axis too (selection only moves on X)
/clitematica follow axis y unlock — Unlock Y axis (resume vertical following)
```

**Requirements:** Litematica must be installed and a schematic must be loaded.

### `/cbow` Quick Start

```
--- Help ---
/cbow                         — Toggle arrow trajectory prediction on/off
/cbow help                    — Show help information
/cbow help <subcommand>       — Show detailed help for on/off/status/target

--- Control ---
/cbow on                      — Enable trajectory prediction
/cbow off                     — Disable trajectory prediction
/cbow status                  — Show current state, weapon type, charge %, enchantments

--- Auto-Aim Targeting ---
/cbow target auto             — Arm auto-targeting: draw bow, aim will lock when crosshair passes an entity
/cbow target <player>         — Set a specific player as target (by name)
/cbow target @e[distance=..50,limit=1]  — Set target via entity selector
/cbow target @p               — Target nearest player
/cbow target                  — Show current target status
/cbow target stop             — Cancel targeting
```

**Usage:** Hold a bow and draw it (or hold a loaded crossbow). A dotted golden parabola shows the predicted arrow path in real-time. The landing point is marked with a prominent crosshair marker. If the arrow would hit an entity, the entire trajectory turns green and a diamond marker appears at the impact point. Multishot bows/crossbows show 3 trajectories. The toggle state persists across game restarts in `config/client-tools/global.json`.

**Auto-aim:** Set a target with `/cbow target auto` (auto-acquire on draw) or `/cbow target <name/selector>`. When you draw the bow, the mod controls your aim — adjusting yaw/pitch in real-time with full gravity and drag compensation. The lock releases when you fire; the target config is kept for your next shot. Use `/cbow target stop` to clear.

### `/criptide` Quick Start

```
--- Help ---
/criptide                        — Toggle Riptide override on/off
/criptide help                   — Show help information
/criptide help <subcommand>      — Show detailed help for on/off/status

--- Control ---
/criptide on                     — Enable Riptide override
/criptide off                    — Disable Riptide override
/criptide status                 — Show current state, held trident, Riptide level
```

**Usage:** Hold a trident with the Riptide enchantment, enable the override with `/criptide on`, then right-click and release to launch. Works anywhere regardless of biome, weather, or sky access. The vanilla game code handles the spin attack, velocity, and sound effects. The toggle state persists across game restarts in `config/client-tools/global.json`.

### `/cdoll` Quick Start

```
--- Help ---
/cdoll help                  — Show full help
/cdoll help <subcommand>     — Show detailed help for a subcommand

--- Manage doll items ---
/cdoll add minecraft:diamond — Apply doll model to diamond
/cdoll add stick             — Apply doll model to stick (bare name = minecraft:stick)
/cdoll remove stick          — Remove doll appearance from stick
/cdoll list                  — Show all doll-ified items
/cdoll clear                 — Remove all doll assignments
```

**Tab-completion:** `add` suggests all registered item IDs. `remove` suggests only items currently in the doll list.

**How it works:** Each doll-ified item gets a model JSON file generated under `resourcepacks/client-tools-dolls/`. The file inherits from `minecraft:item/template_doll` (provided by the built-in furina_doll pack) and sets `layer0` to the original item's texture. Resource packs are automatically reloaded after each change.

### `/cencrypt` — Encrypted Private Chat

```
--- Help ---
/cencrypt help                                — Show full help
/cencrypt help <subcommand>                   — Show detailed help for a subcommand

--- Key setup ---
/cencrypt set <player> <password>             — Set a player-specific encryption key
/cencrypt clear <player>                      — Clear player-specific key (revert to player name)
/cencrypt stop                                — Clear all player keys
/cencrypt status                              — Show key count and group info

--- Messaging ---
/cencrypt msg <player> <message>              — Encrypted message to one player (tab-completes online players)
/cencrypt group add <player>                  — Add player to group
/cencrypt group remove <player>              — Remove player from group
/cencrypt group list                          — Show group members
/cencrypt group send <message>               — Encrypted message to all online group members
/cencrypt group clear                         — Remove all group members

--- Alias ---
/cencrypt alias em                            — Set /em as shortcut for /cencrypt msg (default)
/cencrypt alias                               — Disable the alias shortcut
```

**Protocol:** Messages are AES-256-GCM encrypted and sent through vanilla `/msg`. The mod intercepts incoming whispers, detects the `[CTP2P]` marker, decrypts, and displays plaintext. Ciphertext never appears in chat.

**Encryption key priority:** Player-specific key (if set via `/cencrypt set`) → player name (always available as fallback). No global key needed — you can start encrypted chat immediately with zero configuration.

**Player-specific keys:** Use `/cencrypt set <player> <password>` to set a key for a specific player. Messages to/from that player will use their key instead of the player name fallback. Use `/cencrypt clear <player>` to remove the player-specific key. This allows different keys for different friends on the same server.

**Delivery confirmation:** Each message carries a unique ID. The recipient auto-replies with an ACK. The sender sees `✓ Delivered` on success or `✗ Could not deliver` after a 30-second timeout (offline / wrong key / truncated).

**Command alias:** Use `/cencrypt alias em` to set `/em` as a shortcut for `/cencrypt msg`. Then `/em Bob hello` works like `/cencrypt msg Bob hello`. Disable with `/cencrypt alias`. Default alias is `em`.

**Key persistence:** Player-specific keys are saved to `config/client-tools/global.json` and auto-restored on restart. Use `/cencrypt stop` to clear all keys.

**Undecryptable messages:** If an incoming `[CTP2P]` message can't be decrypted (wrong key, corruption), a visible warning is shown.

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

### Required

| Dependency | Version |
|------------|---------|
| Minecraft | 1.21～1.21.8 |
| Fabric Loader | >= 0.16.10 |
| Fabric API | * |
| Java | >= 21 |

### Optional (Recommended)

| Dependency | Purpose |
|------------|---------|
| [ModMenu](https://modrinth.com/mod/modmenu) | Adds a config button in the Mods screen |
| [Cloth Config](https://modrinth.com/mod/cloth-config) | Graphical settings GUI (Flight, Craft, Sweep, Display, Timer, Status) |
| [Litematica](https://modrinth.com/mod/litematica) | Enables `/clitematica` and Litematica integration for `/csweep` |

---

## License

MIT License
