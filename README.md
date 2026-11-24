# AnroidAgent

Android 自动化 / 无障碍研究工具。**仅用于开发者调试、自动化测试与安全研究。**

> 本仓库**不包含**任何第三方 App 的协议实现、签名算法、设备指纹伪造逻辑。
> 所有目标适配必须由使用者自行实现，且目标必须是自有或有授权的 App。

---

## 一、模块结构

```
core/       模型、策略引擎（红线）、能力探测          纯逻辑，可 JVM 单测
driver/     设备控制抽象：A11yDriver + DriverRouter   基础版能力
agent/      感知（TUI 映射 / 压缩 / diff）、动作解码、提示词
protocol/   协议层接口定义（不含任何实现）
app/        应用外壳、无障碍服务、启动守卫
```

依赖方向严格单向：`app → agent/protocol/driver → core`。

**高级版特性全部落在 `core` 与 `agent`**，不依赖 Shizuku、不依赖 root、不依赖 hook 框架：

| 特性 | 位置 | 说明 |
|---|---|---|
| 动作级红线 | `core/policy/Policy.kt` | L0~L3 分级，比价场景零误杀 |
| 目标硬禁 | `core/policy/Targets.kt` | 包名 / 证书 / `CATEGORY_GAME` |
| 安全窗口 | `core/policy/Targets.kt` | `FLAG_SECURE` → 硬停 |
| 进程级自杀 | `core/policy/EmergencyStop.kt` | 含冷却期与残留恢复 |
| TUI 省 token | `agent/perceive/Perceive.kt` | GUI → 文本网格，成本降一个量级 |
| 单调升级 | `core/policy/PolicyDecider` | 模型只能加严，不能放行 |
| 相册去重 | `core/media/DedupPlanner.kt` | 本地哈希聚类，**零模型 token** |

## 二、构建（已在 macOS/Windows 本机验证通过）

```powershell
# 本机环境（已核对）：
#   JDK  21  C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot
#   SDK   %LOCALAPPDATA%\Android\Sdk  (platforms;android-36, build-tools;36.0.0)
#   GHome D:\.gradle
#   Gradle 9.5.0

gradle -p . :core:test :agent:test :app:test        # 25 个测试（app 跑两遍，按 flavor）
gradle -p . :app:assembleBaseDebug                  # 基础版  app/build/outputs/apk/base/debug/app-base-debug.apk (7.90 MB)
gradle -p . :app:assembleProDebug                   # 追加包  app/build/outputs/apk/pro/debug/app-pro-debug.apk  (7.98 MB)
```

| 组件 | 版本 | 说明 |
|---|---|---|
| Gradle | **9.5.0** | 与本机缓存 dist 一致（`gradle-wrapper.properties` 指向腾讯云镜像） |
| AGP | **9.3.0** | 与你已有的 `AndroidStudioProjects/xiqueer` 一致 |
| Kotlin | **2.4.10** | 由 AGP 9 **内置 Kotlin** 提供，**不单独应用 KGP** |
| compileSdk / targetSdk | 36 / 36 | 与本机 `platforms;android-36` 对齐 |
| minSdk | 30 | Android 11 |

> ⚠️ 关键约束：AGP 9 与 `org.jetbrains.kotlin.android`（KGP）**互斥**——
> 新 DSL 下 KGP 会因 `BaseExtension` 已被移除而崩溃。因此本项目走 AGP 内置
> Kotlin 路线：`plugins {}` 里**只声明 android 插件**，编译器选项在
> `kotlin { compilerOptions { jvmTarget.set(...) } }` 里配置。
> 同理，`kotlinx-serialization` 编译器插件也未引入，JSON 用 Android 内置 `org.json`。
>
> 不要把 `android.builtInKotlin=false` 或 `android.newDsl=false` 加回来——
> 那是绕过手段，AGP 10 会移除。

## 二·五、实测数据

`agent` 模块的 `PerceiveTest` 用合成电商列表页（搜索框 + 头像 + 3 tab +
12 个商品 + 4 个底部导航）对比两种表示：

| 表示 | token |
|---|---|
| 原始无障碍 XML（uiautomator dump 等价冗余度） | **4231** |
| TUI 压缩后 | **87** |
| **压缩比** | **48.6×** |

