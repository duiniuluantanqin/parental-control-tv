# 编译和安装指南

## 快速开始

### 1. 编译项目

运行编译脚本：
```bash
build.bat
```

编译成功后，APK 文件位置：
```
D:\work\study\app\build\outputs\parental\Parental-TV.apk
```

### 2. 安装到设备

#### 安装到模拟器
```bash
install_emulator.bat
```

#### 安装到真机/TV设备
```bash
install_device.bat
```

---

## 详细说明

### build.bat - 编译脚本

**功能：**
- 清理旧的编译文件
- 编译 Debug APK
- 显示 APK 文件位置和大小

**使用前提：**
- 已安装 JDK
- 已设置 JAVA_HOME 环境变量，或在常见安装位置安装了 Android Studio

**如果编译失败：**
1. 在 Android Studio 中打开项目
2. 点击 **Build → Rebuild Project**
3. 查看错误信息并修复

---

### install_emulator.bat - 安装到模拟器

**功能：**
- 自动检测运行中的模拟器
- 卸载旧版本（如果存在）
- 安装新版本
- 授予悬浮窗权限
- 可选择启动应用

**使用前提：**
- Android 模拟器已启动
- ADB 已添加到系统 PATH

**启动模拟器：**
1. 打开 Android Studio
2. 点击右上角 AVD Manager
3. 点击 ▶ 启动模拟器

---

### install_device.bat - 安装到真机/TV设备

**功能：**
- 自动检测已连接的真机设备
- 卸载旧版本（如果存在）
- 安装新版本
- 授予悬浮窗权限
- 显示已安装版本号
- 可选择启动应用

**使用前提：**
- 设备已通过 USB 或网络连接
- 设备已开启 USB 调试
- 已授权此电脑进行调试

**连接 TCL TV：**
```bash
adb connect 10.0.0.44:5555
```

**TCL TV 特殊说明：**
- TCL 固件不支持 `adb install -r`
- 可能需要使用 TCL MultiScreen Interaction 应用安装
- 详见 `docs/INSTALL_TCL.md`

---

## 常见问题

### Q: 编译失败，提示找不到 JAVA_HOME

**A:** 两种解决方案：

1. **方案一：在 Android Studio 中编译**
   - 打开 Android Studio
   - File → Open → 选择项目
   - Build → Rebuild Project

2. **方案二：设置 JAVA_HOME**
   - 找到 JDK 安装路径（如 `C:\Program Files\Android\Android Studio\jbr`）
   - 设置系统环境变量 `JAVA_HOME`

### Q: 安装时提示找不到 adb

**A:** 添加 Android SDK 到系统 PATH：
1. 找到 SDK 路径（通常在 `C:\Users\[用户名]\AppData\Local\Android\Sdk`）
2. 添加 `platform-tools` 目录到 PATH
3. 重启命令行窗口

### Q: 模拟器安装脚本提示未找到模拟器

**A:** 
1. 打开 Android Studio
2. 点击右上角 AVD Manager
3. 点击 ▶ 启动一个模拟器
4. 等待模拟器完全启动后再运行安装脚本

### Q: TCL TV 安装失败

**A:** 
1. 确保电视和电脑在同一网络
2. 电视上开启 ADB 调试
3. 运行 `adb connect 10.0.0.44:5555`
4. 如果 `adb install` 失败，使用 TCL MultiScreen Interaction 应用

---

## 版本信息

当前版本：**0.8.0**

版本历史：
- 0.8.0: 重构UI，添加密码保护和卸载保护功能
- 0.7.0: 添加设备管理器和远程同步功能
- 0.6.0: 初始版本

---

## 文件说明

- `build.bat` - 编译脚本
- `install_emulator.bat` - 安装到模拟器脚本
- `install_device.bat` - 安装到真机/TV脚本
- `app/build/outputs/parental/Parental-TV.apk` - 生成的 APK 文件

---

## 技术支持

如有问题，请查看：
- `AGENTS.md` - 项目开发规则
- `docs/INSTALL_TCL.md` - TCL TV 安装说明
- `docs/DEPLOY_ECS.md` - 后端部署说明
