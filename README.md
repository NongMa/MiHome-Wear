# MiHome-Wear

米家设备的 **Wear OS 手表控制端**。灵感与协议实现来自 [MiHome-Windows](https://github.com/huanyuejue/MiHome-Windows)（Python + PySide6 桌面版），本项目把它的核心层用 Kotlin 重写了一遍，界面按手表形态重新设计。

> 原项目是 Python 桌面程序，**无法直接移植**到安卓手表（手表上没有 CPython 运行时，Qt 桌面组件也不适用）。
> 因此这里的做法是：**照搬协议语义、重写实现语言与 UI**——`mijiaAPI` 的登录/签名/控制流程逐条对照移植，界面层全部用 Compose for Wear OS 重做。

## 功能

| 功能 | 说明 |
|------|------|
| 扫码登录 | 手表屏幕渲染二维码，米家 APP 扫码完成登录；凭据落盘，之后自动刷新 token |
| 设备列表 | 按家庭 / 房间分组排序，显示在线状态与开关状态 |
| 设备控制 | 按 miot-spec 自动生成控件：布尔→开关、数值→滑块、枚举→点按切换、只读→灰显 |
| 动作执行 | 设备 spec 里的动作渲染为按钮（如"开窗""重置滤芯"） |
| 场景执行 | 列出米家 APP「智能 → 手动控制」的手动场景，一键执行 |
| 小爱指令 | 选一台在线小爱音箱，语音输入或点常用指令，走 `execute-text-directive` 下发 |

## 界面结构

```
启动 ─ 检查登录态
      ├─ 无凭据 → 扫码登录页（二维码 + 长轮询）
      └─ 有凭据 → 主菜单
                   ├─ 设备 ── 设备详情（开关 / 滑块 / 枚举 / 动作）
                   ├─ 场景 ── 场景列表（点按执行）
                   └─ 小爱同学 ── 语音输入 / 常用指令
```

左滑返回上一级（`SwipeDismissableNavHost`），列表用 `ScalingLazyColumn` 适配圆屏缩放。

## 构建

### 环境要求

| 工具 | 版本 |
|------|------|
| JDK | 17+ |
| Android SDK | compileSdk 35（platforms;android-35 + build-tools 35） |
| Gradle | 8.13（首次构建自动下载） |

### 构建 APK

```powershell
cd MiHome-Wear
.\gradlew.bat assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk
```

### 安装到手表

手表需开启 **开发者选项**（设置 → 关于手表 → 连点版本号）与 **ADB 调试 / 通过 Wi-Fi 调试**，然后：

```powershell
adb connect <手表IP>:5555
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

或用 Android Studio 直接打开工程运行到手表/模拟器。

> **路径建议**：Gradle + Kotlin 对中文路径一般没问题，但如果构建报奇怪的编码错误，把工程挪到纯 ASCII 路径再试（原版 Windows 项目的 Nuitka 就明确要求纯英文路径）。

## 项目结构

```
app/src/main/java/com/mijia/wear/
├── crypto/MiCrypto.kt        # 签名与 RC4 加解密（对照 mijia-api miutils.py）
├── data/AuthStore.kt         # 凭据持久化（对应上游 auth.json）
├── net/
│   ├── XiaomiAuth.kt         # 扫码登录 + token 刷新（对应 apis.py QRlogin）
│   └── MiCloudClient.kt      # 签名 POST（对应 mijiaAPI._request）
├── repo/
│   ├── SpecRepository.kt     # miot-spec 规格拉取与缓存（对应 devices.get_device_info）
│   └── MiRepository.kt       # 家庭/设备/场景/属性/动作（对应 core/service.py）
├── model/Models.kt           # 稳定的数据模型，隔离云端原始结构
├── vm/AppViewModel.kt        # 状态中枢
└── ui/                       # Compose for Wear OS 界面
    ├── App.kt                # 外壳 + 路由 + Toast
    ├── Qr.kt                 # 二维码渲染
    └── screens/              # 登录 / 主菜单 / 设备 / 详情 / 场景 / 小爱
```

## 协议移植对照表

| Windows 版（Python） | 本项目（Kotlin） |
|------|------|
| `mijiaAPI.miutils.gen_nonce/get_signed_nonce/gen_enc_signature/encrypt_rc4` | `crypto/MiCrypto.kt` |
| `apis.mijiaAPI._request`（签名 POST + 响应 RC4 解密） | `net/MiCloudClient.kt` |
| `apis.mijiaAPI.QRlogin / _get_qr_login_data / _complete_qr_login` | `net/XiaomiAuth.kt` |
| `devices.get_device_info`（miot-spec 拉取 + 缓存） | `repo/SpecRepository.kt` |
| `core/service.list_devices / power_states / _find_on_method / _switch_target` | `repo/MiRepository.kt` |
| `core/service.run_action`（`_in` 通道下发小爱文本指令） | `MiRepository.sendTextDirective` |

移植时保留了原实现里几处关键的经验性处理：

- **虚拟子设备（多键面板）**：did 形如 `<父did>.s3`，云端只认父 did + 对应 siid，请求一律发给父设备；
- **开关通道选择**：spec 里有 `on-2/on-3/on-4` 多路时按 did 后缀选通道，否则会控制错灯；
- **写接口 code 语义**：`code ∈ {0, 1}` 都算成功（1 = 网关已接收指令）；
- **无 spec_type 的设备**（手表、第三方蓝牙产品）：云端不下发规格，按"无控制面板"处理，不轮询。

## 已知限制（v0.1）

- **手表屏幕小，二维码约 160dp**：扫码时请把手机凑近、避免反光；超时点"刷新二维码"。
- 枚举属性用"点按切换"而非下拉框，选项多时需要多点几下。
- 动作按钮**没有二次确认**（桌面版有），点按即执行。
- 仅支持云端控制，未做局域网直连（局域网控制需另外实现 miio 协议）。
- 未做图标缓存、耗材、定时任务、订阅消息推送等桌面版的进阶功能。
- 小爱语音输入依赖手表系统内置识别器（Wear OS 的 Google 语音输入），部分国行手表可能只支持文字指令。

## 隐私与安全

- 登录凭据保存在应用私有目录 `filesDir/auth.json`，卸载应用即清除；不上传、不包含在 APK 内。
- 仅与小米官方接口通信：`account.xiaomi.com`、`api.mijia.tech`、`miot-spec.org`。
- 建议只在自己的手表上安装使用；请遵守小米服务条款。

## 开源许可

[GPL-3.0-or-later](LICENSE)（与原项目 MiHome-Windows 及其上游 mijia-api 一致）。

协议实现参考并致谢：

- [MiHome-Windows](https://github.com/huanyuejue/MiHome-Windows) — Windows 桌面版与适配层经验
- [mijia-api](https://github.com/Do1e/mijia-api) — 米家 API 封装（GPL-3.0）
