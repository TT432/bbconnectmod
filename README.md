# bbconnectmod

1.20.1 Forge 模组端：在 Yes Steve Model 的模型界面给「以文件夹形式加载」的模型提供「连接到 BB」，把模型文件夹交给 Blockbench Community 的 `bbconnectmod` 插件；Blockbench 保存后由插件写回模型文件夹并通知本模组重载模型目录。

## 连接方向与端口

- **游戏端主动拨号**：模组不监听任何端口，只在点击「连接到 BB」时连接 `bridge.host:bridge.port`（默认 `127.0.0.1:41234`）。插件端在 `127.0.0.1` 上监听；插件不会发现游戏，也没有广播。
- 端口两端各自可配：
  - 模组：`config/bbconnectmod-client.toml` → `bridge.port`
  - 插件：Blockbench 设置 → 插件 → BB Connect 的端口（`bbconnectmod_port`）

## 构建

模组编译期依赖本地构建的 Yes Steve Model：插件的按钮、卡片解析与热重载都直接使用 YSM 的公开类（`PlayerModelScreen`、`CatalogModelButton`、`ClientModelService`、`ModelRuntime`）。

```sh
# 1. 取开源 YSM（dev/1.20 分支）并构建 raw（named mappings）jar
git clone --depth 1 -b dev/1.20 https://github.com/YesSteveModel/YesSteveModel.git ysm
cd ysm
./gradlew compileJava jar -x generateBuiltinModelIndex -x generateBuiltinDefaultContract -x verifyBuiltinModelIndex
# 产物：build/devlibs/ysm-3.0-dev-forge+mc1.20.1-raw.jar

# 2. 放入本工程的 libs/ 并构建
cp build/devlibs/ysm-3.0-dev-forge+mc1.20.1-raw.jar <本仓库>/bbconnectmod/libs/ysm-3.0-dev-forge+mc1.20.1.jar
cd <本仓库>/bbconnectmod
./gradlew build      # 产物：build/libs/bbconnectmod-1.0.0.jar
```

`YSM` 的 native 库在构建时被 YSM 的 Gradle 脚本要求（`YSM_NATIVE_PATH` / `ysm.native_path`），只做编译用途时可用已有的 `ysm-core.dll` 占位，并跳过 `generateBuiltin*` / `verifyBuiltinModelIndex` 任务。

本仓库实测所用的本地构建对 YSM 源码做了两处 UI 语法改造（`PlayerModelScreen`、`ExtraPlayerConfigScreen` 里两个匿名 `Checkbox` 子类改为命名内部类），因为当前 javac 对这两个匿名类报 «变量 o 已在构造器中定义»；改造不涉及本模组用到的任何 API。

## 运行期配置

| 键 | 默认 | 说明 |
|---|---|---|
| `bridge.host` | `127.0.0.1` | 插件监听地址 |
| `bridge.port` | `41234` | 插件监听端口 |
| `sync.auto_reload` | `true` | 收到插件写回后重载 YSM 模型目录（`ReloadableModelCatalog.reload()`） |
| `sync.reload_resource_packs` | `false` | 额外执行一次资源包重载（等价 F3+T） |
| `sync.chat_messages` | `true` | 在聊天栏报告连接与同步结果 |

端口要在**同一台机器**上实测可绑定再改：本机存在 Windows 保留端口段，`47823`、`47824`、`45000` 在没有监听者的情况下也直接 `EADDRINUSE`（`netstat` 查不到占用者），而 `41234`、`41235`、`40000`、`51234` 可正常绑定。判断端口能不能用不要只看 `netstat`。

## 协议（v1）

UTF-8、换行分隔的 JSON；一行一个消息，单行上限 16 MiB。

游戏端 → 插件：

```json
{"v":1,"type":"hello","mod":"bbconnectmod","mod_version":"1.0.0","mc":"1.20.1"}
{"v":1,"type":"open","path":"C:/Users/me/ysm/custom/demo","model":"demo"}
{"v":1,"type":"sync_ack","path":"C:/Users/me/ysm/custom/demo","ok":true,"reload":"COMMITTED/12 models/0 errors","files":14}
{"v":1,"type":"pong"}
```

插件 → 游戏端：

```json
{"v":1,"type":"hello_ack","plugin":"bbconnectmod","plugin_version":"1.0.0","port":41234}
{"v":1,"type":"opened","path":"...","project":"main.json"}
{"v":1,"type":"save","path":"...","files":["models/main.json","textures/default.png","animations/main.animation.json"]}
{"v":1,"type":"error","message":"..."}
{"v":1,"type":"ping"}
```

`save` 的 `files` 是插件实际写回的包内相对路径；游戏端重载完成后用 `sync_ack` 回报结果。

## 测试

```sh
./gradlew test
```

`src/test/java/com/bbconnectmod/net/BridgeSessionTest.java` 用真实 TCP socket 配一个假插件端，覆盖协议层，**不需要启动游戏**：

- `sendsHelloAndOpenThenAcknowledgesSave`：连上后发 `hello`（`v=1`、`mod=bbconnectmod`、`mc=1.20.1`）与 `open`（`directory.toAbsolutePath().normalize().toString()` + 模型名）；插件回 `hello_ack`/`opened`/`save` 之后，游戏端写 `sync_ack`（`ok`/`files`/`reload`/`path`）并把重载结果交给 listener；`ping` 必须回 `pong`。
- `reportsPluginErrorAndClosesOnRequest`：插件的 `error` 只经 listener 上抛，不中断会话线程。
- `rejectsNonDirectoryTargetsWithoutDialling`：目标不是目录时不发起连接，直接报 `Not a model folder`。

`BridgeSession` 刻意不依赖任何 Minecraft 类，所以这些用例在任何 JVM 上都能跑；MC 适配层（`BbConnectClient`、`YsmBridge`、`BbConnectScreenOverlay`）只有编译期证据。

## 验证状态

| 环节 | 证据 |
|---|---|
| 协议、连接生命周期、错误路径 | `./gradlew test` 3/3 通过（真实 socket，非 mock 协议） |
| 插件端（监听/导入/保存回写） | 在真实 Blockbench 实例里跑过端到端（见 `blockbench-plugins` 的 `plugins/bbconnectmod`） |
| MC 适配、YSM 热重载、模型界面按钮 | 仅 `./gradlew build` 编译通过 + 对 YSM 公开 API 的静态匹配；本机没有 3.0 的原生 `ysm.dll`（YesSteveModel-Native 需 Conan/CMake 构建），**未在真实游戏内运行**，故按上述证据交付 |

## 已知边界

- 只支持**文件夹形式**的模型源（YSM 的 `CURRENT_RAW_DIRECTORY`）。`.zip` / `.ysm` / 服务器下发的模型没有按钮。
- 模型界面按钮是叠加绘制的热点（`ScreenEvent.Render.Post` + `MouseButtonPressed.Pre`），不是真实 widget，因此 YSM 分页/搜索重建界面时无需额外同步状态。
- 几何写回由 Blockbench 的 Bedrock codec 重新编译，可能丢弃 YSM 包内 codec 不认识的字段；动画只写回 Blockbench 已加载且路径位于包内的文件。
