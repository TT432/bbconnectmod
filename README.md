# bbconnectmod

1.20.1 Forge 模组端：在 Yes Steve Model 的模型界面给「以文件夹形式加载」的模型提供「连接到 BB」，把模型文件夹交给 Blockbench Community 的 `bbconnectmod` 插件；Blockbench 保存后由插件写回模型文件夹并通知本模组重载模型目录。

## 连接方向与端口

- **游戏端主动拨号**：模组不监听任何端口，只在点击「连接到 BB」时连接 `bridge.host:bridge.port`（默认 `127.0.0.1:41234`）。插件端在 `127.0.0.1` 上监听；插件不会发现游戏，也没有广播。
- 端口两端各自可配：
  - 模组：`config/bbconnectmod-client.toml` → `bridge.port`
  - 插件：Blockbench 设置 → 插件 → BB Connect 的端口（`bbconnectmod_port`）

## 构建

模组编译期依赖本地构建的 Yes Steve Model：插件的按钮、卡片解析与热重载都直接使用 YSM 的公开类（`PlayerModelScreen`、`CatalogModelButton`、`ClientModelService`、`ModelRuntime`）。

**CI 自动构建**（`.github/workflows/build.yml`，`windows-latest` + JDK 17 + Gradle 8.12）：按固定 commit `15c2c25` 检出 YSM → 应用 `ci/ysm-compile.patch` → 构建 dev jar 放进 `libs/` → `gradle build`（含单元测试）→ 上传 `build/libs/bbconnectmod-<version>.jar`；推 `v*` tag 时额外建 release 并附该 jar。

`ci/ysm-compile.patch` 是什么：YSM 的 `dev/1.20` 在 JDK 17 + Forge 47.4.3 下有两个匿名 `Checkbox` 子类编不过（javac 报 *已在构造器中定义了变量 o*）。补丁只把这两个匿名类改成命名内部类，不涉及本模组用到的任何 API，也不改行为。补丁只作用于 CI 的一次性构建，发布物里没有 YSM 代码。

本地复现同一套流程：

```sh
git clone --filter=blob:none --no-checkout https://github.com/YesSteveModel/YesSteveModel.git ysm
cd ysm
git fetch --depth 1 origin 15c2c25f5b50f54ee099bc474483dc4b588d956d
git checkout --detach FETCH_HEAD
git apply --verbose <本仓库>/ci/ysm-compile.patch
# 编译只需要一个存在的绝对路径，不加载它
printf 'ysm.native_path=%s/ysm_placeholder.dll\nysm.fast_run=true\n' "$(pwd -W)" > local.properties
: > ysm_placeholder.dll
gradle -x generateBuiltinModelIndex -x generateBuiltinDefaultContract -x verifyBuiltinModelIndex jar
# 产物：build/devlibs/ysm-3.0-dev-forge+mc1.20.1-raw.jar

mkdir -p <本仓库>/libs
cp build/devlibs/ysm-3.0-dev-forge+mc1.20.1-raw.jar <本仓库>/libs/ysm-3.0-dev-forge+mc1.20.1.jar
cd <本仓库>
gradle build      # 产物：build/libs/bbconnectmod-1.0.0.jar（dev）与 build/reobfJar/output.jar（SRG，发布用）
```

`JAVA_HOME` 必须是 JDK 17（Forge 1.20.1）。

产物的区别（实测）：`build/libs/bbconnectmod-*.jar` 与 `build/reobfJar/output.jar` **字节一致**（md5 都是 `3cdccfb0…`）。ForgeGradle 6 的 `official` channel 编译出来的字节码已经是 SRG 名（`Minecraft.m_91087_()`、`Minecraft.f_91062_`），所以 `jar` 的产物本身就是可发布的生产 jar，`reobfJar` 无事可做。CI 仍从 `build/reobfJar/output.jar` 取件，是为了在映射发生变化时沿用标准流程而不是依赖这个巧合。

## 运行期配置

| 键 | 默认 | 说明 |
|---|---|---|
| `bridge.host` | `127.0.0.1` | 插件监听地址 |
| `bridge.port` | `41234` | 插件监听端口 |
| `sync.auto_reload` | `true` | 收到插件写回后重载 YSM 模型目录（`ReloadableModelCatalog.reload()`） |
| `sync.reload_resource_packs` | `false` | 额外执行一次资源包重载（等价 F3+T） |
| `sync.chat_messages` | `true` | 在聊天栏报告连接与同步结果 |

