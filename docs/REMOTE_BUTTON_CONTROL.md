# 最近观看按键开关

Parental TV → `Advanced settings` → 输入家长 PIN → `Block Recent Watch button`。

- 开关默认开启，设置保存在电视本地，不随远端规则同步覆盖。用户手动关闭后，覆盖升级与重启均保留关闭选择。首次使用仍需授权无障碍服务；缺少授权时界面明确提示尚未生效。
- 首次使用需授权 `Parental TV remote button control` 无障碍服务。高级设置提供 `Accessibility access` 按钮；返回后会显示授权状态。
- 开启时，服务请求按键过滤，只吞掉确认过的 TCL 最近观看扫描码 `613` 或 `KEYCODE_TCL_RECENT_WATCH`。主页、方向、确认、返回、音量和 Android 最近任务键均不主动拦截。
- 某些固件在无障碍过滤前打开厂商页面，因此同时监听 `com.tcl.cyberui.recents.PluginMainActivity` 的窗口状态事件并发送返回。此方式可能短暂显示最近观看页面，不能承诺完全阻止页面加载。
- 关闭开关立即停止页面拦截并撤销本服务的按键过滤请求，无需停用原桌面或卸载应用。
- 服务不获取窗口内容，只接收原桌面的窗口状态和系统按键事件。其他无障碍服务的设置保持不变。

检查服务状态：

```powershell
adb shell dumpsys activity service com.example.tvparental/.RecentWatchGuardService
```

`enabled` 为开关值，`blockedPresses` 是成功吞掉的按键次数，`closedPages` 是返回操作被系统接受的次数（是否真正关闭仍需检查前台窗口）。`lastKeyCode` 和 `lastScanCode` 仅保留最后一个按键码，不保存输入序列。

若应用被真正强行停止导致系统取消无障碍授权，需要从 `Accessibility access` 重新开启本服务。普通进程死亡后的系统重绑与开关持久化不能替代该授权。

## 真机验证（2026-11-07）

在 `tcl_m7642` / Android 9 / `10.0.0.44` 安装 `1.0.15`。用户通过家长 PIN 进入高级设置，开启开关，用实体遥控器按最近观看，并确认生效。服务诊断输出：

```text
Recent Watch guard: enabled=true
blockedPresses=18 closedPages=1 lastKeyCode=4 lastScanCode=158
```

`blockedPresses=18` 证明实际按键已经到达无障碍过滤器并被吞掉，`closedPages=1` 表示至少有一次使用了返回后备。不能据此承诺所有固件或每次都完全不出现页面。原有 Aerial 与小T助手授权均已保留。

用户随后要求默认开启，`versionCode=40` 将未设置过此选项时的默认值改为 `true`，保留显式关闭选择。Android 构建和差异检查通过。

`versionCode=40` 已覆盖安装；升级后系统重新绑定服务，诊断显示 `enabled=true`、`blockedPresses=3 closedPages=1`，用时统计仍为前台服务，Aerial 与小T助手服务仍在绑定列表。实体方向键事件也已到达服务并透传。关闭开关后的实体按键恢复、音量键、完整答题流程不在本次已完成的真机验收范围内。
