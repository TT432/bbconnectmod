# bbconnectmod

[中文](README.md) | **English**

Adds a **"Connect to BB"** button to folder-form models in the Yes Steve Model screen: one click opens that model pack as a project in Blockbench; saving in Blockbench writes the pack back and tells the game to reload it, and the player stays on the same model.

## What you need

| Component | Requirement | Where |
|---|---|---|
| Blockbench Community | **≥ 5.3.85** (the plugin's `min_version`) | https://github.com/TT432/blockbench |
| Plugin `ysm_utils` | 2.5.2-community.1 (the version tested) | Community edition plugin store (community source) |
| Plugin `bbconnectmod` (BB Connect) | ≥ 1.0.1 | Community edition plugin store (community source), **depends on `ysm_utils`** |
| Yes Steve Model | `dev/1.20` (3.0 dev, mod id is **`ysm`**) | https://github.com/YesSteveModel/YesSteveModel |
| This mod | 1.0.0, Minecraft **1.20.1** + **Forge 47.4.3+** | Actions artifacts / Releases of this repository |

The `bbconnectmod` plugin depends on `ysm_utils`. When the prerequisite is missing, disabled, or itself missing a prerequisite, the plugin stays *installed* but is not enabled: the plugin list and details page show a "missing prerequisite" badge naming the exact dependency, and the details page offers an "Install dependencies" button. Once the prerequisite is in place the plugin is enabled automatically — no reinstall or restart. Uninstalling or disabling the prerequisite pushes dependents back into that state at runtime.

## Installation

1. Install **Blockbench Community ≥ 5.3.85** (earlier versions have no plugin-dependency support and the store will refuse the plugin).
2. Open Blockbench → plugin store → install `ysm_utils` and `bbconnectmod` (order does not matter).
3. On first enable the plugin requests Node module permissions: `net` (listen on `127.0.0.1` only) and `fs` (read/write the model pack directory). Denying either one disables connecting and writing back.
4. Game side: put this mod's jar and Yes Steve Model into `mods/` and launch a 1.20.1 Forge client.
5. To change the port see "Port" below.

## Using it

1. **Open the YSM model screen** (YSM's own keybind or in-game YSM menu).
2. Only **folder-form** models get the button: the model source is a directory containing `ysm.json`, e.g. `ysm/custom/demo/`. `.zip` / `.ysm` / server-pushed models have no "Connect to BB".
3. Click **"Connect to BB"** below the model card → Blockbench opens that folder as a project (geometry + texture + animations + animation controllers) and stamps bridge markers on it.
4. Edit normally in Blockbench; **saving (Ctrl+S) triggers the sync**: the plugin writes geometry/textures/animations back into the same folder and then asks the game to reload the model directory.
5. The game reports the result in chat, e.g.
   `Sent 1 file(s) to the game; model reload: COMMITTED/28 models/0 errors | selection:kept`
6. To stop, just close Blockbench; the game side does not need to keep a "connection" open.

### Reading the chat / status messages

| Message | Meaning |
|---|---|
| `Sent 0 file(s)` | This save changed nothing, so nothing was written and nothing reloaded (normal) |
| `Sent N file(s) … COMMITTED/28 models/0 errors` | Write-back succeeded and the model directory reloaded |
| `selection:kept` | The player is still on the same model (model ids are content hashes, so the id changes on reload; the mod re-selects by path) |
| `selection:unchanged` | The content hash did not change, so nothing needed fixing (normal) |
| `selection:declined` | The player switched to another resolvable model during the reload; the mod does not override that |
| `selection:missing` | That path is no longer in the model directory (e.g. the write-back broke `ysm.json`); the selection falls back to the default model |
| `selection:unavailable` | Connected to a remote server, so the mod cannot rewrite the server-side selection (only singleplayer integrated servers support this) |

### Port

- Default `127.0.0.1:41234`; both sides are configurable independently:
  - Mod: `config/bbconnectmod-client.toml` → `bridge.port` (restart the client after changing it)
  - Plugin: Blockbench → Settings → Plugins → BB Connect → "BB Connect port" (`bbconnectmod_port`, restarts listening immediately)
- Both values must match, and the port must actually be bindable **on the same machine**. Windows excludes some port ranges (e.g. `50000–50059` and various Hyper-V reservations): those fail with `EADDRINUSE` even though `netstat` shows no owner. Check with `netsh int ipv4 show excludedportrange protocol=tcp` plus an actual bind attempt.
- If listening fails after a port change the plugin status bar names the reason; an unexcluded port in the `20000–40000` range is usually safest.

### If connecting fails

| Symptom | Check |
|---|---|
| Clicking the button reports a connection failure | Is the plugin enabled (not held back by a missing `ysm_utils`)? Do both ports match? Is a security product blocking `127.0.0.1`? |
| Clicking opens nothing in Blockbench | Does the plugin panel say "waiting for the game"? Does the model folder contain `ysm.json`? |
| The in-game model does not change after saving | Is the mod's `sync.auto_reload` `true`? Check the model reload result in the game log |
| Chat shows `selection:unavailable` | You are on a remote server; selection restore only works on singleplayer integrated servers |

## Connection direction and ports (implementation)

- **The game dials out**: the mod never listens; it only connects to `bridge.host:bridge.port` (default `127.0.0.1:41234`) when "Connect to BB" is clicked. The plugin listens on `127.0.0.1`; it never discovers or broadcasts to games.
- Both sides configure their own port (see above).

## Building

Compiling the mod needs a locally built Yes Steve Model: the button, card inspection and hot reload use YSM's public classes directly (`PlayerModelScreen`, `CatalogModelButton`, `ClientModelService`, `ModelRuntime`).

**CI build** (`.github/workflows/build.yml`, `windows-latest` + JDK 17 + Gradle 8.12): checks out YSM at the pinned commit `15c2c25` → applies `ci/ysm-compile.patch` → builds the dev jar into `libs/` → `gradle build` (unit tests included) → uploads `build/libs/bbconnectmod-<version>.jar`; pushing a `v*` tag additionally creates a release with that jar.

What `ci/ysm-compile.patch` is: YSM's `dev/1.20` does not compile under JDK 17 + Forge 47.4.3 — two anonymous `Checkbox` subclasses fail with *variable o is already defined in constructor*. The patch only turns those two anonymous classes into named inner classes; it touches no API used by this mod and changes no behavior. It applies to the CI's one-off build only; no YSM code ships in our artifacts.

Reproduce the same flow locally:

```sh
git clone --filter=blob:none --no-checkout https://github.com/YesSteveModel/YesSteveModel.git ysm
cd ysm
git fetch --depth 1 origin 15c2c25f5b50f54ee099bc474483dc4b588d956d
git checkout --detach FETCH_HEAD
git apply --verbose <this repo>/ci/ysm-compile.patch
# compilation only needs an existing absolute path; the file is never loaded
printf 'ysm.native_path=%s/ysm_placeholder.dll\nysm.fast_run=true\n' "$(pwd -W)" > local.properties
: > ysm_placeholder.dll
gradle -x generateBuiltinModelIndex -x generateBuiltinDefaultContract -x verifyBuiltinModelIndex jar
# output: build/devlibs/ysm-3.0-dev-forge+mc1.20.1-raw.jar

mkdir -p <this repo>/libs
cp build/devlibs/ysm-3.0-dev-forge+mc1.20.1-raw.jar <this repo>/libs/ysm-3.0-dev-forge+mc1.20.1.jar
cd <this repo>
gradle build      # outputs: build/libs/bbconnectmod-1.0.0.jar (dev) and build/reobfJar/output.jar (SRG, for release)
```

`JAVA_HOME` must be JDK 17 (Forge 1.20.1).

About the artifacts (measured): `build/libs/bbconnectmod-*.jar` and `build/reobfJar/output.jar` are **byte-identical** (both md5 `3cdccfb0…`). ForgeGradle 6's `official` channel already emits SRG member names (`Minecraft.m_91087_()`, `Minecraft.f_91062_`), so the `jar` output is production-usable and `reobfJar` has nothing to do. CI still collects `build/reobfJar/output.jar` so that mapping changes keep following the standard flow instead of relying on that coincidence.

## Runtime configuration

| Key | Default | Meaning |
|---|---|---|
| `bridge.host` | `127.0.0.1` | Address the plugin listens on |
| `bridge.port` | `41234` | Port the plugin listens on |
| `sync.auto_reload` | `true` | Reload the YSM model directory after a plugin write-back (`ReloadableModelCatalog.reload()`) |
| `sync.reload_resource_packs` | `false` | Also reload resource packs (equivalent to F3+T) |
| `sync.chat_messages` | `true` | Report connection and sync results in chat |

A port conflict surfaces in AIDebugServer as a single startup line `Failed to start AI Debug Server on port N: BindException`; the client **still starts**, just without a debug channel (automation sees "connected but idle").

## Protocol (v1)

UTF-8, newline-delimited JSON; one message per line, max 16 MiB per line.

Game → plugin:

```json
{"v":1,"type":"hello","mod":"bbconnectmod","mod_version":"1.0.0","mc":"1.20.1"}
{"v":1,"type":"open","path":"C:/Users/me/ysm/custom/demo","model":"demo"}
{"v":1,"type":"sync_ack","path":"C:/Users/me/ysm/custom/demo","ok":true,"reload":"COMMITTED/12 models/0 errors","files":14}
{"v":1,"type":"pong"}
```

Plugin → game:

```json
{"v":1,"type":"hello_ack","plugin":"bbconnectmod","plugin_version":"1.0.0","port":41234}
{"v":1,"type":"opened","path":"...","project":"main.json"}
{"v":1,"type":"save","path":"...","files":["models/main.json","textures/default.png","animations/main.animation.json"]}
{"v":1,"type":"error","message":"..."}
{"v":1,"type":"ping"}
```

`save.files` lists the pack-relative paths the plugin actually wrote; the game answers with `sync_ack` once the reload finished.

## Reload semantics

On `save` the game does three things: **remember the current selection**, reload the directory, then **restore the selection**.

- **Model ids are content hashes**: `ModelInfoCapability.modelId` is a Hash256 of the model content, and `ModelSelectionService.resolve()` falls back to `selectDefault()` when that id is gone. So any write-back that changes content invalidates the old id — this is the root cause of "the player's model resets to the default after saving", independent of whether the write was full or incremental (measured: a full rewrite and a single-cube edit both change the id).
  - Evidence: capability before write-back `modelId=f93c4f11… texture=gsl`; after a geometry-only write-back (1 file) `modelId=5bc2f4a6… texture=default`.
- **`SelectionKeeper`**: before the reload it remembers `(player, path, texture, modelId)` on the integrated-server thread keyed by **directory path** (`CatalogLocation.value()`, e.g. `local:custom/demo`); after the reload it looks the path up again with `catalog.findPath(path)` and re-selects it via `SessionProtocolHandler.applyCommandSelection(...)`. The texture is kept if it still exists, otherwise the model's default / first texture by name is used.
  - It only pins when the player was actually on that model; if the player deliberately switches to another **resolvable** model during the reload, the mod does not override them (`selection:declined`).
  - The outcome is appended to `sync_ack.reload`, e.g. `COMMITTED/28 models/0 errors | selection:kept`; `unchanged` = hash unchanged, `missing` = path no longer in the catalog, `unavailable` = not a singleplayer integrated server.
- **Retry while the catalog is busy**: `ReloadableModelCatalog.reload()` returns `BUSY` immediately while a scan is running (the freshly written files are not picked up in that case). The game retries `BUSY` up to 6 times, 400 ms apart, so "saved but the game did not change" does not happen.
- **Write serialization lives in the plugin**: it does not write to disk before the previous batch's `sync_ack` arrives; saves in between are merged into one deferred sync. YSM validates that the model directory did not change mid-scan (`Model source changed while loading` / `Model root changed while scanning`), and writing during a scan fails the whole scan and keeps the old catalog.

## Name locking

Bone, locator, animation and animation-controller names in a YSM pack are referenced by name from files inside the pack, so renaming breaks references. On connect the plugin writes two markers onto the project:

- `Project.bbconnect_pack`: the model pack directory this project is bound to (the plugin's own marker);
- `Project.ysm_project_info.path`: the field ysm_utils expects (it is also ysm_utils' only signal for "this is a YSM project"; ysm_utils sets it in its own import flow, and this plugin sets it for bridged projects).

Based on that, the Blockbench core locks "Bedrock Export: Normalize Names" to off for **bridged projects**: the read value is forced to false, the settings control is disabled and shows a lock reason, writes are rejected, and export/write-back always uses the original names. After unbinding (switching to a normal project) the user's stored toggle value comes back automatically.

## Write-back granularity

Write-back is **incremental**: each exported file is compared byte-by-byte against the file on disk and skipped when identical; writes go through a temp file + rename. A save that changes nothing writes nothing and sends no `save`, so it triggers no reload and no model id changes.

## Tests

```sh
gradle test
```

`src/test/java/com/bbconnectmod/net/BridgeSessionTest.java` runs a real TCP socket against a fake plugin endpoint and covers the protocol layer **without starting the game**:

- `sendsHelloAndOpenThenAcknowledgesSave`: after connecting it sends `hello` (`v=1`, `mod=bbconnectmod`, `mc=1.20.1`) and `open` (`directory.toAbsolutePath().normalize().toString()` + model name); after the plugin replies `hello_ack`/`opened`/`save`, the game writes `sync_ack` (`ok`/`files`/`reload`/`path`) and hands the reload result to the listener; `ping` must be answered with `pong`.
- `reportsPluginErrorAndClosesOnRequest`: a plugin `error` is surfaced through the listener and does not kill the session thread.
- `rejectsNonDirectoryTargetsWithoutDialling`: a non-directory target dials nothing and reports `Not a model folder`.

`BridgeSession` deliberately depends on no Minecraft class, so these tests run on any JVM; the MC adapter layer is covered by the in-game flow below.

## In-game verification (clientsmoke + mcmcp)

YSM's dev client is driven through clientsmoke's `AIDebugServer` (HTTP `/ping` `/loaded` `/eval` `/enterworld` `/close`). The host side `.mcmcp` (a local dev fixture, not committed) points at ModDevGradle's **pre-generated** launch script; do not launch through Gradle:

```json
{"ysm-dev": ["cmd.exe", "/c", "tools\\run_ysm_dev.cmd", "{port}"]}
```

`tools/run_ysm_dev.cmd` in this repo is the wrapper; it defaults to `<repo>/../work/ysm_src` and honours the `YSM_SRC` environment variable.

A full pass:

```sh
cd bbconnectmod && gradle compileJava                     # or jar (see the mapping note above)
# pack build/classes into a dev-namespace jar and drop it into YSM's run/client_a/mods
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
# also put clientsmoke's 1.20.1 dev jar into run/client_a/mods
# model fixture: run/client_a/ysm/custom/<name>/ (with ysm.json inside)
```

`JAVA_HOME` must be JDK 17 (Forge 1.20.1): when it points at a non-existent `jbr-17.0.14`, Gradle fails with *JAVA_HOME is set to an invalid directory*.

Model-screen verification in short: `/enterworld` → in `/eval` `minecraft.setScreen(new PlayerModelScreen())` → `YsmBridge.folderCards(screen)` for cards and button rects → dispatch real clicks through `MouseHandler.onMove` + `MouseHandler.onPress` (private, reflection) → Blockbench opens the project → edit and save in BB → the game chat shows `Sent N file(s) ... model reload: COMMITTED/...`.

Measured facts worth knowing:

- **`mcmcp_launch`'s crash detection is unreliable**: when an old client is still alive or the port is in `TIME_WAIT`, it may report `Game crashed during loading, exit=0` and kill the occupant while the new client has actually reached the title screen (`run/*/logs/latest.log`, `AI Debug Server started on port <port>`). Trust the log; before restarting, kill leftover clients by process name and pick a bindable port, or just start the client manually with `cmd.exe /c tools\run_ysm_dev.cmd <port>`.
- **`/eval`'s class loader cannot see classes from the mod jar**: `com.bbconnectmod.*` fails with "package does not exist" inside `/eval` snippets; call it via `Class.forName("com.bbconnectmod.ysm.YsmBridge")` reflection. YSM itself (mounted from `build/classes`) can be referenced directly.
- **Modified keybinds such as Alt+Y cannot be injected with `KeyboardHandler.keyPress`**: YSM's `InputCheckUtil.keyMatch` uses `KeyModifier.getActiveModifier()`, which reads real GLFW state, so injected events never make ALT "held". Open the screen with `setScreen(new PlayerModelScreen())` instead; mouse clicks still go through the real `MouseHandler` path (`onMove` + `onPress`, GUI coordinates × `getWindow().getGuiScale()`).
- **Service/job names must be unique**: registering another managed process under an existing name kills the earlier one (it looks like "the client exited cleanly and the socket vanished"). Use unique names in verification scripts.
- **`ysm model set`'s path argument is `StringArgumentType.string()` and takes no `:`**: quote it (`/ysm model set Dev "local:custom/demo" -`) or it fails with `Expected whitespace to end one argument`; confirm the directory resolves first with `cat.resolvePath("local:custom/demo").message()`.

Pitfalls hit in practice:

- **YSM 3.0's mod id is `ysm`**, not 2.x's `yes_steve_model`. With the wrong id `ModList.get().isLoaded(...)` is always false and the overlay drawing and click handling **fail silently** (code present, no hotspot on screen). This repo uses `ysm`.
- **`gradle jar` produces an SRG-mapped jar** (production). Dropping it into a dev `run/*/mods` fails with `NoSuchMethodError: Minecraft.m_91087_()` when calling MC methods. Dev runs need a hand-packed jar from `build/classes/java/main` + `build/resources/main`.
- **YSM's dev environment does not run out of the box**: EMI in `collabImplementation`, moonlight/cloth-config in `collabRuntimeOnly` etc. ship unre-mapped refmaps and fail with `@Shadow field ... was not located`. Setting `ysm.fast_run=true` in `local.properties` uses the official compile-only dependency mode.
- **`/eval` is broken by the mod's own annotation processor**: YSM registers one in `META-INF/services/javax.annotation.processing.Processor` whose implementation needs the shadow-relocated ASM packages, which are absent from the dev classpath → every eval snippet fails with `NoClassDefFoundError: com/elfmcys/ysm/api/internal/processor/asm/ClassVisitor`. Moving that service file out of `build/resources/main/META-INF/services/` (or making eval pass `-proc:none`) works around it.
- **Copying a builtin model as a fixture is silently dropped as a duplicate identity**: copying `assets/ysm/builtin/default` into `ysm/custom/<name>` yields the same hash as the builtin model, so the catalog reports one scan error and the client shows no new model. Changing metadata/properties in `ysm.json` changes the content hash.
- The client **never exits on its own**; close it with mcmcp's `/close` (or kill by PID), do not wait for it.
- After editing `src/main/resources` or `META-INF/mods.toml`, repack the jar in `run/mods` or you keep running the old content.

## Verification status

| Area | Evidence |
|---|---|
| Protocol, connection lifecycle, error paths | `gradle test` 3/3 pass (real sockets, not mocked protocol) |
| Plugin side (listen/import/save write-back) | Real Blockbench instance + fake game endpoint end-to-end (`plugins/bbconnectmod` in `blockbench-plugins`) |
| Native library `ysm.dll` | Built from `YesSteveModel-Native` sources (clang-cl 23 + MSVC STL + Conan 2.34), exports `JNI_OnLoad`/`JNI_OnUnload`/`YSM_ABI_0_1_0_unstable`; dev client logs `Loading native lib` then `NativeLibUtil.isAvailable()==true`, models render in the model screen |
| "Connect to BB" + project open | Real 1.20.1 Forge dev client (clientsmoke driven): the button is drawn under folder-form model cards, a real `MouseHandler` click opens the project in Blockbench (`format=bedrock`, 49 cubes, 1 texture, animations present, project bound to the model folder), chat shows `Bridge connected to 127.0.0.1:41234` and `Model folder opened in Blockbench` |
| BB save → incremental write-back | Save with no changes: `models/main.json` mtime and md5 unchanged, no sync line in chat (0 files); after adding one cube: only `models/main.json` written, chat `Sent 1 file(s) to the game; model reload: COMMITTED/28 models/0 errors` |
| Player stays on the same model | With `local:custom/demo` selected (`texture=gsl`), after write-back `ModelInfoCapability.modelId` went from `a6e38ee2…` to the new content hash `2a4ac5a0…` while `catalog.find(id).location().value()` is still `local:custom/demo`; log `BB Connect kept Dev on local:custom/demo (gsl)`, `sync_ack.reload` ends with `\| selection:kept` |
| Rapid consecutive saves (serialization) | Two saves 400 ms apart: two separate `COMMITTED/28 models/0 errors` (about 3 s apart, the second waited for the first ack); the disk ends up with both edits, `catalog.report().errorCount()==0`, both kept the selection `selection:kept` |
| Deletions are written back | Deleting a cube in the project and saving: the cube disappears from the exported file, cube count 52→51, `Report` has no errors |
| Restoring the pre-write directory state | After removing all test cubes and saving, `models/main.json` md5 returned to the pre-test `c70fc47a…` (byte-identical) |
| Release artifact (single-file plugin 1.0.1) | Deploying the final `plugins/bbconnectmod/bbconnectmod.js` and restarting BB: handshake logs `handshake accepted by plugin 1.0.1` (same `PLUGIN_VERSION`), another save still yields `Sent 1 file(s) … COMMITTED/28 models/0 errors \| selection:kept` and the exported file contains the new cube; deleting that cube and saving brings the md5 back to `c70fc47a…` |
| Plugin-side port configuration | Plugin setting `bbconnectmod_port` 41234 → 41235 → 41234 (plugin reloaded each time): `netstat` shows exactly one `127.0.0.1:<port>` listener, the old port leaves nothing behind |
| Bridged projects do not rename | Creating a group named `bad name` in a bridged `demo` and saving: the exported file still has `"name": "bad name"`; removing `bbconnect_pack`/`ysm_project_info` from the same project makes `Codecs.bedrock.compile()` emit `bad_name`. In the settings window the toggle reads off and is disabled with an amber lock reason, and both `set(true)` and `ui_value = true` are rejected (`master_value` stays true) |
| Deferred enable on `ysm_utils` dependency | After uninstalling `ysm_utils`, `bbconnectmod` becomes `dependency_blocked=true / disabled=false / missing=['ysm_utils']` and `41234` stops listening; loading the real `ysm_utils` (`loadFromFile(..., false)`) restores it (`blocked=false`, actions re-registered, `41234` listening again); the details page shows "⚠ Dependencies missing" and "Install dependencies" instead of enable/disable |
| Published plugin loads (store content) | Installing the registry copy `plugins/bbconnectmod/bbconnectmod.js` (`BBPlugin.register`) into a real BB 5.3.85 instance: `disabled=false`, `dependency_blocked=false`, `missing=[]`, actions registered, `127.0.0.1:41234` LISTENING |
| Portable 5.3.85 lock behavior | Portable `Blockbench-Community_x64_5.3.85.exe`: `settings['community_bedrock_export_rename'].lock()` returns `{value:false, reason:"…"}` when the project carries `Project.bbconnect_pack`, `value/ui_value` are forced false while `master_value` stays true, and both return to true once the marker is removed |

## Known limits

- Only **folder-form** model sources are supported (YSM's `CURRENT_RAW_DIRECTORY`). `.zip` / `.ysm` / server-pushed models get no button.
- The model-screen button is an overlay hotspot (`ScreenEvent.Render.Post` + `MouseButtonPressed.Pre`), not a real widget, so YSM rebuilding the screen for paging/search needs no extra state sync.
- Geometry is recompiled by Blockbench's Bedrock codec, which may drop fields the codec does not know; animations are written back only for files Blockbench loaded and that live inside the pack.
- Selection restore only works on **singleplayer integrated servers** (it needs `ModelInfoCapability` and `ServerModelService` on the server thread); on remote servers the server decides after the reload and `sync_ack.reload` carries `selection:unavailable`.
- If a write-back makes the directory unacceptable to YSM (e.g. removing a required `ysm.json` field), the path disappears from the catalog and the selection falls back to the default model (`selection:missing`) — that is a content problem, not a failure of selection restore.
- The plugin-side port change is verified in a real instance; the **mod-side** port change is only confirmed in code (`session()` reads `bridge.port` when first established, changing it needs a client restart) and no separate non-default-port in-game case was run this round.