真实的商品/聊天页面文字更多，压缩比会低于这个数字（估计 8~20×），
但"文本化后能用纯文本模型"这个结构性收益不变。
测试断言 `>= 4×` 作为下限保护。

## 二·六、当前完成度

已落库（已编译，测试通过）：

| 模块 | 内容 |
|---|---|
| `core/policy` | L0~L3 分级、动作级拦截、单调升级、目标硬禁、安全窗口、自杀+冷却 |
| `core/device` | `DeviceInfoProvider` 唯一读取点 + `ConsistencyChecker` |
| `core/capability` | 能力位与探测接口 |
| `agent/perceive` | 压缩流水线、TUI 渲染、帧 diff、token 估算 |
| `agent/act` | 模型输出解码 + 索引有效性校验 |
| `agent/prompt` | 系统提示词（风险清单由 `PolicyVocab` 生成） |
| `agent/model` | **OpenAI 兼容客户端 + 单步 Planner（含修复重试、历史裁剪、多模态）** |
| `agent/task` | **任务状态机 + 串行队列（五条退出路径，可取消）** |
| `app/task` | **任务持久化（队列 / 心跳 / 最近结果，坏档降级为空）** |
| `agent/perceive` | **视觉兜底门槛 `VisionGate`（信息量不足才付截图的钱）** |
| `driver` | Driver 抽象、A11y 实现、路由降级、`GuardedDriver` |
| `driver/capture` | **MediaProjection 前台服务 + 取帧 + 全黑帧（secure）识别** |
| `protocol` | 仅接口（Adapter / Signer / 注册表 + dex 校验） |
| `app/overlay` | **悬浮控制面板（specialUse FGS）+ 配置持久化 + 位置钳制** |
| `premium-shizuku` | **追加包：shell 通道（screencap / inject / dumpsys secure）** |
| `app` | 外壳、无障碍服务、启动冷却守卫、模型配置与试跑界面 |

测试：**172 个，全绿**（core 80 + driver 12 + agent 23 + app 41 + premium 16；app 的 41 项在 base/pro 各跑一遍 → 213 次执行）。

尚未实现（按需推进）：

- Signer 的 dex 热更新加载（接口与 sha256 校验已就绪，加载器未实现）
- 中断任务的定时续跑（当前只记录不重放）

> Shizuku 能调用的完整权限与接口清单见 [docs/shizuku-capabilities.md](docs/shizuku-capabilities.md)
> （AOSP `com.android.shell` 的 531 条权限，按产品用途分组，含"有权限但做不到"的清单）。

## 二·七、已实现的两条闭环

### 1. 模型客户端（`agent/model`）

```
A11y 树 → Compressor → TuiRenderer → Planner.next()
                                          ↓
                              OpenAiCompatibleClient (OkHttp)
                                          ↓
                              ActionDecoder (org.json)
                                          ↓
                          PolicyDecider（单调升级，模型不能放行）
```

- `ModelConfig`：baseUrl / apiKey / model / temperature / maxTokens / `response_format`
- 重试：429、5xx、网络异常退避重试（最多 3 次）
- **解码失败自动修复一次**：把模型的错误输出回灌 + 要求"只输出 JSON"
- 历史裁剪到 `maxHistory`（默认 6 轮）——省 token 的另一半在这里
- 本地 `TokenEstimate` 与服务端 `usage` 同时记录，便于核对成本

约束解码的现实说明：多数 OpenAI 兼容服务只保证 `json_object` 返回**合法 JSON**，
不保证符合 schema。真正的不变式在本地 `ActionDecoder` + `PolicyDecider`，不依赖服务端。

### 2. MediaProjection 截图（`driver/capture`）

```
MainActivity → createScreenCaptureIntent → onActivityResult
             → ScreenCaptureService（前台，type=mediaProjection）
             → getMediaProjection → registerCallback → createVirtualDisplay
             → ImageReader → ScreenCapture.latestJpeg()
```

- **顺序不可调换**：`startForeground` 必须先于 `getMediaProjection`，
  `registerCallback` 必须先于 `createVirtualDisplay`（Android 14+ 强制，否则 SecurityException）
- `FLAG_SECURE` 页面 → 全黑帧 → `ScreenCapture.lastFrameUniformlyBlack = true`
  → 由 `SecureWindowWatch.viaBlackFrame` 判为 secure → 硬停
