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

`BridgeSession` 刻意不依赖任何 Minecraft 类，所以这些用例在任何 JVM 上都能跑；MC 适配层由下面的实机流程覆盖。

## 实机验证（clientsmoke + mcmcp）

YSM 的 dev 客户端用 `clientsmoke` 的 `AIDebugServer` 驱动（HTTP `/ping` `/loaded` `/eval` `/enterworld` `/close`），宿主侧 `.mcmcp` 指向 ModDevGradle **预生成**的启动脚本，不要经 Gradle 启动：

```json
{"ysm-dev": ["cmd.exe", "/c", "D:\\...\\run_ysm.cmd", "{port}"]}
```

```bat
@echo off
set "AI_DEBUG_PORT=%~1"
cd /d D:\...\ysm_src\run\client_a
call ..\..\build\moddev\runClient.cmd < nul
```

一次完整流程：

```sh
cd bbconnectmod && ./gradlew compileJava                     # 或 jar（见下方映射说明）
# 把 build/classes 打成 dev-namespace jar，放进 YSM 的 run/client_a/mods
# 把 clientsmoke 的 1.20.1 dev jar 也放进 run/client_a/mods
# 模型夹具：run/client_a/ysm/custom/<name>/（含 ysm.json）
```

模型界面验证要点：`/enterworld` → `/eval` 里 `minecraft.setScreen(new PlayerModelScreen())` → 读 `YsmBridge.folderCards(screen)` 拿到卡片与按钮矩形 → 用 `MouseHandler.onMove` + `MouseHandler.onPress`（refresh 反射，私有）派发真实鼠标点击 → Blockbench 侧项目打开 → 在 BB 里改模型并保存 → 游戏侧聊天栏出现 `Sent N file(s) ... model reload: COMMITTED/...`。

踩坑清单（都是实测撞过的）：

- **YSM 3.0 的模组 id 是 `ysm`**，不是 2.x 的 `yes_steve_model`。用错 id 时 `ModList.get().isLoaded(...)` 恒为 false，叠加绘制与点击会**静默失效**（代码在、界面无热点）。本仓库已改为 `ysm`。
- **`./gradlew jar` 产出 SRG 映射 jar**（生产用），把它丢进 dev 的 `run/*/mods` 会在调用 MC 方法时 `NoSuchMethodError: Minecraft.m_91087_()`。dev 运行要用 `build/classes/java/main` + `build/resources/main` 手工打包的 jar。
- **YSM dev 环境默认跑不起来**：`collabImplementation` 里的 EMI、`collabRuntimeOnly` 里的 moonlight/cloth-config 等 jar 未重映射 refmap，会 `@Shadow field ... was not located`。在 `local.properties` 写 `ysm.fast_run=true` 走官方的编译期依赖-only 模式。
- **`/eval` 会被 mod 自带注解处理器打断**：YSM 在 `META-INF/services/javax.annotation.processing.Processor` 注册了处理器，其实现依赖 shadow 重定位后的 ASM 包，dev classpath 里没有 → 任何 eval 片段都报 `NoClassDefFoundError: com/elfmcys/ysm/api/internal/processor/asm/ClassVisitor`。临时把该 service 文件从 `build/resources/main/META-INF/services/` 移走（或让 eval 加 `-proc:none`）即可。
- **复制 builtin 模型当夹具会因 identity 重复被静默丢弃**：`ysm/custom/<name>` 直接复制 `assets/ysm/builtin/default` 会与内置模型同 hash，catalog 只报 1 个 scan error、客户端看不到任何新模型。改 `ysm.json` 的 metadata/properties 让内容 hash 不同即可。
- 客户端**不会自动退出**，验证结束用 mcmcp 的 `/close`（或按 PID 杀），不要等它自己结束。
- 修改 `src/main/resources` 或 `META-INF/mods.toml` 后必须重新打包 `run/mods` 里的 jar，否则跑的是旧内容。

## 验证状态

| 环节 | 证据 |
|---|---|
| 协议、连接生命周期、错误路径 | `./gradlew test` 3/3 通过（真实 socket，非 mock 协议） |
| 插件端（监听/导入/保存回写） | 真实 Blockbench 实例 + 假游戏端端到端（`blockbench-plugins` 的 `plugins/bbconnectmod`） |
| 原生库 `ysm.dll` | 由 `YesSteveModel-Native` 源码构建（clang-cl 23 + MSVC STL + Conan 2.34），导出 `JNI_OnLoad`/`JNI_OnUnload`/`YSM_ABI_0_1_0_unstable`；dev 客户端日志 `Loading native lib` 后 `NativeLibUtil.isAvailable()==true`，模型能在模型界面正常渲染 |
| 模型界面「连接到 BB」+ 打开项目 | 真实 1.20.1 Forge dev 客户端（clientsmoke 驱动）：文件夹模型卡片底部绘制「连接到 BB」，经 `MouseHandler` 真实点击后 Blockbench 打开工程（`format=bedrock`、47 个 cube、1 张贴图、45 条动画、`Project.bbconnect_pack` 指向模型目录），游戏聊天栏出现 `Bridge connected` 与 `Model folder opened in Blockbench` |
| BB 保存 → 写回 + 游戏内重载 | 在 BB 里加一个 cube 保存：磁盘 `models/main.json` 被整体重写（cube 47→48），游戏聊天栏 `Sent 4 file(s) to the game; model reload: COMMITTED/28 models/0 errors`，YSM 对该目录的 stamp 摘要随之变化（重新观测到新内容） |

## 已知边界

- 只支持**文件夹形式**的模型源（YSM 的 `CURRENT_RAW_DIRECTORY`）。`.zip` / `.ysm` / 服务器下发的模型没有按钮。
- 模型界面按钮是叠加绘制的热点（`ScreenEvent.Render.Post` + `MouseButtonPressed.Pre`），不是真实 widget，因此 YSM 分页/搜索重建界面时无需额外同步状态。
- 几何写回由 Blockbench 的 Bedrock codec 重新编译，可能丢弃 YSM 包内 codec 不认识的字段；动画只写回 Blockbench 已加载且路径位于包内的文件。
