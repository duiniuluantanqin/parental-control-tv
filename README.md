## Android 应用

在 Android Studio 中打开项目目录，等待 Gradle 同步。项目使用 Kotlin、Android Gradle Plugin 9.0.0、`compileSdk 37`、`minSdk 28`，Gradle Wrapper 固定为 9.1.0。若 SDK Platform 37 缺失，请在 SDK Manager 中安装。

Windows 新环境准备、JDK/SDK 定位和安装流程见 [README_BUILD.md](README_BUILD.md)。从仓库根目录使用项目构建脚本：

```powershell
.\build.bat
```

版本号统一保存在根目录 `VERSION`。APK 位于 `app\build\outputs\parental\Parental-TV-v<版本号>.apk`。应用自动从 `https://tvparental.mediainsight360.com` 同步题目和规则，无需在电视配置地址或登录；首页可手动同步并查看今日已用时间。首次使用需要在电视确认系统悬浮窗权限；应用不能静默授予 Android 特殊权限。

### 启动 Android 模拟器

在 Android Studio 打开 Device Manager，找到已创建的虚拟设备并点击启动按钮。也可以在 PowerShell 中列出并启动 AVD：

```powershell
$androidSdk = "$env:LOCALAPPDATA\Android\Sdk"
$emulator = Join-Path $androidSdk "emulator\emulator.exe"
& $emulator -list-avds
& $emulator -avd Television_1080p
& $emulator -avd "AVD_NAME"
```

如果列表为空，在 Device Manager 点击“Create Device”，选择设备型号和系统镜像，完成创建后再启动。

如果 SDK 安装在其他目录，请将 `$androidSdk` 改为 Android Studio SDK Manager 显示的路径。将 `AVD_NAME` 替换为列表中的设备名称。

等待模拟器启动后安装当前调试版本：

```powershell
.\install_emulator.bat
```

模拟器安装脚本会从 Android SDK 环境变量、`local.properties`、常见 SDK 目录或 PATH 定位 ADB，并会先卸载旧包，因此会清除模拟器中的本地应用数据。电视安装请使用 `install_device.bat`。

屏幕亮起时长按 30 秒采样，额度临近耗尽时会缩短采样间隔。后台服务在开机后恢复；TCL 固件还需要通过 ADB 放行 `APP_AUTO_START` 与 `APP_ASSOC_START`。完整安装升级步骤见 [docs/INSTALL_TCL.md](docs/INSTALL_TCL.md)。

## 本机后台

需要 Python 3.11 或更高版本，无第三方 Python 依赖。在项目根目录打开 PowerShell，首次启动会在 SQLite 中创建默认管理员 `admin/admin`。

然后启动本地预览服务，保持这个终端运行：

```powershell
$env:HOST = "127.0.0.1"
python backend/server.py
```

浏览器打开 `http://127.0.0.1:8765/questions`，使用 `admin/admin` 登录，然后通过右上角“修改密码”更换默认密码（新密码至少 10 个字符）。后台分为“使用规则”和“题库管理”两个页面：添加题目时从下拉框选择类别，包含内置类别与题库已有的自定义类别；点击列表中的题目可打开详情，查看类别、难度、四个选项和正确答案。

如果 8765 端口已被占用，可在启动服务前改用其他端口，例如：

```powershell
$env:HOST = "127.0.0.1"
$env:PORT = "8766"
python backend/server.py
```

此时打开 `http://127.0.0.1:8766/settings`。停止服务时在运行它的 PowerShell 窗口按 `Ctrl+C`。

管理员用户名和加盐密码散列、题库、规则、设备及使用记录均保存在 `backend/data.sqlite3`。忘记密码时可运行 `python backend/server.py --set-admin` 重设。首次启动会用默认规则、种子题库和 `admin/admin` 初始化 SQLite，不会自动导入旧 JSON。启动本地后台只会让浏览器管理页面使用本地数据；app 当前仍固定连接线上域名 `https://tvparental.mediainsight360.com`，不会因为启动本地后台而自动切换。修改 `backend/web/` 后刷新浏览器即可看到变化，无需重启 Python。

题库支持 DeepSeek AI 出题：古诗词类别输入作品名（可附作者），数学、地理历史和科学类别可直接随机出题。每次生成 1–10 道四选一题目，预览并勾选后批量入库。AI 与手动出题的难度统一为“低、中、高”，默认“中”；已有题库的“小学、初中、高中”读取时分别对应“低、中、高”。需要在服务环境中配置 `DEEPSEEK_API_KEY`；Key 配置和使用步骤见[部署文档](docs/DEPLOY_ECS.md#deepseek-ai-出题)。

## 部署到阿里云 ECS

复用 ECS 上已有的 Caddy，为新域名增加 `reverse_proxy 127.0.0.1:8080`，Python 后台由 systemd 托管。按 [部署文档](docs/DEPLOY_ECS.md) 完成一次性初始化后，推送后台或种子题库到 `main`，GitHub Actions 就会测试、同步代码、重启后台并检查 HTTPS 接口。

管理页面为 https://tvparental.mediainsight360.com/ 。代码位于 `/var/www/html/tvparental`，管理员账号散列、题库、规则、设备和记录保存在 `backend/data/state.sqlite3`；自动发布会保留这些运行数据，请另外备份。新数据库默认账号为 `admin/admin`，首次登录后应在页面右上角修改密码。应用端口 8080 只监听本机，无需在安全组开放。

已有宿主机 Caddy 时不要运行仓库的 `backend/compose.yaml`，其中自带的 Caddy 会争用 80/443 端口。

## 后端测试

```powershell
python -m unittest discover -s backend/tests -v
```

## 当前边界

Android 用屏幕交互状态估算电视使用时间，电视厂商的休眠和进程管理策略可能影响后台服务。悬浮框持有遥控器焦点，可以阻挡当前画面的遥控操作，但无法强制停止其他应用的视频解码或音频，也无法替代设备所有者级别的系统管控。
