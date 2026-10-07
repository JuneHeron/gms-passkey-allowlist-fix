# GMS Passkey Allowlist Fix

让「改包版」Telegram 客户端（本次实测：**NagramX**）也能正常使用
**Google 密码管理器的通行密钥** 的 LSPosed 模块。

---

> ## ⚠️ 作者说明（请先读）
>
> - 作者**没有任何安卓开发经验**，本项目 **100% 由 AI 编写（vibe coding）**，作者只负责提需求、测试和反馈。
> - **仅在以下两台设备上实测通过**：
>   - 小米 17（25113PN0EC）/ 澎湃 OS 4.0.0.32.XPCCNXM（Android 17，SDK 37）/ Google Play 服务 26.36.35
>   - 小米 14（23127PN0CC）/ 澎湃 OS 3.0.302.0.WNCCNXM（Android 16，SDK 36）/ Google Play 服务 26.34.36
>
>   两者均使用 LSPosed IT v2.2.0（libxposed API 102）。其它机型、ROM、GMS 版本请自行测试与调整，
>   作者无法提供技术支持。
> - 作者做这个**纯粹是为了修自己设备上的问题**：**只有在自己设备上失效时才会继续更新**。
>   发布出来只是顺手分享，不承诺维护，也不保证处理 issue / PR（欢迎讨论，但不一定回复）。
>
> **Author's note** — The author has no Android development experience; this project is 100%
> AI-written (vibe coding). It was tested on only two devices: Xiaomi 17 (25113PN0EC, HyperOS
> 4.0.0.32 / Android 17 / Google Play services 26.36.35) and Xiaomi 14 (23127PN0CC, HyperOS
> 3.0.302.0 / Android 16 / Google Play services 26.34.36), both with LSPosed IT v2.2.0
> (libxposed API 102). Use on other devices / ROMs / GMS versions at your own risk. This was built
> for personal use and is only updated when it breaks on the author's own device — publishing is a
> by-product, not a maintenance commitment.

---

> 症状：在这类 App 里点「创建通行密钥」，界面根本弹不出来，App 提示
> 「无通行密钥应用 / 通行密钥在此设备上不可用」。

**English**: An LSPosed module that lets repackaged Telegram clients (tested with **NagramX**)
create and use passkeys with **Google Password Manager**. Such clients must assert
`origin=https://telegram.org` to be accepted by Telegram's servers, but Google only lets
allowlisted browsers assert origins, so GMS throws
`IllegalStateException("Origin is not being returned as the calling app did not match the
privileged allowlist")` before any UI can appear. This module hooks that check inside the
Google Play services process and returns the stored origin instead.

Requirements: LSPosed (libxposed API 101+), Android 14+, credential service set to Google.
Install: install the APK → enable the module in LSPosed with scope **Google Play services
(`com.google.android.gms`)** → reboot or force-stop Play services. Verify with
`adb logcat | grep PasskeyFix` (expect `bypass installed on ...`).
Build: `bash build.sh` (JDK 17+; toolchain is downloaded automatically).

---

## 问题原因

改包版 Telegram 为了能被 Telegram 服务器接受，会在调用凭据接口时**断言**自己代表
`https://telegram.org`（因为改包版拿不到 telegram.org 的数字资产链接授权，只能冒充浏览器）。

而 Google 对「冒充浏览器」管得很严：只有它白名单内批准过的浏览器才允许这么做。改包版的
包名+签名不在名单里，于是 GMS 在弹出任何界面之前就直接抛异常：

```
[FetchAllowlistedOriginOperation] rejecting asserted origin from app '<pkg>'
  ... because it is not in the list of trusted browsers
Caused by: java.lang.IllegalStateException: Origin is not being returned as the calling app
  did not match the privileged allowlist
```

**这是 Google 的设计（privileged apps），不是系统 / ROM 的问题** —— 换任何手机、任何 ROM
结果都一样。本模块在 GMS 进程内把这层校验绕过去。

## 适用前提

- **LSPosed**（modern / libxposed API 101+；本模块以 `META-INF/xposed/*` 声明）
- 系统里「凭据服务 / 密码服务」必须指向 **Google**（`com.google.android.gms/...PasswordAndPasskeyService`），
  而不是厂商自带密码管理器
- 按机制，任何会被该校验拒绝的调用方都应受益（不限于 Telegram）；但本次**只在 NagramX 上实测**，
  其它客户端未测试

> 注意：模块只解决「GMS 白名单」这一层。App 或服务器端的其它要求（账号状态等）不受影响。

## 安装

1. 安装 `gms-allowlist-fix.apk`
2. 在 LSPosed 中启用模块，作用域勾选 **Google Play 服务 (com.google.android.gms)**
3. 重启手机；或强制停止 Google Play 服务让它重新加载（`am force-stop com.google.android.gms`）
4. 验证：`adb logcat | grep PasskeyFix`
   - 加载时应看到 `bypass installed on ...`
   - 创建通行密钥时应看到 `bypassed ... -> origin=https://telegram.org`

## 工作原理

两层定位，互为兜底：

1. **按已知混淆名预装**：例如 GMS 26.36.35 上是类 `nft` 的方法 `b(String)`
   （其它 GMS 版本混淆名不同，例如 26.34.36 上是 `nel.b`）
2. **异常调用栈探针**：hook `IllegalStateException(String)` 构造器，凡是消息含
   `privileged allowlist` 的异常，就用它自身的调用栈逐帧定位做校验的方法
   （逐帧尝试 `Class.forName(name, false, appClassLoader)`，**跳过加载不出来的帧** ——
   否则会选到框架/模块自身的混淆类）。预装失败时由它兜底，因此 GMS 更新后即使混淆名改变
   也会自动找到

命中后把「抛异常」改成「返回该校验对象里保存的 origin（以 http 开头的 String 字段）」，
校验随即通过。

## 已知限制

- 仅在 **NagramX** 上实测了「创建」与「登录」；其它改包版客户端（Forkgram / Nekogram 等）**未测试**
- Google Play 服务若改变整套机制（不只是改混淆名），需要更新本模块
- 需自行评估风险：本模块在 **Google Play 服务进程内** 做方法 hook（作用域仅该进程）

## 构建

```bash
bash build.sh        # 需要 JDK 17+
```

build-tools、android.jar、libxposed API 会自动下载到 `tools/`；产物为 `gms-allowlist-fix.apk`。

## 免责声明

仅供学习研究与个人使用。使用第三方修改过的客户端本身存在风险；本模块可能随 Google Play
服务更新而失效。请自行评估并承担风险。

另见开头的**作者说明**：作者无安卓开发经验、项目 100% 由 AI 编写、仅在上述两台设备上测试过、
仅在自己设备失效时更新。

## 致谢

白名单校验的位置与绕过思路，最早由 [Howard20181/HyperPasskey](https://github.com/Howard20181/HyperPasskey)
的 issue #27 / PR #28 公开分析（GMS 26.32.34，HyperOS/Android 17）。本模块为独立实现，
并额外加入了不依赖类名的动态定位与两层兜底。

## License

GPL-3.0
