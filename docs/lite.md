# 掌心窗 Lite

掌心窗 Lite 只保留已经确认会使用的远程能力：连接检查、基础手机状态、设置闹钟和锁定屏幕。

## MCP 工具

- `linjian_status`：检查后端、手机心跳、自动重连状态，以及 MCP / 后端 / Android 版本。
- `get_phone_state`：读取屏幕亮灭、电量、充电、无障碍和后台服务状态；前台 App 上报默认关闭。
- `set_alarm`：单次或批量请求 Android 系统闹钟，并查询掌心窗自己的请求记录。
- `phone_screen_off`：通过 Android 无障碍服务直接锁屏，并在执行后核验屏幕确实熄灭。

所有控制工具都等待手机回传，明确区分 `queued`、`completed` 和 `confirmed`。超时时只返回未确认，不会把“命令已发送”写成“已经成功”。

Android 的公开闹钟 Intent 可以请求设置闹钟，也能按时间请求关闭一次性闹钟；但不能读取完整的系统闹钟列表，也没有最终结果回读。因此 Lite 版只列出掌心窗自己的请求记录。设置结果中的 `request_dispatched=true` 只表示系统闹钟 App 接住请求，不能冒充 `alarm_created=true`；关闭结果同理，`dismiss_requested=true` 不等于 `alarm_disabled=true`。同一时间有多个闹钟时，系统可能要求用户选择。

`/mcp-wallet` 在 Lite 版中不可用。旧模块的实现暂时保留在源码中用于回退，但不会注册到 MCP；同步后端和 Android 端也都有命令白名单，只接受闹钟与锁屏命令。

## Android 前端

启动页是单页设置面板，只提供：

- 服务器地址、Token 和设备 ID；
- 后台服务状态与开关；
- 无障碍和电池优化设置入口；
- 本机闹钟与锁屏测试；
- 最近日志。

无障碍配置不允许截图、手势或读取窗口内容，只保留执行系统锁屏所需的服务。前台 App 包名默认不上报。

## 从旧版升级

Lite 版继续使用原包名与公开版固定签名，版本码为 `30902`。从 `0.3.5.1` 覆盖安装时会保留原有服务器地址、Token 和设备 ID。安装后请确认：

1. 系统无障碍中的“掌心窗服务”仍为开启状态；
2. 掌心窗允许后台运行；
3. 单页中的后台服务已启动；
4. “测试闹钟”和“测试锁屏”均能正常执行。

## 构建

本地安装 Android SDK Platform 34 与 Build Tools 34.0.0 后运行：

```bash
bash android/build.sh
```

输出文件为 `android/PalmWindow-Lite-v0.3.9-lite.2.apk`。也可以运行仓库中的 `Build Android Debug APK` 工作流。

覆盖安装旧版所需的固定签名密码不再写进公开源码。请在仓库 Actions Secret 中配置 `PUBLIC_KS_PASSWORD`；未配置时工作流只生成用于编译检查的 `-unsigned.apk`，不能直接覆盖安装旧版。
