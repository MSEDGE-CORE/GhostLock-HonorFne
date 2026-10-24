@echo off
chcp 65001 >nul
title GhostLock-HonorFne 一键临时 root
cd /d "%~dp0"

rem 闪退追踪: 每阶段一行痕迹写入 run_root.log。窗口意外关闭时,
rem 该文件最后一条记录 = 闪退断点, 把文件发给开发者即可定位。
set "LOG=%~dp0run_root.log"
> "%LOG%" echo === run_root.bat start %date% %time% ===

echo ==================================================
echo    GhostLock-HonorFne  一键临时 root（Windows）
echo    目标设备: 荣耀70 FNE-AN00（Bootloader 锁定）
echo ==================================================
echo.

rem ---------- 模式选择：部署 / 撤销 ----------
echo 请选择运行模式:
echo   [1] 部署 / 一键临时 root（默认）
echo   [2] 撤销部署（清理设备端传输文件与模块中转；不卸载管理器/app）
set "MODE=1"
set /p "MODE=输入 1 或 2 后回车: "
if "%MODE%"=="2" goto :undo
>> "%LOG%" echo mode select: %MODE%
goto :deploy

:undo
where adb >nul 2>nul || goto :no_adb
set "BASH="
if exist "C:\Program Files\Git\bin\bash.exe" set "BASH=C:\Program Files\Git\bin\bash.exe"
if not defined BASH if exist "C:\Program Files (x86)\Git\bin\bash.exe" set "BASH=C:\Program Files (x86)\Git\bin\bash.exe"
if not defined BASH (
    where bash >nul 2>nul && set "BASH=bash"
)
if not defined BASH goto :no_bash
"%BASH%" "root/host-scripts/uninstall.sh"
echo.
pause
exit /b 0

:deploy
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
>> "%LOG%" echo [1/5] deps ok
echo.

rem ---------- 2/5 连接设备 ----------
echo [2/5] 连接设备（USB）
:devcheck
adb get-state >nul 2>nul
if errorlevel 1 (
    echo       等待设备接入（请插好 USB 并允许 USB 调试授权）...
    adb wait-for-device
)
echo      已连接:
adb devices
>> "%LOG%" echo [2/5] device connected
echo.

rem ---------- 3/5 root 状态检查 ----------
echo [3/5] 检查 KernelSU 状态...
rem 无线 adb 单次 shell 瞬断(空响应)会把"已加载"误判为"未加载"而整跑
rem exploit, 同 boot 重复 W2 的 slab_drain fork 风暴会触发系统保护性重启。
rem 最多 2 次检测抗瞬断(与 run_root.command / run_w2.sh 同规则)。
rem goto 循环而非 for 块: 块内 %errorlevel%/%VAR% 有延迟展开坑。
set "KSU_TRIES=0"
set "KSU_LOADED=0"
:ksu_chk
adb shell "grep -q kernelsu /proc/modules" >nul 2>nul
if not errorlevel 1 (
    set "KSU_LOADED=1"
    goto :ksu_done
)
set /a KSU_TRIES+=1
if %KSU_TRIES% LSS 2 (
    echo       检测无响应，重试(%KSU_TRIES%/2)...
    ping -n 6 127.0.0.1 >nul
    goto :ksu_chk
)
:ksu_done
if "%KSU_LOADED%"=="1" (
    echo       KernelSU 已在运行，无需再次提权。
    >> "%LOG%" echo [3/5] ksu already loaded - top up install/activate
    call :ensure_module
    rem 上次运行可能超时误判"未落地"(模块已加载但 su 未就绪): 补齐安装/激活,
    rem 重跑即修复
    echo       --- 补齐 KSU / LSP 管理器与 Zygisk 激活 ---
    "%BASH%" "root/host-scripts/install_managers.sh"
    "%BASH%" "root/host-scripts/activate_zygisk.sh"
    echo.
    pause
    exit /b 0
)
echo       KernelSU 未加载，继续。
>> "%LOG%" echo [3/5] ksu not loaded
echo.