- 基础版没有静默截图能力，这是唯一合法的取帧路径（每次会话用户确认）

### 3. 悬浮窗（`app/overlay`）

```
OverlayService（前台，type=specialUse）
  ├ WindowManager.addView(TYPE_APPLICATION_OVERLAY)
  ├ 拖动标题栏移动 → 松手落盘（位置写进配置）
  ├ 点「读屏」→ AgentRunner.runOnce()
  └ 执行前把窗口整个 removeView（不是设 GONE）
```

两个关键设计：

**① 执行时必须移除窗口，而不是隐藏。**
只要 overlay 存在，目标 App 收到的 `MotionEvent` 就会带 `FLAG_WINDOW_IS_OBSCURED`，
强风控 App 会直接忽略这次触摸。这是"悬浮窗给人看"与"反检测给机器看"的**结构性冲突**，
只能用时序分离解决：待命时显示，执行时移除。开关是「隐藏执行」。

**② 位置必须钳制后再落盘。**
用户把窗口拖到屏幕外后如果原样保存，下次启动就再也拖不回来——这类工具最经典的差评来源。
`OverlayConfig.clamp()` 保证 x ∈ [0, screenW-width]、y ∈ [0, screenH-height]，
并有 6 个单测覆盖（含"窗口比屏幕还大"的退化情形）。

配置用 JSON 持久化（`OverlayConfig.toJson/fromJson`），并提供「导出配置」按钮——
换机、复现 bug、灰度对比都需要它。

### 4. 任务状态机（`agent/task`）

刻意**不用**自由 ReAct 循环：每一步的失败都要落到具体状态，否则用户看到的是
"跑了一会儿，不知道为啥停了"。

```
RUNNING ──模型给 DONE──────────→ DONE
        ──策略硬升级 / Ask────→ PAUSED（等人工）
        ──secure 窗口 / Kill──→ STOPPED（硬停）
        ──无动作 / 连续失败───→ FAILED
        ──步数耗尽───────────→ FAILED
```

- 队列串行（单设备并发必然互相破坏状态），支持取消与历史（最近 20 条）
- 执行历史压成「assistant(原始输出) / user(执行结果)」回灌，模型才知道自己做过什么，
  否则会原地打转
- `StepLog` 记录每步的 verdict / outcome / 是否用视觉 / 耗时，失败时保留原始输出便于复盘
- 6 个测试覆盖全部五条退出路径 + 视觉携带

### 5. 视觉兜底（`agent/perceive/VisionGate`）

TUI 在自绘界面上会失效（Flutter / Unity / 部分 WebView）。判定按"信息量不足"
而不是"识别出某个框架"——后者永远追不上壳的变种。

| 条件 | 含义 |
|---|---|
| 节点 < 5 | 基本是空树（自绘容器通常只有 1~3 个节点） |
| 可交互 < 3 | 只有背景容器 |
| 有文本 < 2 | 全是图标，TUI 只剩一堆 ◻ |

三个阈值取"与"，任一不满足就附截图。**刻意不看层深**：扁平的多按钮页面深度只有 1，
但信息量完全够用，按深度判会白白多付截图的钱。

代价是明确的：一张 1080p 图约 1500 token，而 TUI 只有 87（实测 48×）。
所以顺序永远是 **TUI 优先 → 不够才截图**，8 个测试锁住阈值行为。

## 二·八、真机验证流程

1. 装 `app-debug.apk`，打开无障碍（设置 → 无障碍 → AnroidAgent）
2. 填模型配置（baseUrl / model / apiKey）→ 保存（本地服务可留空 key）
3. 点「投屏授权」→ 系统弹窗确认（Android 14+ 每次会话都要确认一次）
4. 填目标包名 → 「加入白名单」——**白名单为空时任何目标都会被拒绝**
5. 点「5 秒后试跑」→ 立刻切到目标 App → 5 秒后自动读屏、生成 TUI、调模型、打印判定

或者用悬浮窗（更方便）：

5. 点「悬浮窗授权」→ 回 App 点「启动面板」
6. 切到目标 App，面板会浮在上面；拖动标题栏摆位置
7. 点面板上的「读屏」→ 面板自动消失、执行完再回来，标题栏显示结果
8. 「隐藏执行」开/关决定执行期间是否移除窗口（见上一节的设计冲突说明）

