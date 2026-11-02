# 看电视前答题

面向雷鸟/TCL Android 9 电视的 Kotlin 家长控制原型。电视每隔 10 分钟显示一道全屏四选一题；方向键选项、OK 键提交。每题只有一次作答机会，答错后锁定 10 分钟。默认每日可用 60 分钟，额度用完后到次日恢复。设备屏幕亮起时计入使用时长，锁定期间不计入。

应用自带 50 道初中难度题目，覆盖古诗词、数学、科学、地理和历史。题库与每日限额、答题间隔、答错锁定时长可以从家长 Web 后台远程管理。设备每 5 分钟同步一次；后台规则也可在电视应用主页手动同步。

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

建议为 ECS 配置域名 A 记录，开放安全组 TCP 80/443，并用 Compose 部署 Caddy HTTPS 反向代理和应用：

1. 在 `backend/.env.example` 基础上创建 `backend/.env`，填入 ECS 域名及至少 24 位随机管理员令牌。此文件不会提交到 Git。
2. 在项目根目录运行 `docker compose --env-file backend/.env -f backend/compose.yaml up -d --build`。
3. 首次启动后，Caddy 会为域名申请 TLS 证书。管理地址为 `https://<域名>`；电视后台地址填 `https://<域名>`。

题库状态保存在 `backend/data/state.json`，位于持久化目录；重建容器不会清除题库或规则。请备份 `backend/data/`。ECS 只应对公网开放 HTTPS 入口，切勿把应用的 8080 端口直接暴露到公网。管理员令牌通过 HTTPS 输入管理页，不要将真实令牌写入仓库或命令历史。

## 后端测试

```powershell
python -m unittest discover -s backend/tests -v
```

## 当前边界

Android 用屏幕交互状态估算电视使用时间，电视厂商的休眠和进程管理策略可能影响后台服务。全屏覆盖可以阻挡当前画面的遥控操作，但无法强制停止其他应用的视频解码或音频，也无法替代设备所有者级别的系统管控。