rem ---------- 4/5 部署（无条件覆盖） ----------
rem 覆盖而非存在即跳过：chain_hook.sh / ko / syms 迭代频繁，存在校验会
rem 让已部署设备拿不到新版（LSA merge 修复就卡在旧 chain_hook 上）。
if not exist "root\exploit\ghostlock" (
    echo [错误] 未找到 root\exploit\ghostlock（exploit 二进制）。
    echo        本仓库应自带预编译产物；若你通过 git clone 获取且缺失，
    echo        请先更新到最新版本；或参考 README "重新构建" 一节自备
    echo        Android NDK r27c 后执行:  cd root\exploit ^&^& ANDROID_NDK_HOME=.. make
    >> "%LOG%" echo [ERR] ghostlock missing
    timeout /t 30 /nobreak >nul
    pause
    exit /b 1
)
for %%F in (chain_hook.sh kernelsu.ko ksud magiskpolicy ksu_rules load_ko kmsg_dumper fne_syms.txt) do (
    if not exist "root\ksu-late-load\%%F" (
        echo [错误] 未找到 root\ksu-late-load\%%F
        >> "%LOG%" echo [ERR] missing root\ksu-late-load\%%F
        timeout /t 30 /nobreak >nul
        pause
        exit /b 1
    )
)
echo [4/5] 部署设备端文件（约 20MB，覆盖同步，请稍候）...
adb shell "mkdir -p /data/local/tmp/ksu_fne"
adb push "root/exploit/ghostlock" /data/local/tmp/e
if errorlevel 1 goto :push_fail
adb shell "chmod 755 /data/local/tmp/e"
for %%F in (chain_hook.sh kernelsu.ko ksud magiskpolicy ksu_rules load_ko kmsg_dumper fne_syms.txt) do (
    adb push "root/ksu-late-load/%%F" /data/local/tmp/ksu_fne/ || goto :push_fail
)
adb shell "chmod 755 /data/local/tmp/ksu_fne/chain_hook.sh /data/local/tmp/ksu_fne/magiskpolicy /data/local/tmp/ksu_fne/ksud /data/local/tmp/ksu_fne/load_ko /data/local/tmp/ksu_fne/kmsg_dumper"
echo      部署完成。
>> "%LOG%" echo [4/5] deploy done
echo.

rem netfix_packet 模块体积小、迭代快：每次都重新传输，
rem 确保模块修复（sepolicy 规则 / LSPosed tmpfs 补丁）总能同步到设备。
:sync_module
echo       同步 netfix_packet 模块...
adb shell "rm -rf /data/local/tmp/netfix_packet" >nul 2>nul
adb push "root/ksu-modules/netfix_packet" /data/local/tmp/
if errorlevel 1 goto :push_fail
echo.

:grind
rem ---------- 5/5 运行 ----------
echo [5/5] 开始 per-boot 提权（W1 - W2 - KSU 晚加载）
echo       * 全自动运行，含多次设备重启，可能耗时较长
echo       * 请勿关闭本窗口；勿同时运行其他 grind
echo       * 成功后脚本自动停止
echo.
>> "%LOG%" echo [5/5] grind start %date% %time% - 窗口若在 grind 期间消失=被杀, 非脚本退出
"%BASH%" "root/host-scripts/run_forever.sh"
echo.

rem root 落地后 detached chain_hook 还需数十秒完成 KSU 晚加载，
rem 立即检查 /proc/modules 会误报失败——轮询等待（最长 180 秒），
rem 期间实时增量显示设备端 chain_status / chain.log（wait_ksu.sh）。
"%BASH%" "root/host-scripts/wait_ksu.sh"
if not errorlevel 1 goto :ksu_ok
goto :ksu_fail

:ksu_fail
>> "%LOG%" echo [5/5] grind failed - retryable
call :ensure_module
echo ==================================================
echo    [未成功] 本次 grind 未落地，可重新运行再试。
echo ==================================================
goto :done_root

