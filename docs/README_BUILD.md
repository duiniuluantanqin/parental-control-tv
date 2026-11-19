# Android 编译与安装

## 新环境准备

在新电脑克隆项目后，从 Android Studio 打开仓库根目录并完成 Gradle 同步。项目使用 Android Gradle Plugin 9.0.0、Gradle Wrapper 9.1.0、`compileSdk 37` 和 `minSdk 28`。安装 Android Studio 自带的 JDK（JBR）或 JDK 17+，并在 SDK Manager 中安装 Android SDK Platform 37 和 Android SDK Platform-Tools。

Gradle Wrapper 已随仓库提交，无需单独安装全局 Gradle。`.tools/gradle-9.1.0/` 只是可选的本机缓存；没有它时，构建脚本会使用 `gradlew.bat` 下载仓库指定版本。`local.properties` 保存本机 Android SDK 路径，已被 Git 忽略；在新电脑上由 Android Studio 生成，不要从其他电脑复制或提交旧路径。

## 编译 APK

在仓库根目录打开 PowerShell：

```powershell
.\scripts\build.bat
```

这是 Windows 的标准入口。脚本会从 `JAVA_HOME`、Android Studio 常见安装位置、JDK 环境变量或 PATH 定位 Java。Gradle 优先使用 `.tools/gradle-9.1.0/` 独立安装或 `GRADLE_USER_HOME` 中已解压的 9.1.0 分发包；若本机没有缓存，则使用 Gradle Wrapper 获取固定版本。项目本地 `.tools/gradle-home/` 存在时会复用其中的 Wrapper 缓存。脚本先执行 `clean`，再构建 Debug APK。

成功产物：

```text
app\build\outputs\parental\Parental-TV-v<version>.apk
```

如果 Android Studio 安装在自定义目录且脚本没有找到 JDK，可在当前 PowerShell 窗口临时指定其 JBR/JDK 路径后重试；不需要改系统级环境变量：

```powershell
$env:JAVA_HOME = 'C:\path\to\Android Studio\jbr'
Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe')
.\scripts\build.bat
```

Windows 统一使用 `scripts/build.bat`。Gradle 官方 Wrapper（`gradlew`、`gradlew.bat`）保留在仓库根目录。直接调用 `gradlew.bat` 仅用于有意绕过脚本时，并且需要先确保当前 shell 的 `JAVA_HOME` 有效。

macOS/Linux 可在仓库根目录运行 `./gradlew assembleDebug`，前提是已安装 JDK 17+，并通过 Android Studio 或 `ANDROID_HOME`/`ANDROID_SDK_ROOT` 配好 SDK 37。

## 安装到模拟器

先在 Android Studio Device Manager 创建 API 28 TV 虚拟设备，在仓库根目录用脚本启动：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start_emulator.ps1
```

脚本从 SDK 环境变量、`local.properties`、默认 SDK 目录或 PATH 定位模拟器。只有一个 AVD 时直接启动，多个时提示输入名称；加 `-List` 只列出设备，加 `-AvdName "AVD_NAME"` 可指定设备。等待模拟器启动完成后安装：

```powershell
.\scripts\install_emulator.bat
```

该脚本会从 Android SDK 环境变量、项目 `local.properties`、常见 SDK 目录或 PATH 定位 ADB，并会先卸载模拟器上的旧包再安装，因此会清除该模拟器中本应用的本地数据。构建产物不存在时先运行 `scripts/build.bat`。

## 安装到雷鸟/TCL 电视

运行：

```powershell
.\scripts\install_device.bat
```

电视安装入口是 `scripts/install_device.bat`。它需要 Python 3、电视开启 ADB 调试并可从本机访问。安装器会从 `ANDROID_SDK_ROOT`、`ANDROID_HOME`、项目 `local.properties` 或 PATH 查找 ADB，并通过电视自带的 TCL“多屏互动”发起安装；电视上需要用遥控器确认。此固件拒绝 `adb install -r`，不要用普通 ADB 覆盖安装。设备和升级验证步骤见 [TCL 安装指南](INSTALL_TCL.md)。

默认电视地址为 `10.0.0.8:5555`。换电脑但仍安装到同一台电视时，确认电视和电脑在同一网络，并先用 `adb devices` 或运行安装脚本确认连接状态。如果电视 IP 变化，可运行 `.\scripts\install_device.bat 10.0.0.9`，传入不带端口的 IP。命令行参数优先于环境变量 `TV_PARENTAL_TV_IP`；均未设置时使用默认 IP `10.0.0.8`。

## 故障定位

- 提示找不到 JDK：确认 Android Studio/JDK 已安装；自定义目录时在当前 shell 设置 `JAVA_HOME`，然后重跑 `scripts/build.bat`。不要因终端里没有 `java` 命令就判断项目无法构建，脚本会直接检查 Android Studio 的 JBR。
- 提示 SDK Platform 37 缺失：在 Android Studio 的 SDK Manager 安装 Android SDK Platform 37，然后重新打开项目以生成本机 `local.properties`。
- Gradle 首次构建停在下载：确认网络能访问 Gradle Wrapper distribution URL。Wrapper 文件已提交；全局安装 Gradle 不是解决方案。
- 找不到 ADB：安装 Android SDK Platform-Tools。两个安装脚本会查找 SDK 环境变量、`local.properties`、常见 SDK 目录和 PATH。
- 电视拒绝安装：按 [TCL 安装指南](INSTALL_TCL.md) 使用 TCL MultiScreen Interaction，并在电视端确认。

新会话开始 Android 工作时，先读仓库根目录的 [AGENTS.md](../AGENTS.md) 和 [.codex/skills/tv-parental-control/SKILL.md](../.codex/skills/tv-parental-control/SKILL.md)，再从项目根目录使用上述入口。具体 JDK、SDK 和缓存位置属于本机状态，应该从当前机器的脚本、环境变量和 Android Studio 设置中重新发现，不依赖聊天历史。
