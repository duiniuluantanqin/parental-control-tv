# TCL 后台恢复

目标为 `tcl_m7642`、Android 9。TCL 自启动许可、前台服务与系统重绑配合使用，不能保证在所有厂商系统上绕过“强行停止”。

## 实现

- `UsageTrackingService` 使用前台通知和 `START_STICKY`。普通进程清理后，系统可以重新启动服务。
- 已批准的 `MediaControlListenerService` 被系统创建或连接时，恢复用时统计和答题闹钟，并恢复尚未完成的正式挑战。正常恢复不打开主界面。
- 恢复沿用持久化的闹钟截止时间，不重新赠送完整答题间隔。真正强停会取消系统闹钟，因此不能只看磁盘上的闹钟记录。
- 通知监听启动时在约两分钟内做有限重试，不做永久轮询。解锁前不读取凭据存储；收到用户解锁广播后再恢复。
- `BootReceiver` 继续承担开机恢复。开机答题按电视本地日期每天只触发一次；开机广播与主界面启动共用持久化日期记录，缺少悬浮窗权限时不消耗当天次数，授权返回后可补触发。当天后续重启或重新打开应用不会额外触发启动挑战，正常定时答题和锁定恢复仍按原规则执行。通知监听重绑不触发开机题目。

## 必要设置

检查 TCL 权限与通知使用权：

```powershell
adb shell appops get com.example.tvparental APP_AUTO_START
adb shell appops get com.example.tvparental APP_ASSOC_START
adb shell settings get secure enabled_notification_listeners
```

两个 app-op 应为 `allow`，通知列表应包含 `com.example.tvparental/com.example.tvparental.MediaControlListenerService`。已有授权会在覆盖升级时保留；如缺失，可从应用的 `Allow media pause` 入口授权。这项授权既用于暂停播放，也用于系统绑定后的后台恢复。

## 验证

先确认服务正常运行并记录 PID，再模拟进程被杀与包被强行停止。杀进程后不要用 `am start`、`startservice`、打开应用或重装来干预，否则无法证明自动恢复。

```powershell
adb shell pidof com.example.tvparental
adb shell dumpsys activity services com.example.tvparental
adb shell am force-stop com.example.tvparental
# 等待系统重绑，再检查：
adb shell pidof com.example.tvparental
adb shell dumpsys activity services com.example.tvparental
adb shell "logcat -d -s TvParentalRecovery:I '*:S'"
adb shell "dumpsys alarm | grep -A 5 -B 3 com.example.tvparental"
```

应看到新 PID、已绑定的通知监听、前台 `UsageTrackingService` 及恢复的答题闹钟。闹钟记录可能在服务启动后被远端规则同步调整；恢复动作本身使用原截止时间。未完成挑战按正常产品规则恢复，可能出现答题或额度限制弹窗。

标准 Android 对真正 force-stop 没有通用应用内自救保证：自身闹钟和 Job/WorkManager 不是可靠后备，`onDestroy` 也不保证回调。此路径依赖目标 TCL 的通知监听重新绑定行为；若固件不再绑定，需要用户再次启动应用，或通过正式设备管理/厂商支持的管理方案处理。

## 本次真机结果（2026-11-07）

安装版本 `1.0.14` / `versionCode=38`，通过项目 `build.bat` 构建、`install_device.bat` 覆盖升级。验证期间没有手动打开应用，没有给应用发送启动命令。

| 场景 | 原 PID | 恢复 PID | 观测到完整恢复的时间 | 结果 |
| --- | --- | --- | --- | --- |
| `run-as com.example.tvparental kill -9 10442` | 10442 | 10581 | 3.8 秒 | 前台统计、已绑定通知监听、答题闹钟均恢复 |
| `am force-stop com.example.tvparental` | 10581 | 10705 | 13.4 秒 | 前台统计、已绑定通知监听、答题闹钟均恢复 |

测试以约 3 秒间隔检查，时间为发现完整恢复的时刻。答题闹钟的持久化截止时间从 `1794050360586` 到 `1794050360599`、再到 `1794050360621`，未重置完整间隔。恢复后 `dumpsys activity service` 显示 `usageRunning=true`、`running=true`，且 `lastSampleElapsed` 随统计定时器继续前进。

本次未进行整机重启、实体遥控器待机唤醒、完整五题作答或每日额度耗尽的回归测试；两项强杀测试不能代替这些场景，也不能外推至其他 TCL 固件。