端口要在**同一台机器**上实测可绑定再改：本机存在 Windows 保留端口段，`47823`、`47824`、`45000` 在没有监听者的情况下也直接 `EADDRINUSE`（`netstat` 查不到占用者），`45317`、`45351`、`45361`、`46011` 同批实测同样不可 bind，而 `41234`、`41235`、`40000`、`38123`、`51234` 可正常绑定。判断端口能不能用不要只看 `netstat`，用 `netsh int ipv4 show excludedportrange protocol=tcp` 加上实际 bind 试探。

端口冲突在 AIDebugServer 上的表现是启动日志一行 `Failed to start AI Debug Server on port N: BindException`，随后客户端**照常启动**、只是没有调试通道（自动化脚本会看到「已连接但状态 idle」）。

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

## 重载语义

收到 `save` 后游戏端做两步：先**记住当前选择**，再重载目录，最后**恢复选择**。

- **模型 id 是内容哈希**：Yes Steve Model 的 `ModelInfoCapability.modelId` 是模型内容的 Hash256，`ModelSelectionService.resolve()` 找不到该 id 就 `selectDefault()` 回退默认模型。所以只要写回改变了内容，旧 id 必然失效——这是「保存后玩家模型变成默认模型」的根因，与全量还是增量写回无关（实测：全量重写与只改一个 cube 都换 id）。
  - 证据：写回前 capability `modelId=f93c4f11… texture=gsl`；只改几何（1 个文件）写回后 `modelId=5bc2f4a6… texture=default`。
- **`SelectionKeeper`**：重载前在集成服务器线程按**目录路径**（`CatalogLocation.value()`，如 `local:custom/bbdemo`）记住 `(player, path, texture, modelId)`，重载后在服务器线程用 `catalog.findPath(path)` 取到新 id 并 `SessionProtocolHandler.applyCommandSelection(...)` 重新选中同一路径；贴图仍存在就保留，否则退回模型默认/字典序第一个贴图。
  - 只在玩家原本就选中该模型时才 pin；重载期间玩家主动切到另一个**可解析**的模型时不覆盖（`selection:declined`）。
  - 结果写进 `sync_ack.reload`，形如 `COMMITTED/28 models/0 errors | selection:kept`；`unchanged` = 内容哈希没变、`missing` = 该路径已不在目录、`unavailable` = 非单人集成服务器。
- **扫描忙时重试**：`ReloadableModelCatalog.reload()` 在有扫描进行中时立即返回 `BUSY`（此时刚写入的内容不会被采纳）。游戏端对 `BUSY` 最多重试 6 次、每次间隔 400 ms，避免「保存了但游戏里没变」。
- **写入串行化由插件负责**：插件在收到上一批 `sync_ack` 前不写盘，把期间的保存合并为一次延后同步。YSM 的扫描会校验模型目录在扫描期间是否变化（`Model source changed while loading` / `Model root changed while scanning`），扫描中写盘会让整次扫描失败并保留旧目录。

## 名称锁定（不改名）

YSM 模型包里的骨骼、定位器、动画、动画控制器名都由包内文件按名称引用，改名会让引用断掉。绑定时模组侧插件在项目上写入两个标记：

- `Project.bbconnect_pack`：本项目对应的模型包目录（插件自己的绑定标记）；
- `Project.ysm_project_info.path`：ysm_utils 的约定字段（它也用它决定「当前信息」菜单是否可用——即 ysm_utils 判断「当前是 YSM 项目」的唯一依据，只在它自己的导入流程里写入，桥接项目由本插件补上）。

Blockbench 核心据此在**桥接项目**下把「Bedrock Export: Normalize Names」锁定为关闭：读取值强制 false、设置窗口控件禁用并显示锁定原因、写入被拒绝；导出与写回一律按原名称。断开桥接（换一个普通项目）后用户原来的开关值自动恢复。

## 写回粒度

插件写回是**增量**的：每个导出文件先与磁盘上现有内容逐字节比较，一致就跳过；写入走临时文件 + rename 原子替换。内容零变化的保存既不写盘也不发 `save`，因此不会触发重载，也就不会改变任何模型 id。

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

YSM 的 dev 客户端用 `clientsmoke` 的 `AIDebugServer` 驱动（HTTP `/ping` `/loaded` `/eval` `/enterworld` `/close`），宿主侧 `.mcmcp`（本仓库已提交）指向 ModDevGradle **预生成**的启动脚本，不要经 Gradle 启动：

