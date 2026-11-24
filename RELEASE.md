# AnroidAgent v0.1.0（demo）

Android 上运行的 UI 代理：无障碍读界面 → 压成文本网格（TUI）→ 模型决策 → 注入动作。
带动作级策略闸门（红线分级、目标硬禁、命中即自杀）与相册去重特化。

这是**演示版本**，用于代码审阅与本地试用，未做真机验证（见「已知限制」）。

## 产物

| 文件 | 大小 | 说明 |
|---|---|---|
| `AnroidAgent-base-v0.1.0.apk` | 6.48 MB | 基础版：无障碍 + MediaProjection，**不含任何 shell 代码** |
| `AnroidAgent-pro-v0.1.0.apk` | 6.55 MB | 追加包：完整基础功能 + Shizuku shell 通道 |
| `AnroidAgent-v0.1.0-src.zip` | — | 源码快照（与 `main` 分支 HEAD 一致） |

APK 签名证书 SHA-256：

```
f9468cf5a5691843cdec8b529e0b8b62d835310db2db5b5e10fe42ea400d341d
```

APK SHA-256：

```
cdfcf9e1a9e21d04aacd194f74a57ebe602203f1c46e783f54fbf58f174edd2f  AnroidAgent-base-v0.1.0.apk
d1f5b38fe9e356220cfce3ea0af8dd6557ce511b413d41e9f091df9cd6745b0e  AnroidAgent-pro-v0.1.0.apk
```

## 安装

- **base**：装完在「设置 → 无障碍」里启用 `AnroidAgent`。截图走 MediaProjection，每次需授权。
- **pro**：额外需要 [Shizuku](https://shizuku.rikka.app/) 并授权。授权后可用静默截图、
  精确前台判定，以及高级 shell 操作。

两个包 applicationId 不同，**可以共存**；但**不要同时启用两个无障碍服务**，会双重注入。

## 从源码构建

需要 JDK 17+ 与 Android SDK（compileSdk 36）。

```bash
./gradlew :app:assembleBaseDebug :app:assembleProDebug      # 调试包
./gradlew :core:test :driver:test :agent:test :app:test :premium-shizuku:test   # 172 个测试
```

Release 包需要签名。密钥**不入库**，通过参数传入：

```bash
./gradlew :app:assembleBaseRelease :app:assembleProRelease \
  -PdemoKeystore=/path/to/demo.keystore \
  -PdemoStorePass=****** \
  -PdemoKeyAlias=anroidagent-demo \
  -PdemoKeyPass=******
```

不传参数时产出 **unsigned** APK——不会静默用调试密钥冒充发布包。

自己生成一个演示密钥：

```bash
keytool -genkeypair -noprompt -keystore demo.keystore -alias anroidagent-demo \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass ****** -keypass ****** -dname "CN=Demo, O=Demo, C=CN"
```

## 发到 GitHub

### 网页上传的限制

- 单个文件 **≤ 25 MB**，一次拖拽 **≤ 100 个文件**
- 本仓库 tracked 文件 **103 个**，超过单批 100 的上限 → 网页要分两批，容易漏
- APK 都在 25 MB 内，但二进制不应该提交进 git（每次改动都会永久占仓库体积）

**结论：源码走命令行推送，APK 走 Release 附件。**

### 命令行（推荐）

```bash
# 1. 推源码
git remote add origin https://github.com/<user>/<repo>.git
git branch -M main
git push -u origin main

# 2. 打 tag
git tag -a v0.1.0 -m "AnroidAgent v0.1.0 demo"
git push origin v0.1.0

# 3. 传附件（需要 GitHub CLI：https://cli.github.com/）
gh release create v0.1.0 \
  dist/AnroidAgent-base-v0.1.0.apk \
  dist/AnroidAgent-pro-v0.1.0.apk \
  dist/AnroidAgent-v0.1.0-src.zip \
  --title "AnroidAgent v0.1.0 demo" \
  --notes-file RELEASE.md
```

没有 `gh` 时：浏览器打开
`https://github.com/<user>/<repo>/releases/new`，选 tag `v0.1.0`，
把 `dist/` 里三个文件拖进附件区（Release 附件单文件上限 2 GB，不受 25 MB 限制）。

`.gitignore` 已经排除 `dist/`、`.demo/`、`*.log`、`build/`，所以 `git add -A` 不会误传产物和密钥。

## 已知限制

- **未做真机验证**。MediaStore 查询、缩略图哈希、shell 移动校验都只在编译与 JVM 单测层面验证过。
- 演示签名证书，**不要用它分发正式版本**。
- 少数机型上 `loadThumbnail` 对部分媒体项会失败，这类项计入"无哈希"并跳过去重，不会误判为不重复。
- 清理相册要求 Shizuku：app 沙箱无法写公共目录，只有 shell 能做「复制 + 校验 + 删除」。

## 法律与使用边界

本项目定位为**开发者调试与安全研究工具**：不含任何第三方 App 的协议实现，
不绕过目标应用的授权机制，代码中硬禁微信 / QQ / 游戏 / 金融类目标
（命中即自杀进程，见 `core/policy/Targets.kt` 与 `EmergencyStop`）。

依赖全部为 Apache-2.0 / MIT 等宽松协议，**不含** GPL 的 LSPatch/LSPosed、
LGPL 的 LSPlant、无许可证的 Pine。

自行使用造成的后果由使用者承担。
