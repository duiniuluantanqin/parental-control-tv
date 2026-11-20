# 雷鸟/TCL 电视安装与升级

适用设备：雷鸟/TCL `tcl_m7642`，Android 9/API 28，ADB 地址 `10.0.0.8:5555`（2026-11-12 更新）。此固件会拒绝 `adb install` 和 `adb install -r`，使用电视自带的 TCL“多屏互动”推送 APK。电视端必须确认安装或升级。本流程已在这台电视上验证。

`scripts/install_device.bat` 接受可选的 IP 参数，例如在仓库根目录运行 `.\scripts\install_device.bat 10.0.0.9`（不带端口）。IP 取值顺序为命令行参数、环境变量 `TV_PARENTAL_TV_IP`、默认值 `10.0.0.8`；ADB 端口固定为 5555。ADB 连接失败时会保留实际错误信息；中文输出不会因 Windows 默认编码而导致解码异常。

`scripts/install_device.bat` 是 Windows 启动入口，内部调用同目录的 `install_device.py`。Python 脚本负责查找 ADB、提供临时 APK 下载服务、发起 TCL 多屏互动安装、校验版本、设置权限及清理连接；两个文件需要一起保留。

## 升级前

1. 电视开机，并与电脑连接同一网络。
2. 保持包名 `com.example.tvparental` 不变。版本名统一读取根目录 `VERSION`，Android 的 `versionCode` 每次递增 1。不要更换用于签名的电脑/Android Studio 用户配置，否则电视可能拒绝覆盖安装。
3. 在项目根目录打开 PowerShell。以下命令优先使用 PATH 中的 ADB；如果未加入 PATH，可将 `$adb` 设置为当前电脑 Android SDK `platform-tools` 目录下的 `adb.exe`。仓库安装器 `scripts/install_device.bat` 会自动从 Android SDK 环境变量、`local.properties` 或 PATH 定位 ADB。

## 构建并推送

先构建 APK，并准备本地文件服务目录：

```powershell
$ErrorActionPreference = 'Stop'
$adb = (Get-Command adb -ErrorAction Stop).Source
$tvIp = '10.0.0.8'
$adbTarget = "${tvIp}:5555"
$pushDir = Join-Path (Get-Location) '.tools\tv-parental-push'

& .\scripts\build.bat
if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
New-Item -ItemType Directory -Force -Path $pushDir | Out-Null
$appVersion = (Get-Content '.\VERSION' -Raw).Trim()
$apkPath = ".\app\build\outputs\parental\Parental-TV-v$appVersion.apk"
Copy-Item $apkPath (Join-Path $pushDir 'TvParental.apk') -Force
Invoke-WebRequest -Uri 'https://aerial.qust.me/tools/tcast-push.py' -UseBasicParsing -OutFile (Join-Path $pushDir 'tcast-push.py')

& $adb version
& $adb connect $adbTarget
& $adb devices
```

确认 `adb devices` 中电视状态为 `device`。读取本次构建的版本号，配置 ADB reverse，并启动只监听本机回环地址的临时 HTTP 服务：

```powershell
$metadata = Get-Content '.\app\build\outputs\apk\debug\output-metadata.json' -Raw | ConvertFrom-Json
$versionCode = $metadata.elements[0].versionCode
$workingDir = (Resolve-Path $pushDir).Path

& $adb -s $adbTarget reverse tcp:8765 tcp:8765
$server = Start-Process -FilePath 'python' `
    -ArgumentList @('-m', 'http.server', '8765', '--bind', '127.0.0.1') `
    -WorkingDirectory $workingDir -WindowStyle Hidden -PassThru
```

发起 TCL 安装请求。脚本等待电视确认，最多 300 秒：

```powershell
try {
    python (Join-Path $pushDir 'tcast-push.py') $tvIp `
        'http://127.0.0.1:8765/TvParental.apk' `
        'com.example.tvparental' '健康使用' $versionCode 300
} finally {
    & $adb -s $adbTarget reverse --remove tcp:8765
    Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
}
```

脚本运行后，看电视右上角的“多屏互动”提示：用遥控器按“右”选中“同意”，再按 OK。等待电脑端输出 `Installed.`。如果希望测试纯 ADB 覆盖安装，可先运行 `& $adb -s $adbTarget install -r $apkPath`；当前电视固件返回 `install apk has be disabled from pm by system default!`，所以应继续使用 TCL 推送流程。若超时且电视没有出现提示，可重新执行推送命令；先确认 HTTP 服务和 ADB reverse 仍正常。