```json
{"ysm-dev": ["cmd.exe", "/c", "D:\\...\\run_ysm.cmd", "{port}"]}
```

```bat
@echo off
set "AI_DEBUG_PORT=%~1"
cd /d D:\...\ysm_src\run\client_a
call ..\..\build\moddev\runClient.cmd < nul
```

仓库内的 `tools/run_ysm_dev.cmd` 就是这份包装脚本（`YSM_SRC` 默认取 `<仓库>/../work/ysm_src`，按需改）。

一次完整流程：

```sh
cd bbconnectmod && ./gradlew compileJava                     # 或 jar（见下方映射说明）
# 把 build/classes 打成 dev-namespace jar，放进 YSM 的 run/client_a/mods
python - <<'EOF'
import zipfile, pathlib
root = pathlib.Path('build')
out = pathlib.Path(r'<ysm_src>/run/client_a/mods/bbconnectmod-1.0.0.jar')
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for base in (root / 'classes/java/main', root / 'resources/main'):
        for p in base.rglob('*'):
            if p.is_file():
                z.write(p, p.relative_to(base).as_posix())
EOF
# 把 clientsmoke 的 1.20.1 dev jar 也放进 run/client_a/mods
# 模型夹具：run/client_a/ysm/custom/<name>/（含 ysm.json）
```

`JAVA_HOME` 必须是 JDK 17（Forge 1.20.1）：本机默认的 `JAVA_HOME` 指向不存在的 `jbr-17.0.14` 时 Gradle 直接报 *JAVA_HOME is set to an invalid directory*。

模型界面验证要点：`/enterworld` → `/eval` 里 `minecraft.setScreen(new PlayerModelScreen())` → 读 `YsmBridge.folderCards(screen)` 拿到卡片与按钮矩形 → 用 `MouseHandler.onMove` + `MouseHandler.onPress`（refresh 反射，私有）派发真实鼠标点击 → Blockbench 侧项目打开 → 在 BB 里改模型并保存 → 游戏侧聊天栏出现 `Sent N file(s) ... model reload: COMMITTED/...`。

补充事实（实测）：