输出区会显示：TUI 原文与 token 数、模型原始输出、解析出的 step、risk、最终 verdict、服务端 usage。

## 二·九、追加包（`premium-shizuku`）

### 形态：同一份代码的两个 flavor

```kotlin
flavorDimensions += "edition"
productFlavors {
    create("base") { applicationId = "dev.anroidagent.app" }
    create("pro")  { applicationId = "dev.anroidagent.pro" }
}
dependencies { "proImplementation"(project(":premium-shizuku")) }
```

- **base**：无障碍 + MediaProjection。APK 里**不含任何 Shizuku 类**
- **pro**：base 的全部功能 + shell 通道。功能是"包含"关系，不是"替代"

所以"追加包保留完整基础功能"是**构造上成立**的，不靠人工同步。

### 隔离是代码级可验证的

两个 APK 分别在 `app/build/outputs/apk/{base,pro}/debug/`，可以直接扫 dex：

```powershell
# 期望：base = False/False，pro = True/True
$dexText.Contains("rikka/shizuku/Shizuku")
$dexText.Contains("ShizukuProvider")
```

实测：**base 两个标记均为 false，pro 均为 true**（体积差 80 KB）。
这比 README 里写"不含 shell"有力得多——它是可复现的二进制事实。

基础包通过反射按需加载实现，类不存在时安静降级：

```kotlin
private const val IMPL = "dev.anroidagent.premium.shizuku.ShizukuShellProvider"
val provider: ShellProvider? = runCatching { Class.forName(IMPL)... }.getOrNull()
```

### shell 通道的能力边界

| 能做 | 做不到 |
|---|---|
| `screencap` 静默截图（不弹授权） | 碰 `/dev/uinput` → **伪造不了 HID 设备**（"假蓝牙鼠标"需 root） |
| `input tap/swipe/keyevent`（免无障碍授权） | `input text` 只支持 ASCII（中文需 ADBKeyboard） |
| `dumpsys window` 精确判定 `FLAG_SECURE` | 读其它 App 私有目录 |
| `pm` / `am` / `settings` | 改设备指纹（`ANDROID_ID` 在 `/data/system`，shell 无写权限） |

> ⚠️ 实现取舍：Shizuku 13.x 把 `Shizuku.newProcess` 声明为 `private static`，
> 官方替代方案是 `bindUserService`（需要自己写 AIDL + app_process 入口）。
> 本项目用反射调用（签名已用 `javap` 核对）：少一个进程与一层 AIDL，
> 代价是依赖私有签名。Shizuku 大版本升级后若失效，`selfTest()` 会失败并在 UI 明确报错。

### 注入通道：默认关闭，需显式开启

这是产品上刻意设计的默认值，不是遗漏：

| 状态 | 行为 |
|---|---|
| **默认（未开启）** | 点击/滑动/输入**只走无障碍**。shell 完全不参与注入 |
| 显式开启后 | shell 优先；**任一动作失败自动回退无障碍**，不会整步失败 |
| Shizuku 未就绪 | 开关不可用；仍走无障碍 |

**授权（Shizuku 连接 + 权限）与注入（用 shell 点击）是两件事**：

- 授权解锁的是**只读能力**：`dumpsys` 精确判前台、`FLAG_SECURE` 检测、
  `screencap` 静默截图、高级操作面板
- 注入开关只决定**点击走哪条路**

所以关掉注入后，静默截图和前台判定仍然可用——它们不改设备状态。
这也符合那条原则：**改变设备行为的东西必须显式开启**。

每次动作实际走的通道由 `InputChannelProbe` 记录，显示在状态栏与单步结果里。
复盘时第一句话就该是"这一步是 shell 还是无障碍"，没有这个字段就没法定位问题。

### 已集成的 shell 操作（15 个）

分三层，职责不混：

| 层 | 位置 | 职责 |
|---|---|---|
| 构造 | `ShellCommands`（premium） | 拼命令 + **参数白名单校验**，纯函数、可单测 |
| 执行 | `ShizukuShellOps`（premium） | 跑命令 + 全局副作用**备份/恢复** |
| 界面 | `MainActivity` | 二次确认 + 审计日志 + 目标红线校验 |

