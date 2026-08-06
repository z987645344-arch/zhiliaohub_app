# 知了hub 管理助手（Android）

知了hub 管理助手是 `zhiliaohub` 管理后台的配套原生 Android App。当前版本聚焦单设备安全配对、持久会话、P-256 挑战应答登录和一个真实的网站健康状态卡片，不包含内容编辑、二维码扫描或尚无服务端接口的监控数据。

本仓库与主站仓库相互独立。App 只对接服务端已有接口，本轮没有修改 `zhiliaohub/` 或 `admin-server/`。

兼容性：依赖 `zhiliaohub admin-server v1.2+` 提供的设备认证接口。

## 构建要求

- Android Studio（本项目初始化时使用的本机版本为 AI-253 系列）
- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 35.0.0 或兼容版本
- Gradle Wrapper 8.14（仓库已包含 Wrapper）

在 Android Studio 中打开仓库根目录并等待 Gradle Sync 完成即可运行。命令行构建示例：

确保 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 指向本机 Android SDK 后执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

运行本地测试与 Lint：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

## 首次使用

1. 在设置页输入后台服务根地址，例如 `http://192.168.1.20:3001` 或正式 HTTPS 地址。不要附加 `/api/...` 路径。
2. 使用 HTTP 时必须在醒目警告下显式确认。这只适合可信局域网内的临时开发；正式部署必须改用 HTTPS。
3. 在网页后台先通过密码 + TOTP 登录，再到 `/admin/device` 生成一次性配对码。
4. 在 App 中手动输入 `XXXXX-XXXXX` 配对码并提交。
5. 后续启动会优先使用持久化 session Cookie。Cookie 失效时，App 才申请新挑战并弹出生物识别窗口。

Android 模拟器访问宿主机服务时通常使用 `10.0.2.2`；真机需要填写电脑在同一局域网中的地址，并确保后台监听地址及系统防火墙允许该连接。不要为测试把管理后台直接暴露到公网。

## HTTP 开发模式的边界

`network_security_config.xml` 是静态资源，Android 无法在其中声明运行时由用户输入的域名。因此本项目采用两层限制：

- Android 平台层允许开发期明文流量；
- App 只根据用户明确保存的根地址构造请求，OkHttp 拦截器校验 scheme、host、port 必须与该地址完全一致，并禁用 HTTP/HTTPS 重定向。

这可以把 App 自身的请求限制到用户指定的 origin，但不能让 HTTP 变得安全。HTTP 下 session Cookie 和响应内容仍可能被窃听或篡改。

## 认证与本地存储

- 设备签名密钥：Android Keystore 内的 ECDSA P-256 (`secp256r1`) 密钥；私钥不可导出，并要求每次签名使用强生物识别授权。
- 公钥：以 PEM 编码的 SPKI `PUBLIC KEY` 形式提交到配对接口。
- 挑战签名：对服务端原样返回的 `signedPayload` UTF-8 字节执行 `SHA256withECDSA`；Android `Signature` 输出 ASN.1 DER，再以 Base64 提交。
- session：只持久化名为 `zhiliaohub.admin.sid` 的 Cookie。Cookie 内容使用另一把 Android Keystore AES-GCM 密钥加密后保存，App 备份与设备迁移均已关闭。
- 非敏感设置：服务器地址和“已配对”标记保存在 Preferences DataStore；该标记不能替代 Keystore 密钥或服务端认证。
- 日志：代码不记录私钥、签名原文、Cookie 或配对码。

更换服务器地址会清除旧 session、删除原设备签名密钥并重置本地配对状态。设置页也提供“仅清除会话 Cookie”和“清除本机配对并删除设备密钥”操作。

## 已对接接口

| 接口 | 用途 |
|---|---|
| `POST /api/device-auth/pair` | 提交一次性配对码、设备名和 P-256 SPKI PEM 公钥 |
| `POST /api/device-auth/challenge` | 获取唯一应签名的 `signedPayload` |
| `POST /api/device-auth/login` | 提交 DER/Base64 ECDSA 签名并取得 session Cookie |
| `GET /api/admin/device` | 使用现有 Cookie 探测管理员设备会话是否仍有效 |
| `GET /health` | 显示真实的“在线/离线”和最近检查时间 |

服务端将“配对码错误、过期或已使用”统一返回为同一个 `401`，App 无法可靠区分这三种服务端原因，因此会如实显示组合提示；本地格式错误和服务器不可达则分别提示。

设备被服务端吊销时，既有管理员设备会话返回 `401`，新的 challenge 返回 `409`。App 据此显示“当前设备已被吊销，请在网页后台重新生成配对码”，清理旧 Cookie 和本机签名密钥，并引导回配对页。

## 项目结构

```text
app/src/main/
├── java/com/zhiliaohub/app/
│   ├── data/       # DataStore 设置与配对清理
│   ├── network/    # URL/origin 校验、OkHttp 与设备认证 API
│   ├── security/   # P-256 Keystore 和 AES-GCM CookieJar
│   └── ui/         # 设置、配对、登录/健康状态界面
└── res/
    ├── layout/     # 原生 View XML
    └── xml/        # 网络安全与数据导出规则
```

## 主要依赖

| 依赖 | 版本 | 用途 |
|---|---:|---|
| AndroidX AppCompat | 1.7.1 | 原生 View Activity 与兼容主题 |
| AndroidX Activity KTX | 1.13.0 | Activity result API |
| AndroidX Lifecycle Runtime KTX | 2.11.0 | 生命周期协程 |
| AndroidX Biometric | 1.1.0 | `BiometricPrompt` 与 CryptoObject |
| AndroidX DataStore Preferences | 1.2.1 | 非敏感配置持久化 |
| OkHttp | 5.3.0 | HTTP、CookieJar 与严格 origin 网络层 |

构建工具固定为 AGP 8.13.2、Kotlin 2.3.20、Gradle 8.14，以匹配本轮实际安装并验证过的 API 36/JDK 17 环境。

## 当前验证状态

2026-08-06 已实际完成 Debug APK 编译、7 个 JVM 单元测试和 Android Lint；在 Vivo V2405A（Android 15 / API 35）上完成 8 项真实端到端验证，并验证从 `0.1.0` 覆盖安装到 `0.1.1` 后配对状态、Keystore 私钥和 session Cookie 均保留。生产 HTTPS、网络异常和生物识别锁定等边界仍待验证，详见 [STATUS.md](STATUS.md)。

仓库使用 [GitHub Actions](https://github.com/z987645344-arch/zhiliaohub_app/actions) 在 push 或 pull request 到 `main` 时执行 Debug 编译、JVM 单元测试和 Android Lint；CI 不运行模拟器或替代人工真机验证。
