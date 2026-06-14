# 实现计划：`/ctimer` 客户端定时命令

## 背景
为一个 Minecraft 1.21 Fabric 客户端 mod 添加 `/ctimer` 命令，用于定时重复执行指定命令。支持有限次数和无限循环两种模式。目标场景是定时执行纯客户端命令（如 clientcommand mod 的 `cglow` 命令）或者普通命令（home,服务器的tpa等等）。

## 命令语法
```
/ctimer start <次数|infinite> interval <时长> <要执行的命令>   # 启动定时器, 
/ctimer stop-all                                                # 停止所有定时器
/ctimer list                                                # 列出活跃的定时器, 类似/ctask list的返回描述， 1.cglow 这种形式，编号+命令
/ctimer stop <pattern>                                               # 停止匹配的定时器, 编号或者命令，也是同/ctask stop
```

示例：
- `/ctimer start infinite interval 1s cglow entities @e`
- `/ctimer start 2 interval 1s cglow entities @e`
- `/ctimer start 10 interval 30s say Hello`

时长格式支持：`1s`（秒）、`5m`（分钟）、`1h`（小时）、`500ms`（毫秒）、`20t`（原始 tick），或纯数字（表示 tick 数）。

## 需要创建的文件

### 1. `src/client/java/indi/ohtoai/tool/client_timer/client/timer/TimerInstance.java`
定时器实例数据类：
- 字段：`id`（int，自增唯一标识）、`totalTimes`（int，-1 表示无限）、`remainingTimes`（int，剩余次数）、`intervalTicks`（int，间隔 tick 数，最小为 1）、`ticksElapsed`（int，距离上次执行的 tick 计数）、`command`（String，要执行的命令）
- `tick(MinecraftClient client)` → boolean：每 tick 调用，到达间隔时执行命令，返回 true 表示定时器已完成（应被移除）
- `execute(MinecraftClient client)`：通过 `client.player.networkHandler.sendChatCommand(command)` 发送命令；如果玩家不在世界中（player 或 networkHandler 为 null）则静默跳过

### 2. `src/client/java/indi/ohtoai/tool/client_timer/client/timer/TimerManager.java`
静态管理器：
- 使用 `CopyOnWriteArrayList<TimerInstance>` 存储活跃定时器，保证并发安全
- `addTimer(int times, int intervalTicks, String command)` → 创建 TimerInstance 并加入列表
- `tick(MinecraftClient client)` → 遍历所有定时器，调用 `tick()`，通过 `removeIf` 移除已完成的
- `stopAll()` → 清空列表
- `getTimers()` → 返回不可修改的快照，供 `/ctimer list` 使用
- `getActiveCount()` → 返回当前活跃定时器数量

### 3. `src/client/java/indi/ohtoai/tool/client_timer/client/command/CtimerCommand.java`
命令注册类，通过 Brigadier 构建命令树：
- `register(CommandDispatcher<FabricClientCommandSource> dispatcher)` 方法，在客户端入口点中通过 `ClientCommandRegistrationCallback.EVENT` 调用

Brigadier 命令树结构：
```
/ctimer (literal)
  ├── stop (literal)     → 停止所有定时器，发送反馈
  ├── cancel (literal)   → 同 stop
  ├── list (literal)     → 列出活跃的定时器（id、次数、间隔、命令）
  └── start (literal)
      └── <count: word>      → 次数参数，接受 "infinite" 或正整数
          └── interval (literal)
              └── <duration: word>     → 时长参数，如 "1s"、"5m"
                  └── <command: greedyString>  → 要执行的命令（贪婪匹配）
                      → 解析参数，启动定时器
```

参数建议（Tab 补全）：
- count 参数：建议 `["1", "5", "10", "30", "infinite"]`
- duration 参数：建议 `["1s", "5s", "30s", "1m", "5m", "30m", "1h", "5h"]`

工具方法：
- `parseDuration(String)` → int：去掉后缀（`ms`/`s`/`m`/`h`/`t`），转换为 tick 数；解析失败返回 -1
- `formatDuration(int ticks)` → String：将 tick 数转为人类可读格式（如 "1h 30m"、"45s"），供 `/ctimer list` 显示

## 需要修改的文件

### 4. `src/client/java/indi/ohtoai/tool/client_timer/client/ClientTimerClient.java`
在 `onInitializeClient()` 中添加两行注册：
```java
// 注册 /ctimer 命令
ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
    CtimerCommand.register(dispatcher)
);
// 注册每 tick 回调，驱动定时器
ClientTickEvents.END_CLIENT_TICK.register(client ->
    TimerManager.tick(client)
);
```

## 关键设计决策
- **不需要新的 mixin** — 全部使用 Fabric API 回调（`ClientCommandRegistrationCallback`、`ClientTickEvents`）
- **使用 `sendChatCommand()` 执行命令** — Fabric 的客户端命令系统会拦截此调用：客户端命令在本地执行，服务端命令发送到服务器，两者透明支持
- **定时器在断线后继续存在** — 当玩家不在世界中时静默跳过执行；重新连接后自动恢复
- **时长解析使用字符串后缀匹配** — 无需正则，简单可靠

## 验证方式
1. 编译：`./gradlew build` — 必须无错误
2. 游戏内测试：`/ctimer start infinite interval 5s say Hello` → 验证每 5 秒出现 "Hello"
3. Tab 补全：输入 `/ctimer start` → 验证出现 "infinite"、"1"、"5"、"stop"、"list"、"cancel" 等建议
4. Tab 补全：输入 `/ctimer start 3 interval ` → 验证出现 "1s"、"5s"、"30s" 等时长建议
5. `/ctimer list` → 验证显示活跃定时器信息
6. `/ctimer stop` → 验证所有定时器停止