| 操作 | 命令 |
|---|---|
| 读前台 Activity | `dumpsys activity activities` |
| 读设备标识 | `android_id` / `wlan0` MAC / `serial` / `model` |
| 隐藏 / 恢复系统栏 | `settings put global policy_control ...` |
| 关闭 / 恢复动画 | 三个 `*_animation_scale` / `animator_duration_scale` |
| 启用 / 移除本应用无障碍 | `enabled_accessibility_services`（**合并而不是覆盖**） |
| 清除应用数据 | `pm clear` |
| 强制停止 | `am force-stop` |
| 冻结 / 解冻 | `pm suspend` / `pm unsuspend` |
| 授予 / 撤销权限 | `pm grant` / `pm revoke` |
| 设置 AppOps | `cmd appops set` |

三个必须守住的原则：

1. **参数白名单**。这些字符串会拼进 `sh -c`，一个带 `;` 的包名就是任意命令执行。
   `ShellCommands` 里所有 builder 都对包名/权限/appop 做正则校验，非法直接抛异常。
   16 个单测里有 6 个专测这件事。
2. **全局副作用先备份再改**。`policy_control`、动画 scale、无障碍列表在改动前写入
   `SharedPreferences`，`restoreAll()` 写回原值。这是"进程被杀后设置残留、用户以为手机坏了"
   那个坑的正式解法。
3. **变更类操作不可自动触发**。UI 上 destructive 操作要二次确认，每次都写 `PolicyLog`。
   只读操作（前台/设备标识）才允许自动调用。

### 模型可以直接发 shell 动作（带分类闸门）

只改提示词让模型"主动用 adb"是不够的——动作空间里没有这一类，模型会输出解析不了的
action，然后解码失败、修复重试、最终 FAILED。所以补了完整的四段：

```
提示词（SHELL/ASKING 两段）
    ↓
Step.Shell(cmd)         ← 新增动作
    ↓
ActionDecoder           ← 解析 {"type":"shell","cmd":"..."}
    ↓
ShellCommandPolicy      ← 三档分类：只读 / 变更 / 禁止
    ↓
ActionGuard             ← 只读放行；变更与禁止一律硬升级
    ↓
ShellProvider.exec()    ← 追加包实际执行
```

| 分类 | 例子 | 结果 |
|---|---|---|
| `READ_ONLY` | `dumpsys` / `getprop` / `settings get` / `pm list` / `wm size` / `ps` / `pm list packages \| grep x` | 直接执行 |
| `MUTATING` | `settings put` / `pm clear` / `pm grant` / `am force-stop` / `input …` / `cmd appops set` / `wm size 1080x1920` | 硬升级 → 状态机 PAUSED，命令**不执行** |
| `FORBIDDEN` | `rm` / `su` / `reboot` / `dd` / `mount` / `chmod` / `kill` / 任何 `; && \|\| $() \` > <` 串接 / 未知命令 | 硬升级 + 明确拒绝 |

两个细节：

- **管道允许，命令链禁止**。`pm list packages | grep x` 放行（每段都要是只读或过滤器），
  但 `dumpsys; rm -rf /` 直接拒——分号是逃逸面。
- **未知命令按危险处理**。`mycustomtool --flag` 这类没登记的，一律 `FORBIDDEN`。

提示词里对应两段：SHELL 段告诉模型**什么时候该主动用**（用户说"用 adb"、界面读不到、
界面异常、两次没进展）以及**被拦了不要重试**；ASKING 段明确"不确定时默认问而不是试"，
并给了六个必须问的场景（目标不明、不可逆、需人参与、多路径、页面不符、两次无进展）。

### 被拦之后：待审脚本（不自动执行）

模型发出的变更类 shell 命令被闸门拦下后，不会卡死在"暂停"——它会落成**待审脚本**：

```
模型发 pm clear com.x
    ↓ 闸门判 MUTATING
    ↓ 不执行，写入 filesDir/scripts/<id>.sh
    ↓ 状态机 PAUSED
用户点「待审脚本：1」→ 看内容 → 执行 / 丢弃
    ↓ 执行时：base64 写入 /data/local/tmp → sh → 删除 → 成功则移出队列
```

**四条必须守住的点**：

1. **只有人能触发执行**。`ScriptRunner` 刻意不做命令分类——它的安全边界是
   "用户看过内容并点了确认"。模型只能落草稿，永远拿不到执行入口。
