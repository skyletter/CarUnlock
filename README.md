# CarUnlock — 车机音源解锁

解决**长安 C211 平台车机**上第三方播放器（AntennaPod / 喜马拉雅等）**启动播放无声**的问题。

---

## 原理

车机的音频通路采用**音源仲裁**模型。开机后音源状态未初始化：

```
mCurrentSource = null      音源为空
mCurrentMainSrc = -1       主音源未设置
```

此时音频数据虽然正常写入了声卡、功放也处于 unmute，但**主音源没选中，通路没建立** → 无声。

车机预置的适配应用（酷我、网易云车机版）会通过音源仲裁服务注册音源，第三方通用 App 不会 —— 这就是"先打开酷我才有声音"的原因。

**本应用做的事，就是调用车机自己的音源仲裁接口，请求 APP 音源：**

```
requestSource("app")
  → switchChannel From = null to = APP
  → AudioControlDsp: >>set source 1 , enable 1
  → dealWithAMPSwitch ... mCurrentMainSrc 1 → amp unmute
```

---

## 技术要求

| 项 | 值 |
|---|---|
| 目标服务 | `fce_misc_service_source_ctrl` |
| AIDL | `com.fce.misc.proxy.source.ISourceCtrl` |
| 方法 | `requestSource(String sourceId)`，事务码 **1** |
| 有效音源标识 | `app`（目标）/ `navi` / `usb` |
| 调用权限 | 实测 root 与非 root shell 均可，**无需 root** |

调用方式：**反射 `ServiceManager` 取 IBinder 后直接 `transact`**，不需要引入车机 AIDL 文件。

若 Binder 路线被权限拦截，应用会自动回退到 root 方式执行 `service call`。

---

## 构建

本机无需安装 Android SDK，用 GitHub Actions 云构建：

1. 把 `carunlock/` 目录推到一个 GitHub 仓库
2. Actions 会自动运行（或手动触发 `workflow_dispatch`）
3. 构建完成后在 Artifacts 里下载 `CarUnlock-debug`（内含 `app-debug.apk`）

---

## 安装与配置

### 1. 安装 APK

```sh
ADB=D:/Green/platform-tools/adb.exe
$ADB install -r app-debug.apk
```

> 若车机拦截安装，可先把 APK 放到 `/sdcard`，再用 `pm install` 装。

### 2. 加入开机自启列表

车机自带 **Auto Start（`com.autostart`，Auto Start 2.2）** 管理开机自启：

1. 在车机应用列表里找到 **Auto Start** 并打开
2. 把 **CarUnlock** 加入自启列表
3. 重启车机验证

### 3. 手动测试一次

装好后点一下 CarUnlock 图标即可触发解锁（应用是静默执行的，不显示界面，45 秒后自动关闭）。

---

## 验证

播放 AntennaPod，然后查看日志：

```sh
ADB=D:/Green/platform-tools/adb.exe

# 应用自身日志
$ADB shell "su 0 logcat -d -v time | grep CarUnlock"

# 车机音源链路日志
$ADB shell "su 0 logcat -d -v time | grep -E 'SwitchSource|switchChannel|mCurrentMainSrc|it set to amp'"
```

**期望看到：**

```
CarUnlock: attempt 1: requestSource("app") -> 1
CarUnlock: 解锁成功(音源已切换到 app)

MiscUtils---SwitchSource: requestSource  Source = APP mCurrentSource = null
MiscUtils---SourceConfig: switchChannel From = null to = APP
AudioControlDsp: >>set source 1 , enable 1
AudioControlDsp: dealWithAMPSwitch ... mCurrentMainSrc 1 → it set to amp unmute
```

---

## 也可以不用 APK：直接跑命令

如果只想手动解锁（或排查问题），一条命令即可，**无需 root**：

```sh
$ADB shell 'service call fce_misc_service_source_ctrl 1 s16 "app"'
```

脚本版见同目录 `../audio-unlock.sh`。

**为什么不直接把脚本设成开机自启？** 因为这台车机：

- 没有 Magisk（`/data/adb` 为空）
- 没有 init.d
- `/system`、`/vendor` 均为只读挂载

而车机的自启动管理器 **只认应用**。所以 APK 是唯一无需改动系统分区就能开机自启的载体。

---

## 排障

| 现象 | 排查 |
|---|---|
| `服务未就绪` 反复出现 | 车机音频服务启动较慢，应用已内置 6 次重试、每次间隔 5 秒 |
| `transact 失败: SecurityException` | 说明服务对普通应用做了权限检查，此时会走 root 兜底。请确认车机已 root |
| 日志显示返回 `0` | 可能是幂等（音源已是 app，属正常）也可能是失败。可看车机侧 `switchChannel` 日志确认 |
| 装完开机仍无声 | 检查 Auto Start 里是否已把 CarUnlock 勾选；或增大 `FIRST_DELAY_MS` |
| 想顺便解锁导航音源 | 把 `TARGET_SOURCE` 改成 `navi`，或多次调用不同音源 |

---

## 相关文件

| 文件 | 说明 |
|---|---|
| `../音源解锁方案.md` | 完整的接口分析、事务码映射、实测证据 |
| `../audio-unlock.sh` | shell 版解锁脚本 |
| `../车机音频无声-排障报告.md` | 完整排障过程 |
