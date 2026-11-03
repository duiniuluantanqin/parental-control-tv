# 看电视前答题

面向雷鸟/TCL Android 9 电视的 Kotlin 家长控制原型。电视每隔 10 分钟显示一道全屏四选一题；方向键选项、OK 键提交。每题只有一次作答机会，答错后锁定 10 分钟。默认每日可用 60 分钟，额度用完后到次日恢复。设备屏幕亮起时计入使用时长，锁定期间不计入。

应用自带 50 道初中难度题目，覆盖古诗词、数学、科学、地理和历史。题库与每日限额、答题间隔、答错锁定时长、同步间隔可以从家长 Web 后台远程管理。设备启动时全量同步一次，之后按同步间隔（默认 5 分钟，1–60 分钟可配）轮询；后台规则也可在电视应用主页手动同步。

## Android 应用

在 Android Studio 中打开项目目录，等待 Gradle 同步。项目使用 Kotlin、Android Gradle Plugin 9.0.0、`compileSdk 37`、`minSdk 28`，Gradle Wrapper 固定为 9.1.0。若 SDK Platform 37 缺失，请在 SDK Manager 中安装。

Windows 命令行构建：

```powershell
.\gradlew.bat assembleDebug
```

APK 位于 `app\build\outputs\apk\debug\app-debug.apk`。应用首页可设置后台 URL、手动同步题目和规则，也能查看今日已用时间。首次使用需要在电视确认系统悬浮窗权限；应用不能静默授予 Android 特殊权限。

屏幕亮起时长按 30 秒采样，额度临近耗尽时会缩短采样间隔。后台服务在开机后恢复；TCL 固件还需要通过 ADB 放行 `APP_AUTO_START` 与 `APP_ASSOC_START`。完整安装升级步骤见 [docs/INSTALL_TCL.md](docs/INSTALL_TCL.md)。

## 本机后台

需要 Python 3.11 或更高版本，无第三方 Python 依赖。先启动本机服务：

```powershell
$adminToken = (New-Guid).ToString('N') + (New-Guid).ToString('N')
$env:TV_PARENTAL_ADMIN_TOKEN = $adminToken
Write-Output "Admin token: $adminToken"
python backend/server.py
```

管理页面在 `http://127.0.0.1:8765`。将电脑与电视接入同一局域网，在电视主页输入 `http://<电脑局域网地址>:8765`，保存并同步。Windows 防火墙需允许可信家庭网络访问 TCP 8765。管理页面使用启动时设置的令牌；题目和规则 API 只读接口供电视同步。

## 部署到阿里云 ECS

复用 ECS 上已有的 Caddy，为新域名增加 `reverse_proxy 127.0.0.1:8080`，Python 后台由 systemd 托管。按 [部署文档](docs/DEPLOY_ECS.md) 完成一次性初始化后，推送后台或种子题库到 `main`，GitHub Actions 就会测试、同步代码、重启后台并检查 HTTPS 接口。

管理页面和电视后台地址均为 `https://<域名>`。代码位于 `/var/www/html/tvparental`，令牌保存在 `backend/.env`，题库和规则保存在 `backend/data/state.json`；自动发布会保留这些运行数据，请另外备份。应用端口 8080 只监听本机，无需在安全组开放。

已有宿主机 Caddy 时不要运行仓库的 `backend/compose.yaml`，其中自带的 Caddy 会争用 80/443 端口。

## 后端测试

```powershell
python -m unittest discover -s backend/tests -v
```

## 当前边界

Android 用屏幕交互状态估算电视使用时间，电视厂商的休眠和进程管理策略可能影响后台服务。全屏覆盖可以阻挡当前画面的遥控操作，但无法强制停止其他应用的视频解码或音频，也无法替代设备所有者级别的系统管控。