2. **不能放 app 私有目录再让 shell 调**。`/data/data/<pkg>/` 归 app uid 所有，
   shell（uid 2000）**读不到**（SELinux + 文件权限双重拦截）。所以真正执行的位置是
   `/data/local/tmp`，由 shell 自己写入。
3. **用 base64 而不是 heredoc**。脚本内容可能含引号、`$`、反引号、换行，
   heredoc 定界符一旦与内容撞上就会被截断，base64 无此问题。
4. **id 必须过字符集校验**。id 会拼进文件路径和远端路径，是本模块唯一的注入面
   （脚本内容按设计不校验）。`ScriptCodec.isSafeId` 只允许字母数字和 `-`。

研究模式下执行会被拦截并记录 `would-execute`，不会落盘也不会跑。

## 二·十、相册清理特化（`core/media`）

这是全项目**唯一能做到零模型 token** 的场景：清理相册不需要看屏幕，
一万张照片也不会发一张图给模型。

### 能复用什么、不能复用什么

| 想法 | 结论 |
|---|---|
| 复用系统**缩略图缓存** | ✅ 真能拿到。`ContentResolver.loadThumbnail(uri, 64×64)` 命中系统缓存，不用解码原图 |
| 复用**相册自己的相似分组** | ❌ 分组结果在各家私有数据库（如 `/data/data/com.miui.gallery/databases`），app 读不到，shell 也读不到 |
| MediaStore 元数据 | ✅ 时间/尺寸/bucket/size 直接可用，不用走文件系统遍历 |

所以"缓存命中"的准确含义是：**哈希用系统缩略图算，不解原图**。这是能不能跑到
万张级别的分水岭——解原图约 30ms/张，缩略图约 3ms/张。

### 决策核心是纯函数

`DedupPlanner` 零 Android 依赖、零模型调用，输入 `List<MediaItem>`，
输出可解释的分组建议：

| 分组 | 判据 | 需要模型吗 |
|---|---|---|
| `EXACT` | 大小 + dHash 完全一致 | 否（确定性） |
| `NEAR` | dHash 汉明距离 ≤ 6 | 否（确定性） |
| `BURST` | 近似 + 拍摄间隔 ≤ 3s | 否（确定性） |
| 大视频 | `size ≥ 100MB` | 否（排序即可） |

保留策略是**可解释的启发式**：分辨率 → 体积 → 时间（取新）。
写死在这里是因为它必须可预测；需要更聪明（模糊度、人脸、构图）时再引入模型，
但那是另一个功能。

### 三条安全底线

1. **永不建议删光一组**。`toGroup` 里 `drop.isEmpty()` 直接返回 null，
   单张不构成分组。有测试钉住。
2. **路径级黑名单**。包级黑名单在这里没用（我们看的是文件），
   所以 `SafePaths` 排除 `/sdcard/Android/data`、`/sdcard/Tencent`、
   `/sdcard/WeiXin`、`/sdcard/Pictures/.agent_trash`（自己的暂存区）等。
3. **先转移再删除**。默认落到 `Pictures/.agent_trash/`，用户确认后再清空，
   保留可撤销窗口。**绝不用 `rm`**——绕过回收站等于不可恢复。

### token 账

| 方案 | 1 万张照片的输入 |
|---|---|
| 截图 + VLM 逐张判断 | 约 1.5×10⁷ token |
| TUI 文本逐条 | 约 10⁵ token |
| **本地哈希 + 紧凑表格** | **约 3×10³ token** |

`DedupPlan.promptTable()` 就是那张表：按体积降序、带编号、默认只输出 40 行。
有测试断言"表格不随输入线性膨胀"（100 项输入 → 少于 20 行）。

### 完整链路（已实现）

```
MediaScanner         MediaStore 一次 query（图片 + 视频），不走文件遍历
    ↓ loadThumbnail 9x8 → DHash.hash     命中系统缩略图缓存，不解原图
DedupPlanner         EXACT / NEAR / BURST 聚类 + 大视频排序
    ↓ promptTable
TrashPlanner         只接受用户选中的分组；过滤保护区 / 太新的 / 超上限
    ↓ TrashEntry
TrashMover           mkdir → sha256sum 源 → cp → sha256sum+stat 副本
                     → 校验通过 → 才删源
    ↓ 成功后立刻写索引
TrashStore           filesDir/trash/index.json（原名↔暂存名↔摘要）
    ↑ 撤销：cp 回原路径 → 校验 sha256 → 通过才删暂存副本 → 从索引移除
```

