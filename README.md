# CarUnlock

**长安 C211 平台车机 —— 第三方播放器无声修复**

装好即用，开机自动解除功放静音。AntennaPod、喜马拉雅、QQ 音乐等第三方播放器再也不用先开一次酷我才有声音。

---

## 你遇到的是这个问题吗？

如果你的车机符合以下现象，本工具就是为你准备的：

| 现象 | 说明 |
|---|---|
| 第三方播放器显示在播放，但**扬声器没声音** | AntennaPod / 喜马拉雅 / QQ 音乐等 |
| **车机自带的酷我、网易云有声音** | 高德导航也有声音 |
| **先打开一次自带音乐（酷我），再开第三方播放器就有声了** | 经典的"土办法" |
| 点击暂停再播放**没用** | |
| **每次开机都会复现** | |

如果你全中，那这**不是播放器的问题**，是车机的音频通道机制问题 —— 继续往下看。

---

## 适用范围

- **长安汽车 inCall / Coagent C211 平台车机**（全志 T7，Android 9）
  - 常见车型：二代逸动、逸动 PLUS（早期）、CS75 / CS55 / CS95、锐程系列、UNI 系列（早期）等
- 判断方法：车机 **设置 → 系统升级** 里的版本号以 `OS_C211-...` 或 `OS_CA...` 开头
- **不确定也没关系** —— 只要症状匹配（见上表）就能试，装了没效果卸载即可，不会影响车机

> 这不是长安独有。任何采用「音源仲裁 + 应用白名单」设计的车机都可能有类似问题。

---

## 为什么会有这个问题

车机的音频通路采用**音源仲裁**机制，由车机 MCU 统一管控「当前音源」。开机后音源状态是**未初始化**的：

```
mCurrentSource = null      音源为空
mCurrentMainSrc = -1       主音源未设置
```

此时音频数据虽然正常写进了声卡、功放也处于开启状态，但**主音源没被选中，通路没建立** → 无声。

车机自带的适配应用（酷我、网易云车机版）启动时会通过车机的音源服务**注册音源**，第三方通用播放器不会 —— 所以"先打开酷我"有用。

**CarUnlock 做的事，就是直接调用车机自己的音源仲裁接口：**

```
requestSource("app")
  → switchChannel From = null to = APP
  → AudioControlDsp: >>set source 1 , enable 1
  → dealWithAMPSwitch ... mCurrentMainSrc 1 → it set to amp unmute
```

---

## 安装

### 方式一：下载现成 APK

到 [Releases](https://github.com/skyletter/CarUnlock/releases) 下载最新的 `app-debug.apk`，用 U 盘或 adb 安装：

```sh
adb install -r app-debug.apk
```

> 车机若拦截安装，可把 APK 放到 U 盘，用车机自带文件管理器打开安装。

### 方式二：自己构建

本工程用 GitHub Actions 云构建，本地无需装 Android SDK：

1. Fork 本仓库
2. Actions 会自动运行（或手动触发 `workflow_dispatch`）
3. 在 Artifacts 里下载 `CarUnlock-debug`

---

## 使用

**装完就完事了。**

- 应用**没有界面**，点图标也不会弹出任何窗口
- 开机后自动在后台完成解锁，**不需要任何手动操作**
- 解锁完成后进程自动退出，不常驻、不弹通知、不申请多余权限

**自带开机自启，不依赖任何第三方自启工具**（应用内注册了 `BOOT_COMPLETED` 广播）。

---

## 验证是否生效

播放第三方播放器，然后在电脑上用 adb 看日志：

```sh
# 应用自身日志
adb shell "su 0 logcat -d -v time | grep CarUnlock"

# 车机音源链路日志
adb shell "su 0 logcat -d -v time | grep -E 'switchChannel|mCurrentMainSrc|set to amp'"
```

**期望看到：**

```
CarUnlock: 收到开机广播: android.intent.action.BOOT_COMPLETED
CarUnlock: 已启动解锁服务
CarUnlock: attempt 1: requestSource("app") -> 1
CarUnlock: 解锁完成(返回值 1)

MiscUtils---SourceConfig: switchChannel From = null to = APP
AudioControlDsp: >>set source 1 , enable 1
AudioControlDsp: dealWithAMPSwitch ... mCurrentMainSrc 1 → it set to amp unmute
```

---

## 不想装应用？直接跑命令也行

用 adb（**无需 root**）：

```sh
adb shell 'service call fce_misc_service_source_ctrl 1 s16 "app"'
```

其他可用音源：

| 参数 | 含义 |
|---|---|
| `app` | 第三方应用媒体源（目标） |
| `navi` | 导航 |
| `usb` | 本地 U 盘 |

---

## 技术细节

<details>
<summary>接口与实现（点开）</summary>

### 目标接口

| 项 | 值 |
|---|---|
| 服务名 | `fce_misc_service_source_ctrl` |
| AIDL | `com.fce.misc.proxy.source.ISourceCtrl` |
| 服务端实现 | `com.fce.misc.service.source.SourceCtrlImpl` |
| 所属 APK | `/system/priv-app/FCEMiscService/FCEMiscService.apk` |
| 方法 | `requestSource(String sourceId)`，事务码 **1** |

### 实现要点

- 用**反射 `ServiceManager` 取 IBinder 后直接 `transact`**，不需要引入车机 AIDL 文件
- `targetSdk 28` —— 避开 Android 9 的 hidden API 限制
- 接口**幂等**：音源已是 `app` 时返回 `false`，此时说明已解锁，直接视为完成
- 权限：实测 **root 与非 root 均可**，普通应用通过 Binder 调用即可，**不需要 root**
- 失败时自动回退到 root 方式执行 `service call`（若设备已 root）

### 开发注意

- `Theme.NoDisplay` 的 Activity **必须在 `onResume` 完成前 `finish()`**，否则系统抛 `IllegalStateException` 并强杀进程。所以解锁逻辑放在 `UnlockService`，Activity 只负责转发。
- 应用内用 `sRunning` 静态标志防止被多个入口重复拉起时跑两份任务。

</details>

---

## 排障

| 现象 | 处理 |
|---|---|
| 装了没效果 | 先按上面「验证」章节看日志。若 `requestSource -> 1` 但依然无声，说明不是同一个问题，欢迎开 issue 附日志 |
| 日志里 `服务未就绪` | 音频服务启动较慢，应用内置 6 次重试（首次等 8 秒） |
| 安装报签名冲突 | 先 `adb uninstall com.leo.carunlock` 再装 |
| 想改解锁的音源 | 修改 `UnlockService.java` 里的 `TARGET_SOURCE` 常量，重新构建 |

---

## 免责声明

本项目通过调用车机**自带的音频控制接口**实现功能，不修改系统分区、不刷机、不 root。

但车机系统版本众多，**请自行评估风险**。作者不对因使用本工具造成的任何车辆问题负责。

建议安装前备份重要数据。

---

## 相关背景

这个问题的社区记录可以追溯到 2015 年（老逸动车主装第三方播放器无声）。长安车机工具箱（CABOX）官方 FAQ 也记载了同样症状，给出的方案是"用自启动打开自带音乐" —— 治标不治本。

**CarUnlock 是第一个直接调用音源仲裁接口、从机制上解决该问题的方案。**

---

## License

MIT
