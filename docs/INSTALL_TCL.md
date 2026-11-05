# 雷鸟/TCL 电视安装与升级

适用设备：雷鸟/TCL `tcl_m7642`，Android 9/API 28，ADB 地址 `10.0.0.44:5555`。此固件会拒绝 `adb install` 和 `adb install -r`，使用电视自带的 TCL“多屏互动”推送 APK。电视端必须确认安装或升级。本流程已在这台电视上验证。

## 升级前

1. 电视开机，并与电脑连接同一网络。
2. 保持包名 `com.example.tvparental` 不变。当前版本为 `0.9.5`（`versionCode` 25）；每次改动都在 `app/build.gradle.kts` 中把 `versionCode` 增加 1，并同步更新 `versionName`。不要更换用于签名的电脑/Android Studio 用户配置，否则电视可能拒绝覆盖安装。
3. 在项目根目录打开 PowerShell。下面命令按本机 ADB 路径编写；若 ADB 安装位置变了，先修改 `$adb`。

## 构建并推送

先构建 APK，并准备本地文件服务目录：

```powershell
$ErrorActionPreference = 'Stop'
$adb = 'D:\Downloads\platform-tools-latest-windows\platform-tools\adb.exe'
$tvIp = '10.0.0.44'
$adbTarget = "${tvIp}:5555"
$pushDir = Join-Path (Get-Location) '.tools\tv-parental-push'

$localGradle = '.\.tools\gradle-9.1.0\bin\gradle.bat'
if (Test-Path $localGradle) {
    & $localGradle assembleDebug
} else {
    .\gradlew.bat assembleDebug
}
New-Item -ItemType Directory -Force -Path $pushDir | Out-Null
Copy-Item '.\app\build\outputs\parental\Parental-TV.apk' (Join-Path $pushDir 'TvParental.apk') -Force
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

脚本运行后，看电视右上角的“多屏互动”提示：用遥控器按“右”选中“同意”，再按 OK。等待电脑端输出 `Installed.`。如果希望测试纯 ADB 覆盖安装，可先运行 `& $adb -s $adbTarget install -r '.\app\build\outputs\parental\Parental-TV.apk'`；当前电视固件返回 `install apk has be disabled from pm by system default!`，所以应继续使用 TCL 推送流程。若超时且电视没有出现提示，可重新执行推送命令；先确认 HTTP 服务和 ADB reverse 仍正常。

## 验证

检查包确实安装、版本号正确，并确认悬浮窗权限：

```powershell
& $adb -s $adbTarget shell dumpsys package com.example.tvparental | Select-String 'versionCode=|versionName='
& $adb -s $adbTarget shell appops get com.example.tvparental SYSTEM_ALERT_WINDOW
& $adb -s $adbTarget shell appops get com.example.tvparental APP_AUTO_START
& $adb -s $adbTarget shell appops get com.example.tvparental APP_ASSOC_START
```

首次安装后若权限不是 `allow`，执行一次：

```powershell
& $adb -s $adbTarget shell appops set com.example.tvparental SYSTEM_ALERT_WINDOW allow
```

雷鸟/TCL 固件还会单独限制应用自启动。若 `APP_AUTO_START` 或 `APP_ASSOC_START` 显示 `ignore`，按 aerial-tv 的设备安装做法通过 ADB 放行这两个厂商 app-op：

```powershell
& $adb -s $adbTarget shell appops set com.example.tvparental APP_AUTO_START allow
& $adb -s $adbTarget shell appops set com.example.tvparental APP_ASSOC_START allow
```

打开应用，确认首页显示的每日额度、题目间隔和题库数量。默认每日额度为 60 分钟、答题间隔为 10 分钟、答错锁定为 10 分钟；题库包含 50 道初中难度题目并随机抽取。首次打开应用会自动跳转到悬浮窗权限页，请用遥控器允许；随后在首页选择“设置解锁密码”，用方向键输入两次 6 位密码。按“立即答题测试”验证居中悬浮框；四个选项分成 2×2，方向键移动并高亮，按 OK 提交，没有单独的提交按钮。答错后应显示 10 分钟倒计时，期间遥控器不能关闭锁定页，只能用方向键输入正确密码提前解锁。升级后通常会保留已授予的悬浮窗权限。

应用启动后自动从 `https://tvparental.mediainsight360.com` 获取题库和规则，无需在电视配置地址、用户名或密码。升级后也固定使用该域名，不再读取旧版保存的后台地址。可在首页选择“立即同步题目和规则”进行验证。管理页面和部署方式见项目根目录的 `README.md`。设备默认每 5 分钟同步一次，同步间隔可在后台调整为 1–60 分钟。

应用首次启动时如果没有悬浮窗权限，会自动打开系统授权页；开机时如果权限缺失，也会自动打开该页面。仍需在电视上确认开启，Android 不允许普通应用静默授予此权限。已有 `allow` 授权会保留到升级和重启之后。应用会在 `LOCKED_BOOT_COMPLETED` 与 `BOOT_COMPLETED` 时恢复 10 分钟答题闹钟和时长统计服务；TCL 自启动 app-op 也必须为 `allow`。今日使用时长按屏幕亮起时间估算，午夜按电视本地日期清零；错误锁定期间不累计时长。待机唤醒不一定会发送启动广播，请通过电视完整重启验证冷启动行为。

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
