@echo off
rem Dev client for bbconnectmod: Yes Steve Model's own dev run with clientsmoke's AIDebugServer.
rem
rem Requirements in the YSM dev clone:
rem   1. local.properties: ysm.native_path=<abs path to a built ysm.dll> and ysm.fast_run=true
rem   2. run/client_a/mods/: this mod's dev jar + clientsmoke's 1.20.1 dev jar
rem   3. ./gradlew createClientLaunchScript (build/moddev/runClient.cmd must exist)
rem   4. run/client_a/ysm/custom/<name>/ : a folder-form model (ysm.json inside) to test against
rem
rem No Gradle in this chain: Gradle's console handling blocks the mcmcp launcher. "< nul" keeps
rem the generated script's failure-path "pause" from hanging the launcher on a crash.
set "AI_DEBUG_PORT=%~1"
rem 默认假设：本仓库与 YSM dev clone 同级（<parent>/bbconnectmod 与 <parent>/work/ysm_src）。
rem 其他布局用环境变量 YSM_SRC 指定绝对路径。
if not defined YSM_SRC set "YSM_SRC=%~dp0..\..\work\ysm_src"
cd /d "%YSM_SRC%\run\client_a"
call "%YSM_SRC%\build\moddev\runClient.cmd" < nul