## 验证

检查包确实安装、版本号正确，并确认悬浮窗权限：

```powershell
& $adb -s $adbTarget shell dumpsys package com.example.tvparental | Select-String 'versionCode=|versionName='
& $adb -s $adbTarget shell appops get com.example.tvparental SYSTEM_ALERT_WINDOW
& $adb -s $adbTarget shell appops get com.example.tvparental APP_AUTO_START
& $adb -s $adbTarget shell appops get com.example.tvparental APP_ASSOC_START
```

通过 `scripts/install_device.bat` 安装时，安装器会在电视确认安装且版本校验通过后，自动把悬浮窗权限 `SYSTEM_ALERT_WINDOW` 以及 TCL 自启动权限 `APP_AUTO_START`、`APP_ASSOC_START` 设为 `allow` 并回读核验。若应用是通过其他方式安装或重新安装后相关权限失效，可手动执行：

```powershell
& $adb -s $adbTarget shell appops set com.example.tvparental SYSTEM_ALERT_WINDOW allow
& $adb -s $adbTarget shell appops set com.example.tvparental APP_AUTO_START allow
& $adb -s $adbTarget shell appops set com.example.tvparental APP_ASSOC_START allow
```

答题弹窗前暂停播放还需要本应用的“通知使用权”，与悬浮窗及自启动权限分别设置。这台 TCL Android 9 电视通过 ADB 授权：

```powershell
& $adb -s $adbTarget shell cmd notification allow_listener com.example.tvparental/com.example.tvparental.MediaControlListenerService 0
& $adb -s $adbTarget shell settings get secure enabled_notification_listeners
```

回读结果应包含 `com.example.tvparental/com.example.tvparental.MediaControlListenerService`。该授权允许应用查询媒体会话并发送暂停命令，播放器需要提供可控的 Android 媒体会话。卸载重装后应重新检查此授权；当前安装脚本只自动设置上述三个 app-op。

打开应用，确认首页显示的每日额度、题目间隔和题库数量。默认每日额度为 60 分钟、答题间隔为 10 分钟、答错锁定为 10 分钟；题库包含 50 道初中难度题目并随机抽取。默认家长 PIN 为“上、下、左、右、上、上”，可在首页通过 `Set parent PIN` 修改。首页不再提供权限跳转和测试题入口，也不会自动打开授权页，相关权限须通过上述 ADB 命令设置。启动时答题按电视本地日期每天只触发一次，当天后续重启或重新打开应用不会额外触发；高级设置中的 `Questions on first startup of the day` 开关可关闭此行为。启动答题使用正式五题挑战，五题中至少答对四题即可解锁；全部作答后正确数少于四题才开始答错锁定。四个选项分成 2×2，方向键移动并高亮，按 OK 提交，没有单独的提交按钮。升级后通常会保留已授予的悬浮窗权限。

首次使用或升级到支持账号绑定的版本后，在电视首页选择“绑定账号”，窗口自动获取六位绑定码。登录后台并在“设备与重置”输入绑定码，核对设备后确认。绑定成功后，应用从 `https://tvparental.mediainsight360.com` 获取当前账号的题库和规则，无需在电视配置地址、用户名或密码。可用首页的 `Sync now` 验证。绑定、换绑和重装后的处理见 [使用说明](USER_GUIDE.md#注册与绑定电视)，部署方式见 [部署指南](DEPLOY_ECS.md)。

通过 `scripts/install_device.bat` 安装时，ADB 会设置悬浮窗和自启动权限；若通过其他方式安装且权限缺失，须手动执行上述授权命令。应用会在 `LOCKED_BOOT_COMPLETED` 与 `BOOT_COMPLETED` 时恢复 10 分钟答题闹钟和时长统计服务。今日使用时长按屏幕亮起时间估算，午夜按电视本地日期清零；错误锁定期间不累计时长。待机唤醒不一定会发送启动广播，请通过电视完整重启验证冷启动行为。

## 收尾

安装与验证完成后断开电脑 ADB，避免占用电视的连接：

```powershell
& $adb disconnect $adbTarget
& $adb devices
```

推送期间创建的 HTTP 服务和 ADB reverse 应由上面的 `finally` 自动清理。若 PowerShell 被强制关闭，手动运行：

```powershell
& $adb -s $adbTarget reverse --remove tcp:8765
& $adb disconnect $adbTarget
```
