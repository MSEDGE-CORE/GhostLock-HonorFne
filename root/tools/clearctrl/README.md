# clearctrl — 清除残留 monkey ActivityController

## 故障现象（2026-10-17 诊断）
- 设置 → 指纹页（`FingerprintSettingsActivity`）打开后 **+13ms 自杀 finish**，永远进不去；
- 锁屏**指纹解锁全局失效**（密码/图案解锁正常）；
- dumpsys fingerprint / HAL / gatekeeper 全部正常——硬件无辜。

## 根因
某次 monkey/自动化运行在 `ATMS.setActivityController(controller, isAMonkey=true)` 注册了 monkey 控制器。该 API（services.jar ATMS:2025-2037）**不注册 linkToDeath、无任何日志**，注册进程退出后标志在 system_server 里永久残留：

```
dumpsys activity | grep -i monkey
  mController=c@4a2f278 mControllerIsAMonkey=true      ← 残留
→ AMS.isUserAMonkey() == mUserIsMonkey || ATMS.isControllerAMonkey() == true（永久）
→ HwUtils.isMonkeyRunning() == true → Utils.T4() == true
→ FingerprintSettingsActivity.onCreate 尾部 if (Utils.T4() || !e0(this)) finish()  ← 防猴子设计，短路自杀
→ keyguard 生物识别路径同样检查 isUserAMonkey → 指纹解锁全局失效
```

取证要点（区分短路路径 vs 硬件路径）：
- Honor settingslib 的 `Log.i/d/e` 走 **HiLog**（domain 0x0D010000），要用 `su 0 hilogcat -d` 采，logcat 里没有；
- 自杀时序：`try to unlock` → 3×getIntent()（chatty 折叠）→ 同毫秒 finish(app-request) 到 AMS，**全程无 e0/h0 的 hilog 日志**（"fingerprint hardware detected"/"fpm is null!" 均缺失）⇒ e0 从未执行 ⇒ T4 短路是唯一路径。

## 修复
root 下反射调用 `IActivityTaskManager.setActivityController(null, false)`（双反射绕隐藏 API 限制，`isControllerAMonkey` 是 ATMS 本地方法、不在 binder 接口上，不能反射调）。

```bash
sh build.sh          # javac -source 8 + d8 + jar（macOS，需 Android SDK 36.0.0/android-34）
sh run.sh            # push + su 0 运行 + dumpsys 前后对比
```

手动等价：
```bash
adb shell "su 0 sh -c 'CLASSPATH=/data/local/tmp/clearctrl.jar app_process / ClearCtrl'"
```

## 验证
- `adb shell dumpsys activity | grep -i monkey` → 无输出即清除；
- `am start -n com.android.settings/.fingerprint.FingerprintSettingsActivity` 不再自杀，可直达录入引导；
- 锁屏指纹解锁恢复。

## 备注
- **重启也会自动清除**（system_server 重置 mController=null），但重启丢 per-boot root；
- 若再跑 monkey/自动化后复发，重跑 `run.sh` 即可；
- 2026-10-17 实测：清除后指纹页直达 `FingerprintScreenIntroActivity`，0 次自杀 finish。