- **`mcmcp_launch` 的失败判定不可信**：旧客户端仍存活或端口处于 `TIME_WAIT` 时，它可能报 `Game crashed during loading, exit=0` 并杀掉占用者，而实际新客户端已在 `run/*/logs/latest.log` 跑到 title screen、`AI Debug Server started on port <port>`。以日志为准确认真实状态；重启前先按进程名杀干净遗留客户端并换一个可 bind 的新端口，或干脆手动 `cmd.exe /c tools\run_ysm_dev.cmd <port>` 起客户端。
- **`/eval` 的类加载器看不到模组 jar 里的类**：`com.bbconnectmod.*` 在 `/eval` 片段里编译报「程序包不存在」，要经 `Class.forName("com.bbconnectmod.ysm.YsmBridge")` 反射调用；YSM 自身（`build/classes` 直挂）反而可直接引用。
- **Alt+Y 这类带修饰键的键位无法用 `KeyboardHandler.keyPress` 注入**：YSM 的 `InputCheckUtil.keyMatch` 用 `KeyModifier.getActiveModifier()`，读的是真实 GLFW 键态，注入事件不会让 ALT 变「按下」。改成 `setScreen(new PlayerModelScreen())` 直接开界面；鼠标点击仍走 `MouseHandler` 真实链路（`onMove` + `onPress`，GUI 坐标 × `getWindow().getGuiScale()`）。
- **服务/作业名不能重复**：用同名再注册一个被托管进程会结束先前同名进程（表现为「客户端干净退出、socket 消失」）。验证脚本里给客户端进程用唯一名字。
- **`ysm model set` 的路径参数是 `StringArgumentType.string()`，不含 `:`**：`local:custom/bbdemo` 必须在命令里加引号（`/ysm model set Dev "local:custom/bbdemo" -`），否则报 `Expected whitespace to end one argument`；先用 `cat.resolvePath("local:custom/bbdemo").message()` 确认目录可解析。

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
| 模型界面「连接到 BB」+ 打开项目 | 真实 1.20.1 Forge dev 客户端（clientsmoke 驱动）：文件夹模型卡片底部绘制「连接到 BB」，经 `MouseHandler` 真实点击后 Blockbench 打开工程（`format=bedrock`、49 个 cube、1 张贴图、动画齐全、项目绑定模型目录），游戏聊天栏出现 `Bridge connected to 127.0.0.1:41234` 与 `Model folder opened in Blockbench` |
| BB 保存 → 增量写回 | 无改动保存：`models/main.json` mtime 与 md5 均不变、聊天栏无同步行（0 文件写入）；加一个 cube 保存：只有 `models/main.json` 被写，聊天栏 `Sent 1 file(s) to the game; model reload: COMMITTED/28 models/0 errors` |
| 保存后玩家仍停在原模型 | 选中 `local:custom/bbdemo`（`texture=gsl`）后写回：`ModelInfoCapability.modelId` 从 `a6e38ee2…` 变为新内容哈希 `2a4ac5a0…`，`catalog.find(id).location().value()` 仍是 `local:custom/bbdemo`，日志 `BB Connect kept Dev on local:custom/bbdemo (gsl)`，`sync_ack.reload` 尾部 `\| selection:kept` |
| 连续快速保存（串行化） | 两次保存间隔 400 ms：两次独立 `COMMITTED/28 models/0 errors`（间隔约 3 s，第二次等第一次 ack），最终磁盘包含两次改动、`catalog.report().errorCount()==0`，选择保持两次均 `selection:kept` |
| 删除内容能写回 | 从项目删除一个 cube 保存：导出文件里对应 cube 消失、cube 总数 52→51，`Report` 无 error |
| 恢复写入前的目录状态 | 移除全部测试 cube 后保存，`models/main.json` md5 回到测试前的 `c70fc47a…`（逐字节一致） |
| 发布产物（插件 1.0.1 单文件） | 部署最终 `plugins/bbconnectmod/bbconnectmod.js` 并重启 BB：握手日志 `handshake accepted by plugin 1.0.1`（`PLUGIN_VERSION` 同源），再保存一次仍是 `Sent 1 file(s) … COMMITTED/28 models/0 errors \| selection:kept`，导出文件含新增 cube；随后删除该 cube 保存，md5 再次回到 `c70fc47a…` |
| 插件端端口可配 | 插件设置 `bbconnectmod_port` 41234 → 41235 → 41234（每次重载插件生效）：`netstat` 每次都只有一个 `127.0.0.1:<port>` 监听者、旧端口不残留 |
| 桥接项目不改名 | 桥接打开的 `bbdemo` 里新建组 `bad name` 保存写回：导出文件仍是 `"name": "bad name"`；同一项目临时去掉 `bbconnect_pack`/`ysm_project_info` 后 `Codecs.bedrock.compile()` 里同组变成 `bad_name`。设置窗口里该开关显示为关且禁用，琥珀色提示锁定原因，`set(true)`/`ui_value = true` 均被拒绝（`master_value` 保持原值 true） |
| 依赖 ysm_utils 的延后启用 | 卸载 `ysm_utils` 后 `bbconnectmod` 变为 `dependency_blocked=true / disabled=false / missing=['ysm_utils']` 且 `41234` 停止监听；重新装载真实 `ysm_utils`（`loadFromFile(..., false)`）后自动恢复（`blocked=false`、动作重新注册、`41234` 重新监听）；详情页显示「⚠ Dependencies missing」与「Install dependencies」按钮、不显示启用/禁用按钮 |

## 已知边界

- 只支持**文件夹形式**的模型源（YSM 的 `CURRENT_RAW_DIRECTORY`）。`.zip` / `.ysm` / 服务器下发的模型没有按钮。
- 模型界面按钮是叠加绘制的热点（`ScreenEvent.Render.Post` + `MouseButtonPressed.Pre`），不是真实 widget，因此 YSM 分页/搜索重建界面时无需额外同步状态。
- 几何写回由 Blockbench 的 Bedrock codec 重新编译，可能丢弃 YSM 包内 codec 不认识的字段；动画只写回 Blockbench 已加载且路径位于包内的文件。
- 选择恢复只对**单人集成服务器**有效（需要服务器线程上的 `ModelInfoCapability` 与 `ServerModelService`）；连远端服务器时重载后由服务器决定，`sync_ack.reload` 会带 `selection:unavailable`。
- 若写回把模型目录改成不再被 YSM 接受（例如删掉 `ysm.json` 必需字段），该路径从目录消失，选择自然回退默认模型（`selection:missing`），这是内容本身的问题而不是选择恢复失效。
- 插件端换端口已实机验证；**模组侧换端口**只在代码层确认（`session()` 首次建立时读 `bridge.port`，改配置需重启客户端），本轮未单独跑非默认端口的实机用例。