**索引是还原的唯一依据**。暂存区里的文件名已经和原文件不同
（`0001-IMG_0001.jpg`），丢了索引就等于丢了原始路径——文件还在，但回不去。
所以索引在移动成功后**立刻**落盘，不能等批量结束再写。

**移动为什么必须走 shell**：app 沙箱没有写公共目录的能力（Scoped Storage），
`/sdcard/Pictures/.agent_trash` 只能由 shell 建和写。所以基础版能扫描、
能看建议，但**移入/撤销都需要追加包**——这是权限模型决定的，不是偷懒。

**扫描可中断、有进度**：哈希阶段每 25 张回调一次，UI 每 200ms 最多刷一次
（节流在主线程侧，不阻塞 IO），显示 `已处理/总数 · 百分比 · 已用 · 约剩`。
扫描期间按钮变成「取消扫描」，取消后已算的哈希不保存——半份缓存比没有更容易误导。

**逐组勾选，默认只勾确定性重复**：

| 分组 | 默认勾选 | 理由 |
|---|---|---|
| `EXACT` | ✅ | 字节级重复，删副本不可能丢内容 |
| `NEAR` / `BURST` | ❌ | 启发式相似，可能删掉构图更好的那张 |

这条策略在 `GroupSelection` 里，不在 UI 里——它是安全默认值，
有测试钉住（`default selects only exact duplicates`）：谁把它改成"全选"，测试会红。

**为什么不用 `mv`**：同一文件系统上 `mv` 是原子 rename，更省事，
但它**没法校验**——rename 之后源文件已经没了，复制坏了也发现不了。
多花一次 IO 换"可以验证"，值。

**为什么摘要要在移动时算**：扫描阶段对每个文件算 sha256 要读完整内容，
一万张照片就是几十 GB IO。规划阶段 `sha256` 留空，移动时逐条计算并回填。

## 三、依赖与协议

全部为可商用宽松协议，无 GPL / 无 Xposed 系：

| 依赖 | 用途 | 协议 |
|---|---|---|
| androidx.core-ktx | 基础 | Apache-2.0 |
| kotlinx-coroutines | 并发 | Apache-2.0 |
| org.json | JSON（Android 内置） | 内置，无需依赖 |
| org.json:json | **仅单测**：Android 的 org.json 是空壳（not mocked） | Public Domain |
| OkHttp | 网络 | Apache-2.0 |
| Shizuku api/provider | **仅 pro flavor**：shell 通道 | Apache-2.0 |
| JUnit4 | 测试 | EPL-1.0 |

**刻意不引入**：LSPatch（GPL-3.0，已归档）、LSPosed（GPL-3.0）、
LSPlant（LGPL-3.0）、Pine（无 LICENSE 文件）、SandHook（自定义条款且停更）。

## 四、本仓库的 LICENSE

尚未选定。建议 Apache-2.0（与依赖协议一致，含专利授权条款）。
选定前请勿公开分发。

## 五、Prohibited Targets

代码层面硬性禁止，由 `TargetGuard` 在输入、截图、协议、无障碍事件四个入口执行：

- 即时通讯：微信 `com.tencent.mm`、QQ `com.tencent.mobileqq`、TIM
- 金融支付：支付宝、云闪付及所有银行类应用
- 游戏：任何 `ApplicationInfo.CATEGORY_GAME` 应用
- 任何声明 `FLAG_SECURE` 的窗口

无法确定前台应用时一律拒绝操作（fail-closed）。
规则编译进 APK，**不提供远程关闭或用户覆盖选项**。

## 六、Self-Termination Policy

检测到禁用目标出现在前台或其进程存在时，应用会：

1. 写入触发记录（包名 + 规则 + 时间戳，作为审计证据）
2. 禁用自身无障碍服务（`disableSelf()`，否则系统会重启服务）
3. 撤销所有后台调度
4. 终止自身进程
5. 进入 10 分钟冷却期，期间所有启动入口静默拒绝

检测不确定时按命中处理（fail-closed）。

## 七、免责

本项目为研究/调试工具。使用者须自行确认：

- 目标 App 属于自己或已获授权
- 不用于绕过任何平台的风控、计费或权限机制
- 不用于获取他人数据或财产

作者不对使用者的行为承担责任。