:ksu_ok
>> "%LOG%" echo [5/5] ksu up - installing managers/zygisk
call :ensure_module
echo ==================================================
echo    [成功] KernelSU 已加载，临时 root 就绪！
echo ==================================================
echo       --- 自动安装 KSU / LSP 管理器 ---
"%BASH%" "root/host-scripts/install_managers.sh"
rem Zygisk/LSPosed 注入激活（链后时机，12:40 已验证配方；链内跑会破坏模块）
"%BASH%" "root/host-scripts/activate_zygisk.sh"
call :install_launcher_mods
goto :done_root

:done_root
echo.
pause
exit /b 0

:no_adb
echo [错误] 未找到 adb。
echo        请安装 Android platform-tools 并将 adb 所在目录加入 PATH。
echo        下载: https://developer.android.com/tools/releases/platform-tools
echo        (解压后把目录加入 PATH, 或把 4 个 exe 放进本目录也可)
>> "%LOG%" echo [ERR] no_adb - install platform-tools and add to PATH
rem 30s 强制停留: 连按回车的缓冲按键会瞬间吃掉 pause 造成"闪退"假象
timeout /t 30 /nobreak >nul
pause
exit /b 1

:no_bash
echo [错误] 未找到 Git Bash。
echo        请安装 Git for Windows（https://git-scm.com/download/win）。
>> "%LOG%" echo [ERR] no_bash - install Git for Windows
timeout /t 30 /nobreak >nul
pause
exit /b 1

:push_fail
echo [错误] 文件推送失败。请检查连接与 USB 调试授权后重试。
>> "%LOG%" echo [ERR] push_fail - check cable/USB-debug authorization
timeout /t 30 /nobreak >nul
pause
exit /b 1

rem ---------- 可选板块：安装桌面修改（Launcher/SystemUI hooks） ----------
rem KSU/LSPosed 成功后调用。默认跳过，选 Y 安装：
rem   HnLauncherPort.apk (LSPosed 模块: 桌面布局 + 控制中心动效优化)
:install_launcher_mods
echo.
choice /c YN /n /m "是否安装桌面修改（HnLauncherPort）? [Y=安装 N=跳过]: "
if errorlevel 2 (
    echo       已跳过桌面修改安装。
    goto :eof
)
if not exist "hooks\launcher\out\HnLauncherPort.apk" (
    echo       [错误] 未找到 hooks\launcher\out\HnLauncherPort.apk
    goto :eof
)
echo       安装 HnLauncherPort.apk ...
adb install -r "hooks\launcher\out\HnLauncherPort.apk" >nul 2>nul
if errorlevel 1 (
    echo       [错误] APK 安装失败
    goto :eof
)
echo       APK 安装成功。
echo       提示: 首次需在 LSPosed 管理器中启用模块并勾选
echo             作用域（荣耀桌面 + 系统界面），重启对应应用后生效。
goto :eof

rem ---------- netfix_packet 模块安装（KSU 就绪后调用） ----------
rem /data/adb 为 root 目录，adb 直推不行：先经 /data/local/tmp 中转，su 0 合并同步装入。
rem 无条件同步（cp -rf 合并，保留运行时 netfix.log）：保证模块修复/更新始终生效。
rem 规则在 ksud late-load 时读取注入，故更新自下一次提权/重启起自动激活。
:ensure_module
adb shell "su 0 sh -c 'mkdir -p /data/adb/modules/netfix_packet && cp -rf /data/local/tmp/netfix_packet/. /data/adb/modules/netfix_packet/'" >nul 2>nul
adb shell "su 0 sh -c '[ -f /data/adb/modules/netfix_packet/sepolicy.rule ]'" >nul 2>nul && (
    echo       netfix_packet 模块已同步（网络/RRO/状态栏/导航栏/AOD 修复）
) || (
    echo       [警告] netfix_packet 模块同步失败
)
goto :eof
