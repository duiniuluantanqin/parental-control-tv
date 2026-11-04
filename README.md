# 看电视前答题

面向雷鸟/TCL Android 9 电视的 Kotlin 家长控制原型。电视每隔 10 分钟显示一道居中的大悬浮框四选一题（覆盖约 86%×82% 的屏幕）；方向键选项、OK 键提交。每题只有一次作答机会，答错后锁定 10 分钟。家长可在应用主页设置 6 位方向键密码（如 上下上下左右），答错锁定时输入正确密码可提前解锁；连续输错 5 次需等待 30 秒。默认每日可用 60 分钟，额度用完后到次日恢复。设备屏幕亮起时计入使用时长，锁定期间不计入。

应用自带 50 道中等难度题目，覆盖古诗词、数学、科学、地理和历史。题库与每日限额、答题间隔、答错锁定时长、同步间隔可以从家长 Web 后台远程管理。设备启动时全量同步一次，之后按同步间隔（默认 5 分钟，1–60 分钟可配）轮询；后台规则也可在电视应用主页手动同步。

答对的题会立即从电视本地抽题范围移除，并上报后台；后台核对答案后删除该题，答错则保留。断网时保存待上报记录，恢复连接后自动补报。题库清空后，后台通过 DeepSeek 自动补入 50 道中等难度综合题，覆盖古诗词、数学、科学、地理历史；需要配置 `DEEPSEEK_API_KEY`。该功能需要同时更新后台和电视 APK（0.7.0 / versionCode 8），旧版 APK 不会上报答题结果。

## Android 应用

在 Android Studio 中打开项目目录，等待 Gradle 同步。项目使用 Kotlin、Android Gradle Plugin 9.0.0、`compileSdk 37`、`minSdk 28`，Gradle Wrapper 固定为 9.1.0。若 SDK Platform 37 缺失，请在 SDK Manager 中安装。

Windows 命令行构建：

```powershell
.\gradlew.bat assembleDebug
```

APK 位于 `app\build\outputs\apk\debug\app-debug.apk`。应用自动从 `https://tvparental.mediainsight360.com` 同步题目和规则，无需在电视配置地址或登录；首页可手动同步并查看今日已用时间。首次使用需要在电视确认系统悬浮窗权限；应用不能静默授予 Android 特殊权限。

屏幕亮起时长按 30 秒采样，额度临近耗尽时会缩短采样间隔。后台服务在开机后恢复；TCL 固件还需要通过 ADB 放行 `APP_AUTO_START` 与 `APP_ASSOC_START`。完整安装升级步骤见 [docs/INSTALL_TCL.md](docs/INSTALL_TCL.md)。

## 本机后台

需要 Python 3.11 或更高版本，无第三方 Python 依赖。在项目根目录打开 PowerShell，首次预览时设置本地管理员（已有账号可跳过）：

```powershell
python backend/server.py --set-admin
```

然后启动本地预览服务，保持这个终端运行：

```powershell
$env:HOST = "127.0.0.1"
python backend/server.py
```

浏览器打开 `http://127.0.0.1:8765/questions`，使用上一步设置的用户名和密码登录（用户名默认 `admin`，密码至少 10 个字符）。后台分为“使用规则”和“题库管理”两个页面：添加题目时从下拉框选择类别，包含内置类别与题库已有的自定义类别；点击列表中的题目可打开详情，查看类别、难度、四个选项和正确答案。

本地账号以密码散列保存到 `backend/data/admin.json`，忘记密码时重新运行 `--set-admin`；本地题库与规则默认保存到 `backend/data.json`。本机预览使用本地数据；电视正式版本固定连接线上域名。修改 `backend/web/` 后刷新浏览器即可看到变化，无需重启 Python；按 `Ctrl+C` 停止预览。

题库支持 DeepSeek AI 出题：在“AI 出题”中输入一首古诗词的名称（可附作者）、数量和难度，一次生成 1–10 道四选一题目，预览并勾选后批量入库。AI 与手动出题的难度统一为“低、中、高”，默认“中”；已有题库的“小学、初中、高中”读取时分别对应“低、中、高”。需要在服务环境中配置 `DEEPSEEK_API_KEY`；Key 配置和使用步骤见[部署文档](docs/DEPLOY_ECS.md#deepseek-ai-古诗词出题)。

## 部署到阿里云 ECS

复用 ECS 上已有的 Caddy，为新域名增加 `reverse_proxy 127.0.0.1:8080`，Python 后台由 systemd 托管。按 [部署文档](docs/DEPLOY_ECS.md) 完成一次性初始化后，推送后台或种子题库到 `main`，GitHub Actions 就会测试、同步代码、重启后台并检查 HTTPS 接口。

管理页面为 https://tvparental.mediainsight360.com/ 。代码位于 `/var/www/html/tvparental`，管理员账号散列保存在 `backend/data/admin.json`，题库和规则保存在 `backend/data/state.json`；自动发布会保留这些运行数据，请另外备份。旧版令牌不再用于登录，升级后设置管理员账号的步骤见部署文档。应用端口 8080 只监听本机，无需在安全组开放。

已有宿主机 Caddy 时不要运行仓库的 `backend/compose.yaml`，其中自带的 Caddy 会争用 80/443 端口。

## 后端测试

```powershell
python -m unittest discover -s backend/tests -v
```

## 当前边界

Android 用屏幕交互状态估算电视使用时间，电视厂商的休眠和进程管理策略可能影响后台服务。悬浮框持有遥控器焦点，可以阻挡当前画面的遥控操作，但无法强制停止其他应用的视频解码或音频，也无法替代设备所有者级别的系统管控。
