@echo off
chcp 65001 >nul
title GhostLock-HonorFne 一键临时 root
cd /d "%~dp0"

echo ==================================================
echo    GhostLock-HonorFne  一键临时 root（Windows）
echo    目标设备: 荣耀70 FNE-AN00（Bootloader 锁定）
echo ==================================================
echo.

rem ---------- 1/5 依赖检查 ----------
echo [1/5] 检查依赖...
where adb >nul 2>nul
if errorlevel 1 goto :no_adb
set "BASH="
if exist "C:\Program Files\Git\bin\bash.exe" set "BASH=C:\Program Files\Git\bin\bash.exe"
if not defined BASH if exist "C:\Program Files (x86)\Git\bin\bash.exe" set "BASH=C:\Program Files (x86)\Git\bin\bash.exe"
if not defined BASH (
    where bash >nul 2>nul && set "BASH=bash"
)
if not defined BASH goto :no_bash
echo       OK: adb 与 Git Bash 均已就绪
echo.

rem ---------- 2/5 连接设备 ----------
echo [2/5] 连接设备
echo   [1] USB 数据线
echo   [2] WiFi（手机与电脑同一局域网，端口 5555）
choice /c 12 /n /m "请选择 1 或 2: "
if errorlevel 2 goto :wifi
goto :devcheck
:wifi
set "IP="
set /p "IP=请输入手机 IP 地址: "
if "%IP%"=="" goto :bad_ip
echo       正在连接 %IP%:5555 ...
adb connect %IP%:5555
:devcheck
adb get-state >nul 2>nul
if errorlevel 1 (
    echo       等待设备接入（请插好 USB 并允许 USB 调试授权）...
    adb wait-for-device
)
echo       已连接:
adb devices
echo.

rem ---------- 3/5 root 状态检查 ----------
echo [3/5] 检查 KernelSU 状态...
adb shell "grep -q kernelsu /proc/modules" >nul 2>nul
if not errorlevel 1 (
    echo       KernelSU 已在运行，无需再次提权。
    call :ensure_module
    echo.
    pause
    exit /b 0
)
echo       KernelSU 未加载，继续。
echo.

rem ---------- 4/5 部署检查 ----------
echo [4/5] 检查设备端部署...
set NEED_DEPLOY=0
adb shell "[ -x /data/local/tmp/e ]" >nul 2>nul || set NEED_DEPLOY=1
adb shell "[ -f /data/local/tmp/ksu_fne/chain_hook.sh ]" >nul 2>nul || set NEED_DEPLOY=1
adb shell "[ -f /data/local/tmp/netfix_packet/sepolicy.rule ]" >nul 2>nul || set NEED_DEPLOY=1
if "%NEED_DEPLOY%"=="1" goto :deploy
echo       已部署，跳过。
goto :grind

:deploy
echo       首次使用，推送文件（约 20MB，请稍候）...
adb shell "mkdir -p /data/local/tmp/ksu_fne"
adb push "root/exploit/ghostlock" /data/local/tmp/e
if errorlevel 1 goto :push_fail
adb shell "chmod 755 /data/local/tmp/e"
for %%F in (chain_hook.sh kernelsu.ko ksud magiskpolicy ksu_rules load_ko kmsg_dumper fne_syms.txt) do (
    adb push "root/ksu-late-load/%%F" /data/local/tmp/ksu_fne/ || goto :push_fail
)
adb shell "chmod 755 /data/local/tmp/ksu_fne/chain_hook.sh /data/local/tmp/ksu_fne/load_ko /data/local/tmp/ksu_fne/kmsg_dumper"
adb push "root/ksu-modules/netfix_packet" /data/local/tmp/
if errorlevel 1 goto :push_fail
echo       部署完成。
echo.

:grind
rem ---------- 5/5 运行 ----------
echo [5/5] 开始 per-boot 提权（W1 - W2 - KSU 晚加载）
echo       * 全自动运行，含多次设备重启，可能耗时较长
echo       * 请勿关闭本窗口；勿同时运行其他 grind
echo       * 成功后脚本自动停止
echo.
"%BASH%" "root/host-scripts/run_forever.sh"
echo.

rem root 落地后 detached chain_hook 还需数十秒完成 KSU 晚加载，
rem 立即检查 /proc/modules 会误报失败——轮询等待（最长 180 秒）。
echo       等待 KSU 晚加载链完成（最长 180 秒）...
set /a KSU_WAIT=0
:wait_ksu
adb shell "grep -q kernelsu /proc/modules" >nul 2>nul
if not errorlevel 1 goto :ksu_ok
set /a KSU_WAIT=KSU_WAIT+1
if %KSU_WAIT% GEQ 36 goto :ksu_fail
ping -n 6 127.0.0.1 >nul
goto :wait_ksu

:ksu_fail
call :ensure_module
echo ==================================================
echo    [未成功] 本次 grind 未落地，可重新运行再试。
echo ==================================================
goto :done_root

:ksu_ok
call :ensure_module
echo ==================================================
echo    [成功] KernelSU 已加载，临时 root 就绪！
echo ==================================================

:done_root
echo.
pause
exit /b 0

:no_adb
echo [错误] 未找到 adb。
echo        请安装 Android platform-tools 并将 adb 所在目录加入 PATH。
pause
exit /b 1

:no_bash
echo [错误] 未找到 Git Bash。
echo        请安装 Git for Windows（https://git-scm.com/download/win）。
pause
exit /b 1

:bad_ip
echo [错误] 未输入 IP 地址。
pause
exit /b 1

:push_fail
echo [错误] 文件推送失败。请检查连接与 USB 调试授权后重试。
pause
exit /b 1

rem ---------- netfix_packet 模块安装（KSU 就绪后调用） ----------
rem /data/adb 为 root 目录，adb 直推不行：先经 /data/local/tmp 中转，su 0 cp 装入。
rem 规则在 ksud late-load 时读取注入，故新装模块自下一次提权/重启起自动激活。
:ensure_module
adb shell "su 0 sh -c '[ -d /data/adb/modules/netfix_packet ] || cp -r /data/local/tmp/netfix_packet /data/adb/modules/netfix_packet'" >nul 2>nul
adb shell "su 0 sh -c '[ -f /data/adb/modules/netfix_packet/sepolicy.rule ]'" >nul 2>nul && (
    echo       netfix_packet 模块已就位（网络/状态栏/AOD 修复）
)
goto :eof
