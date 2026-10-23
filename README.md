# 白云桌宠 · 第一步（悬浮窗骨架）

一个最小可运行的 Android 桌宠工程：**悬浮窗形象 + 手指拖拽 + 松手贴边 + 前台服务通知**。
没有聊天、没有联网、没有多余依赖。

- 语言：Kotlin
- minSdk 24 / targetSdk 34 / compileSdk 34
- 架构：`MainActivity`（申请权限、开关桌宠）+ `FloatingPetService`（悬浮窗本体）
- 形象：`app/src/main/res/drawable-nodpi/pet_baiyun.png`（已抠掉白底，透明背景）

---

## 〇、环境要求

- Android Studio **Koala / Ladybug 及以上**（AGP 8.5.2，需要 JDK 17，Studio 自带）
- Gradle 8.7（由 Wrapper 自动下载）
- 一台 Android 7.0+ 的手机（真机效果最好，模拟器也能跑）
- 手机需要开启 **USB 调试**

---

## 一、目录结构

```
BaiYunPet/
├── .github/workflows/build.yml         # GitHub Actions 自动构建 APK
├── settings.gradle.kts                 # 模块声明、仓库配置
├── build.gradle.kts                    # 根构建脚本（插件版本）
├── gradle.properties                   # AndroidX / JVM 参数
├── gradle/wrapper/gradle-wrapper.properties   # Gradle 8.7
├── .gitignore
│
└── app/
    ├── build.gradle.kts                # 编译配置、依赖
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml         # 权限 + Service 声明
        │
        ├── java/com/baiyun/pet/
        │   ├── MainActivity.kt         # 主界面：权限申请、启动/关闭服务
        │   ├── FloatingPetService.kt   # 前台服务：桌宠窗口 + 气泡窗口 + 通知
        │   ├── Message.kt              # 消息数据类
        │   ├── DeepSeekChatManager.kt  # DeepSeek 接口 + 对话历史 + 错误处理
        │   ├── SettingsStore.kt        # SharedPreferences 统一存取
        │   ├── SettingsActivity.kt     # 设置页
        │   ├── BootReceiver.kt         # 开机自启
        │   ├── ManufacturerHelper.kt   # 厂商白名单引导
        │   ├── ScreenDetector.kt       # 前台 App 包名识别（UsageStats）
        │   ├── AppNameMapper.kt        # 包名 → 中文名 + 分类
        │   ├── BaiyunAccessibilityService.kt  # 无障碍读界面文字
        │   ├── ScreenContentParser.kt  # 文字 → 小说/游戏/日常/更新
        │   ├── ScreenCaptureManager.kt # MediaProjection 截屏
        │   ├── ScreenOcrProcessor.kt   # ML Kit 本地 OCR / 图片压缩
        │   ├── ScreenContextBuilder.kt # 三源上下文 → prompt 摘要
        │   ├── ProactiveChatManager.kt # 主动搭话：定时/话术池/防骚扰
        │   ├── ChatAdapter.kt          # 气泡里的消息列表适配器
        │   └── MaxHeightRecyclerView.kt# 支持 maxHeight=200dp 的 RecyclerView
        │
        └── res/
            ├── layout/activity_main.xml
            ├── layout/activity_settings.xml    # 设置页布局
            ├── xml/accessibilityservice.xml    # 无障碍服务配置
            ├── layout/chat_bubble_layout.xml   # 气泡布局（默认 GONE）
            ├── layout/item_chat_message.xml    # 单条消息
            ├── drawable/bg_chat_bubble.xml     # #CC222222 + 12dp 圆角
            ├── drawable/bg_settings_input.xml  # 设置页输入框
            ├── drawable/bg_msg_user.xml        # 用户蓝色气泡
            ├── drawable/bg_msg_pet.xml         # 白云灰色气泡
            ├── drawable/bg_chat_input.xml      # 输入框胶囊底
            ├── drawable/bg_send_button.xml     # 发送按钮圆底
            ├── drawable/ic_send.xml            # 纸飞机图标
            ├── values/strings.xml      # 全部中文文案
            ├── values/colors.xml
            ├── values/themes.xml       # Material 3 浅色主题
            ├── drawable/ic_pet_notification.xml   # 通知栏小云朵（矢量）
            ├── drawable/ic_launcher_foreground.xml
            ├── drawable-nodpi/pet_baiyun.png      # 桌宠形象（透明 PNG，720 宽）
            ├── drawable-nodpi/ic_launcher_head.png
            ├── mipmap-anydpi-v26/ic_launcher.xml
            ├── mipmap-anydpi-v26/ic_launcher_round.xml
            └── mipmap-*/ic_launcher[_round].png   # 48/72/96/144/192 五套桌面图标
```

---

## 二、第二步新增了什么（聊天气泡）

### 2.1 新增文件（全部是新建）

| 文件 | 作用 |
| --- | --- |
| `res/layout/chat_bubble_layout.xml` | 气泡本体：FrameLayout → 一层 LinearLayout（RecyclerView + 输入行），默认 `visibility="gone"` |
| `res/layout/item_chat_message.xml` | 单条消息：小云朵 + 文本，左右/颜色由适配器切换 |
| `res/drawable/bg_chat_bubble.xml` | 气泡底板 `#CC222222`、12dp 圆角、1dp 淡描边 |
| `res/drawable/bg_msg_user.xml` | 用户气泡：蓝色、右下角收窄 |
| `res/drawable/bg_msg_pet.xml` | 白云气泡：灰色、左下角收窄 |
| `res/drawable/bg_chat_input.xml` | 输入框圆角胶囊 |
| `res/drawable/bg_send_button.xml` | 发送按钮圆形背景（含按下态） |
| `res/drawable/ic_send.xml` | 纸飞机图标 |
| `java/.../Message.kt` | `data class Message(val text: String, val isUser: Boolean)` |
| `java/.../ChatAdapter.kt` | RecyclerView 适配器，接管左右布局与配色 |
| `java/.../MaxHeightRecyclerView.kt` | 原生 RecyclerView 不支持 `maxHeight`，用 onMeasure 限制到 200dp |

### 2.2 改动文件

| 文件 | 改了什么 |
| --- | --- |
| `FloatingPetService.kt` | 桌宠窗口改成「容器 + 80dp 形象」（留 6dp 供浮动）；加手势识别、气泡窗口、浮动动画、隐藏/恢复、通知双状态 |
| `MainActivity.kt` | 双击隐藏后按钮变成「叫白云出来」，状态栏显示「已隐藏」 |
| `strings.xml` | 新增气泡、隐藏通知相关文案 |
| `app/build.gradle.kts` | 增加 `androidx.recyclerview:recyclerview:1.3.2` |
| `README.md` | 就是你正在看的这份 |
| `AndroidManifest.xml` | **没改**（气泡不需要新权限） |

### 2.3 交互

| 操作 | 结果 |
| --- | --- |
| 单击形象 | 打开 / 收起气泡，`scaleX/scaleY` 从 0 弹到 1（Overshoot 回弹） |
| 双击形象 | 整个形象消失，只剩通知；点通知本体 → 从原位置回来 |
| 按住拖动 | 立即收起气泡，跟手移动，松手贴边 |
| 气泡打开期间 | 形象用 `ObjectAnimator` 在 ±5dp 之间上下浮动 |
| 发送 | 消息入列表（靠右蓝底）→ 输入框清空 → 调 DeepSeek（见第三节）→ 回复逐字显示（靠左灰底 + 小云朵） |
| 返回键 | 收起气泡（气泡窗口是可聚焦的） |

### 2.4 两个实现要点

1. **气泡是第二个悬浮窗，不是塞进桌宠窗口**。气泡 280dp 宽，比 80dp 的形象大得多；同一个窗口的话，窗口会覆盖一大片屏幕，用户点别处会被吞掉。拆开后，桌宠窗口仍然只有 92dp（80 + 6×2 留白），气泡窗口用 `FLAG_NOT_TOUCH_MODAL`，点气泡以外的地方不影响手机正常操作。
2. **浮动动画要留白**。`translationY` 会让 View 超出父容器，窗口内容是会被裁切的，所以形象外面套了一层 6dp padding 的容器，浮动 ±5dp 正好不切边。

### 2.5 已知限制（下一步再处理）

- 气泡固定贴在形象旁边：如果形象贴在屏幕最底部，弹出键盘可能会盖住输入框；
- 消息只存在内存里，服务被杀就没了（API Key 也是硬编码，见第三节）。

### 2.6 测试步骤

1. 装好、给权限、点「开启桌宠」；
2. 单击白云 → 气泡从她左侧（或右侧，看她靠哪边）弹出，同时她开始上下浮；
3. 输入「你好」→ 点纸飞机 → 你的消息靠右蓝色，约 0.4 秒后「你说：你好」靠左灰色带云朵；
4. 再单击白云 → 气泡缩回去，白云停止浮动；
5. 双击白云 → 她消失，通知栏变成「白云躲起来了」，点通知 → 她回到原位；
6. 关掉气泡再打开，历史消息还在（内存里）。

---

## 三、第三步新增：接 DeepSeek 聊天

### 3.1 新增文件

| 文件 | 作用 |
| --- | --- |
| `java/com/baiyun/pet/DeepSeekChatManager.kt` | 单例：OkHttp 异步请求、拼 messages、维护 10 轮历史、超时/HTTP/JSON 错误处理、切回主线程 |

### 3.2 改动文件

| 文件 | 改了什么 |
| --- | --- |
| `FloatingPetService.kt` | 发送改调 `DeepSeekChatManager`；新增打字机（30ms/字）、发送按钮置灰、请求中/打字中的状态机 |
| `res/values/strings.xml` | 加错误兜底文案、401 提示；删掉不再使用的 `chat_echo` |
| `AndroidManifest.xml` | **加了 `INTERNET` 权限**（不加以外网请求会直接抛异常） |
| `app/build.gradle.kts` | 加 `okhttp:4.12.0`、`gson:2.10.1`，版本升到 0.3.0 |

### 3.3 API Key 从哪来

现在不再硬编码：Key 存在 SharedPreferences 里，由设置页填写（见第四节）。
没填 Key 时发消息不会发请求，会直接提示「API Key 无效或没填」。

### 3.4 请求长什么样

```
POST https://api.deepseek.com/chat/completions
Authorization: Bearer sk-...
Content-Type: application/json

{
  "model": "deepseek-chat",
  "stream": false,
  "messages": [
    { "role": "system", "content": "你叫白云，是一个住在手机里的云朵状桌宠……" },
    ... 最近 10 轮（user / assistant 交替）...,
    { "role": "user", "content": "这把队友全是演员" }
  ]
}
```

- system prompt 在 `DeepSeekChatManager.SYSTEM_PROMPT`，改人设就改那一段；
- 历史存在 `history: MutableList<Message>`，**只在请求成功后**才写入，失败的那轮不会污染上下文；
- 超过 10 轮（20 条）时从最早的开始成对删除；
- 同一时间只允许一个请求（`inFlight` 标志），配合按钮置灰双保险。

### 3.5 错误处理

| 情况 | 处理 |
| --- | --- |
| 连接/读写/整体超时 | OkHttp 全部设成 **15 秒**，`SocketTimeoutException` → `onError("超时（15 秒）")` |
| HTTP 非 200 | 先尝试取 `error.message`，没有就截取前 200 字，回调带状态码 |
| JSON 解析失败 / choices 为空 | `onError("回复解析失败")`，原始响应打到 Logcat |
| 网络不通（DNS 失败等） | `onError("无法连接网络")` |
| 任何 `onError` 的最终展示 | 气泡里回一句「网络卡了？你这信号是2G考古吗」 |
| 401（Key 没填对） | 额外弹一个 Toast 提示去看 API Key，**不会**只让你猜网络 |

### 3.6 打字机与防连点

- 回复到达后先插一条空消息占位，`Handler.postDelayed` 每 **30ms** 追加一个字；
- 用 `codePointAt` 切片，emoji（比如 😂）不会被劈成两个乱码字符；
- 正在显示的 item 直接改 `TextView`，不整行 `notifyItemChanged`，长回复也不卡；
- 网络等待 + 打字期间发送按钮 `isEnabled=false`、`alpha=0.4`，从 UI 到逻辑两道闸；
- 气泡中途被关掉：立刻把完整回复写进列表（下次打开能看到），并结束打字任务。

### 3.7 测试步骤

1. 填好 Key → Run → 单击白云打开气泡；
2. 发「这把队友全是演员」→ 你的消息靠右，过 1~3 秒白云逐字回你；
3. 打字过程中点发送按钮：点不动（灰色）；
4. 断网再发一条 → 白云回「网络卡了？你这信号是2G考古吗」；
5. 故意把 Key 改错 → 同上一句 + Toast 提示 Key 有问题；
6. 连续聊十几轮 → 看 Logcat 请求体里历史条数一直封顶 20 条。

---

## 四、第四步新增：设置页

### 4.1 新增文件

| 文件 | 作用 |
| --- | --- |
| `java/com/baiyun/pet/SettingsStore.kt` | 集中管理三个 key、取值范围和默认值，读写 SharedPreferences |
| `java/com/baiyun/pet/SettingsActivity.kt` | 设置页：API Key、测试连接、两个滑块、保存返回 |
| `res/layout/activity_settings.xml` | ScrollView + LinearLayout，密码框 / SeekBar / 保存按钮 |
| `res/drawable/bg_settings_input.xml` | 设置页输入框：白底 + 浅描边 + 12dp 圆角 |

### 4.2 改动文件

| 文件 | 改了什么 |
| --- | --- |
| `DeepSeekChatManager.kt` | 删掉硬编码 Key；加 `init(context)`；每次请求从 `SettingsStore` 现读 Key；新增 `testConnection(apiKey, …)`（不写历史、不带历史） |
| `FloatingPetService.kt` | 启动时 `init` + 读设置；注册 SharedPreferences 监听：云朵大小变 → `removeView + addView` 重建，气泡透明度变 → 直接改 `alpha` |
| `MainActivity.kt` | 加「设置」按钮跳转；没填 Key 时按钮显示「设置（还没填 API Key）」 |
| `activity_main.xml` | 加 `btnSettings` |
| `AndroidManifest.xml` | 注册 `SettingsActivity`（exported=false） |
| `strings.xml` | 设置页文案、数值格式串 |
| `app/build.gradle.kts` | 版本 0.4.0（依赖不变） |

### 4.3 ★ API Key 现在填在哪

**App 里点「设置」→ 填 API Key → 点「测试连接」确认能用 → 点「保存」**（自动返回主界面）。

- 存在 `SharedPreferences` 文件 `baiyun_settings` 里，字段名 `api_key`；
- 申请地址：[platform.deepseek.com/api_keys](https://platform.deepseek.com/api_keys)；
- 输入框是 `textPassword`，界面上永远是 ●●●●；
- 保存后服务不用重启，下一条消息就会用新 Key（每次请求现读）。

### 4.4 设置怎么"实时生效"

| 设置 | 生效方式 |
| --- | --- |
| 云朵大小 | 服务收到 `KEY_CLOUD_SIZE` 变更 → `applyCloudSize()`：记住位置 → `removeView` → 按新 dp 重新 `showPet()` → 贴回最近的边 |
| 气泡不透明度 | 服务收到 `KEY_BUBBLE_ALPHA` 变更 → `bubbleView.alpha = 百分比/100`，气泡开着也立刻变 |
| API Key | 不缓存，每次 `sendMessage` 从 prefs 读 |

设置页里拖动滑块时底部有一块「气泡预览」，保存前就能看到透明度效果。

### 4.5 测试步骤

1. 主界面点「设置」→ 输入 Key → 点「测试连接」，Toast 显示「连接成功：<前10个字>…」；
2. 把 Key 改错再测 → 「连接失败：HTTP 401：…」；
3. 拖动云朵大小 60~120 → 保存 → 桌面上的白云立刻变大 / 变小，且仍贴着边；
4. 拖气泡不透明度到 50% → 保存 → 单击白云开气泡，气泡半透明能看见壁纸；
5. 返回主界面，设置按钮不再显示「还没填 Key」。

---

## 五、第五步新增：保活与厂商适配

### 5.1 新增文件

| 文件 | 作用 |
| --- | --- |
| `java/com/baiyun/pet/BootReceiver.kt` | 收 `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`，拉起桌宠服务 |
| `java/com/baiyun/pet/ManufacturerHelper.kt` | 品牌检测、白名单引导对话框（只弹一次）、按品牌跳设置页 |

### 5.2 改动文件

| 文件 | 改了什么 |
| --- | --- |
| `AndroidManifest.xml` | 加 `RECEIVE_BOOT_COMPLETED`；注册 `BootReceiver`（含 `MY_PACKAGE_REPLACED`） |
| `FloatingPetService.kt` | 通知渠道换 `_v2` 且重要性改为 **HIGH**；`onDestroy` 延迟 1 秒自愈重启；`suppressRestart` 防止用户主动关闭后被拉回来 |
| `MainActivity.kt` | 启动自检：有权限、没被关闭、服务没跑 → 自动拉起；首次开启后弹白名单引导 |
| `SettingsStore.kt` | 新增 `KEY_PET_ENABLED`（是否该运行）和 `KEY_FIRST_TIME`（引导只弹一次） |

> `FOREGROUND_SERVICE_SPECIAL_USE` 权限和 Service 的 `android:foregroundServiceType="specialUse"` 在第一步就已经写好了，这次没动。

### 5.3 保活做到什么程度

刻意只做三层，不用黑科技：

1. **前台服务 + 常驻通知**：这是唯一正规的“活着”的方式；
2. **`START_STICKY`**：系统回收进程后由系统重建；
3. **`onDestroy` 延迟 1 秒自愈**：服务被手动/系统停掉时自己再拉一次（失败就安静放弃）。

没有做的：双进程守护、1px 透明 Activity、互相唤醒、监听锁屏解锁反复拉起——这些既费电又容易被系统判定为恶意行为。

配套的 `suppressRestart` 很关键：用户点「关闭桌宠」时先写 `pet_enabled=false`，`onDestroy` 里看到这个标记就不再重启，否则会变成“关不掉”。

### 5.4 厂商适配

| 品牌 | 跳转页面 | 手动路径（对话框里也会写） |
| --- | --- | --- |
| 小米 / Redmi | `com.miui.securitycenter` → 自启动管理 | 应用设置 → 授权管理 → 自启动 |
| 华为 / 荣耀 | `com.huawei.systemmanager` → 启动管理（3 个候选类名） | 应用 → 应用启动管理 → 手动管理 |
| OPPO / 一加 / realme | `com.coloros.safecenter` / `com.oppo.safe` | 电池 → 应用耗电管理 → 允许后台运行 |
| vivo / iQOO | `com.vivo.permissionmanager` | 电池 → 后台耗电管理 → 允许后台高耗电 |
| 三星 | `com.samsung.android.lool` | 电池 → 后台使用限制 → 永不休眠 |
| 其他 | 系统「电池优化」列表 | — |

跳转逻辑：每个品牌准备多个候选组件 → `resolveActivity` 先判断存不存在 → `startActivity` 外面再包 `try-catch` → 全失败就 Toast「请手动到设置里加白名单」。

对话框由 `FLAG_FIRST_TIME` 控制，**每台设备只弹一次**；点「知道了」也算弹过。

### 5.5 测试步骤

1. **开机自启**：`adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.baiyun.pet`（部分设备 shell 无权限发受保护广播，直接重启真机即可），看桌面是否直接出现白云；
2. **自愈**：设置 → 应用 → 白云 → 强行停止后再打开 App，应自动拉起；或 `adb shell am force-stop` 后重新进 App；
3. **关得掉**：点「关闭桌宠」→ 等 2 秒，确认没有自己回来；
4. **白名单引导**：清除应用数据 / 重装 → 授权悬浮窗 → 回 App，应弹一次引导；点「去设置」跳到对应页面（模拟器上一般跳到系统电池优化页）；
5. **通知渠道**：设置 → 通知 → 白云，应看到「白云桌宠」且重要性为「紧急/高」；旧的低优先级渠道已被删除。

### 5.6 注意

- Android 12+ 对后台启动前台服务有限制，`BOOT_COMPLETED` 属于系统豁免，但个别 ROM 仍会拦，所以 BootReceiver 里失败只打日志；
- 用户「强行停止」过应用后，系统在用户手动打开前不会送任何广播，这是系统行为，不是 bug；
- 渠道重要性改成 HIGH 后，首次创建会响一声；后续因为 `setOnlyAlertOnce(true)` 不会再响。

---

## 六、第六步新增：前台 App 识别

### 6.1 新增文件

| 文件 | 作用 |
| --- | --- |
| `java/com/baiyun/pet/ScreenDetector.kt` | 用 `UsageStatsManager` 拿当前前台包名；查权限、过滤自己、过滤过期记录 |
| `java/com/baiyun/pet/AppNameMapper.kt` | 包名 → 中文名 + 分类（游戏/阅读/社交/视频/资讯/浏览器/购物），预置 60 个常见 App，未收录返回包名 |

### 6.2 改动文件

| 文件 | 改了什么 |
| --- | --- |
| `FloatingPetService.kt` | 每 **10 秒**查一次前台 App；切换时记录包名/名称/时间戳；游戏或阅读类才回调 `onAppChanged`（现在只 `Log.d`） |
| `MainActivity.kt` | 首次开启桌宠后引导「使用情况访问」权限（只弹一次） |
| `SettingsActivity.kt` / `activity_settings.xml` | 设置页加「使用情况访问权限」入口，随时可回去重新开 |
| `SettingsStore.kt` | 新增 `KEY_USAGE_GUIDE` 标记 |
| `AndroidManifest.xml` | 声明 `PACKAGE_USAGE_STATS`（特殊权限，需用户手动开） |
| `strings.xml` | 引导文案 |

### 6.3 怎么工作

```
每 10 秒：
  ScreenDetector.currentForegroundPackage()   →  com.tencent.tmgp.sgame
  AppNameMapper.resolve()                     →  "王者荣耀" / GAME
  和上次比：
    没变   → 什么都不做
    变了   → 记 package / 名称 / 时间戳
           是 GAME 或 READING 才回调 onAppChanged("王者荣耀")
```

回调接口在服务里：

```kotlin
var appChangeListener: AppChangeListener = AppChangeListener { appName ->
    Log.d(TAG, "onAppChanged: $appName")   // 下一步换成让白云说话
}
```

### 6.4 隐私

- **只读包名**，不截屏、不读屏幕内容、不读通知；
- 权限是系统的「使用情况访问」，只能在设置里手动开；
- 拿不到权限时检测直接跳过，不影响桌宠其它功能。

### 6.5 测试

1. 打开 App → 按提示去「使用情况访问」给白云授权；
2. `adb logcat -s FloatingPetService`；
3. 切到王者荣耀/原神/番茄小说，等 10~15 秒，日志应出现：
   `前台 App 切换：王者荣耀（com.tencent.tmgp.sgame，GAME）` 和 `onAppChanged: 王者荣耀`；
4. 切到微信，只会出现「前台 App 切换：微信」，**不会**触发 onAppChanged。

> 注意：UsageStats 是系统聚合数据，有 1~5 秒延迟，切 App 后不会立刻响应。

---

## 七、第七步新增：无障碍读屏文字

### 7.1 新增文件

| 文件 | 作用 |
| --- | --- |
| `java/com/baiyun/pet/BaiyunAccessibilityService.kt` | 无障碍服务：监听窗口变化，IO 线程遍历节点收文字，去重限流后回调 |
| `java/com/baiyun/pet/ScreenContentParser.kt` | 规则解析：小说 / 游戏对局 / 日常 / 更新，输出 `ScreenContext` |
| `res/xml/accessibilityservice.xml` | 无障碍配置：全 App、两类事件、500ms 合并、可读窗口内容 |

### 7.2 改动文件

| 文件 | 改了什么 |
| --- | --- |
| `AndroidManifest.xml` | 注册无障碍服务（`BIND_ACCESSIBILITY_SERVICE` 保护） |
| `FloatingPetService.kt` | 注册监听，把 `ScreenContext` 存进 `currentContext`（下一步聊天用） |
| `MainActivity.kt` | 首次开启后引导无障碍（只弹一次，和使用情况引导错开） |
| `SettingsActivity.kt` / `activity_settings.xml` | 设置页加「无障碍服务」入口 |
| `SettingsStore.kt` | 新增 `KEY_A11Y_GUIDE` |
| `strings.xml` | 无障碍说明、引导文案 |
| `app/build.gradle.kts` | 加 `kotlinx-coroutines-android:1.8.1`；版本 0.8.0 |

### 7.3 工作流程

```
系统事件（窗口切换 / 内容变化）
   ↓
BaiyunAccessibilityService.onAccessibilityEvent()
   ↓ 3 秒内同一界面直接丢弃
   ↓ 交给 Dispatchers.IO 协程
遍历节点树（最多 800 个节点 / 20 条文字）
   ├─ 跳过 EditText、isEditable、isPassword
   ├─ 只取 TextView / Button 的 text
   └─ 过滤：长度 2~50、必须含字母（汉字的 isLetter 为 true）、去掉广告词/网址
   ↓
ScreenContentParser.parse()
   ↓ 第 X 章 → NOVEL；胜利/失败/击杀/死亡/匹配 → GAME_MATCH
   ↓ 签到/领取/奖励 → DAILY；更新/版本/维护 → UPDATE
   ↓ 其余 → GENERIC
ScreenContext 回调给 FloatingPetService → currentContext
```

### 7.4 隐私边界

| 不做 | 做法 |
| --- | --- |
| 不读输入框 | `isEditable` / `isPassword` / `EditText` 直接跳过 |
| 不碰聊天/支付 | 微信、QQ、企业微信、钉钉、支付宝整包跳过（隐私名单） |
| 不读网页 HTML | 只取 TextView/Button 的可见文字，不解析 WebView 内容 |
| 不收长文本 | 单条超过 50 字直接丢弃 |
| 不刷屏 | 同一界面 3 秒内只处理一次，单界面最多 20 条 |

### 7.5 测试

1. 设置 → 无障碍 → 白云屏幕助手 → 打开；
2. `adb logcat -s BaiyunA11y FloatingPetService`；
3. 打开一本小说（比如番茄），翻到正文页，日志应出现：
   `解析：NOVEL / 第 12 章 风起云涌（13 条文字）`
   以及 `屏幕上下文：NOVEL / 第 12 章 风起云涌 / com.dragon.read`；
4. 打完一局游戏（或打开战绩页）应出现 `GAME_MATCH`；
5. 打开微信：整包被隐私名单跳过，日志里不会出现任何聊天内容。

> 注意：部分 App 用 Compose 或自绘控件，可能拿不到文字；无障碍读取也受系统限制，
> 开启后系统会提示「可读取屏幕内容」，这是该权限的必然行为。

---

## 八、第八步新增：截屏 + OCR（可选模块）

### 8.1 新增文件

| 文件 | 作用 |
| --- | --- |
| `java/com/baiyun/pet/ScreenCaptureManager.kt` | MediaProjection + ImageReader + VirtualDisplay，按间隔抓帧，长边压到 1280 |
| `java/com/baiyun/pet/ScreenOcrProcessor.kt` | 方案 A：ML Kit 中文 OCR（置信度 >0.7）；方案 B：JPEG 压缩 + base64 给云端 |

### 8.2 改动文件

| 文件 | 改了什么 |
| --- | --- |
| `DeepSeekChatManager.kt` | 新增 `describeScreenshot(base64, callback)`，OpenAI 兼容的 image_url 请求 |
| `FloatingPetService.kt` | 截屏生命周期、省电策略、OCR 结果存进 `currentContext` |
| `SettingsActivity.kt` / `activity_settings.xml` | 开关、识别方式、频率、首次隐私弹窗、录屏授权 |
| `SettingsStore.kt` | `KEY_CAPTURE_ENABLED / MODE / INTERVAL / PRIVACY` |
| `AndroidManifest.xml` | `FOREGROUND_SERVICE_MEDIA_PROJECTION`；服务类型加 `mediaProjection` |
| `app/build.gradle.kts` | `text-recognition-chinese:16.0.0`；版本 0.9.0 |

### 8.3 省电策略（默认关闭）

| 条件 | 行为 |
| --- | --- |
| 开关关着 | 完全不截 |
| 前台不是游戏/阅读 | 不截 |
| 前台是游戏 | 至少 **10 秒**一帧 |
| 前台是阅读 | 按设置（3/5/10 秒） |
| 电量 < 20% | 暂停 |
| 息屏 | 暂停 |
| 上一帧还在识别 | 丢新帧，不排队 |

### 8.4 两种识别

- **本地 ML Kit**：离线、免费、不出设备；只取置信度 > 0.7 的行，交给 `ScreenContentParser` 归类。
- **云端视觉**：短边压到 512、JPEG quality 60、base64 后发给 DeepSeek。

> ⚠️ DeepSeek 公开 API 目前以文本为主，图片请求很可能返回 `400`。
> 代码用的是 OpenAI 兼容格式，换成任意支持视觉的兼容端点/模型即可复用；
> 收到错误只会打日志，不会影响桌宠其它功能。

### 8.5 隐私

- 开关默认关闭，首次开启弹说明；
- 本地模式图片只在内存里，识别完立即 `recycle()`；
- 云端模式只上传压缩图，不落盘、不写历史；
- 不截聊天/支付场景（前台判断只放行游戏/阅读）。

### 8.6 测试

1. 先开启桌宠 → 设置 → 打开「白云看屏幕」→ 首次弹隐私说明 → 系统录屏授权；
2. 打开游戏或小说，等 5~10 秒；
3. 本地模式日志：`ML Kit 识别到 N 行` + `OCR 上下文：NOVEL / 第 12 章…`；
4. 云端模式日志：`云端视觉：…`（或 `HTTP 400`，属预期）；
5. 关掉开关 → 下拉通知栏不应再有"正在截取屏幕"的提示；
6. 电量低于 20% 或切到微信 → 不再出帧。

> 服务重启后旧授权失效，需要重新开一次开关（系统限制）。

---

## 九、第九步新增：屏幕上下文接入聊天

### 9.1 新增文件

| 文件 | 作用 |
| --- | --- |
| `java/com/baiyun/pet/ScreenContextBuilder.kt` | 汇总前台 App + 时长 + 无障碍文字 + OCR/视觉，输出一句话摘要（30 秒缓存） |
| `java/com/baiyun/pet/ProactiveChatManager.kt` | 主动搭话的触发规则 + 冷却时间，纯逻辑、可在后台线程跑 |

### 9.2 改动文件

| 文件 | 改了什么 |
| --- | --- |
| `DeepSeekChatManager.kt` | `buildSystemPrompt()` 每次请求现拼上下文；人设追加"屏幕感知"规则；新增 `sendProactive()` |
| `FloatingPetService.kt` | 把前台 App / 无障碍 / OCR / 视觉结果喂给 Builder；每 60 秒后台判断一次主动搭话；气泡开着就打字，没开就 Toast |
| `app/build.gradle.kts` | 版本 1.0.0 |

### 9.3 请求时发生什么

```
sendMessage("这把队友全是演员")
   ↓
buildSystemPrompt()
   ├─ SYSTEM_PROMPT（人设 + 屏幕感知规则）
   ├─ [当前屏幕上下文]
   │    app=王者荣耀 | 时长=47min | 界面文字=失败,匹配中
   └─ [要求] 根据以上上下文自然吐槽，不要生硬提及"我看到你在玩xx"…
   ↓
POST /chat/completions
```

摘要 30 秒内复用，期间有新数据（切 App / 新界面 / 新 OCR）会提前失效重建。
没有权限、没有数据时 `buildSummary()` 返回 null，system prompt 就是原始人设，**聊天完全不受影响**。

### 9.4 主动搭话

主动搭话已在第十节重做：定时检查 + 话术池 + 自动展开气泡。
本节只负责「屏幕上下文 → system prompt」，9.6 的测试也只验证上下文注入。

### 9.5 测试

1. 开启桌宠 + 使用情况访问 + 无障碍（可选截屏）；
2. 打开游戏，聊天时问"我现在该干嘛"，回复应自然带出你在玩游戏；
3. 关掉所有权限后正常聊天，确认 prompt 不含上下文、回复不受影响。

---

## 十、第十步新增：主动搭话（看屏精简版）

### 10.1 改动

| 文件 | 新建/修改 | 说明 |
| --- | --- | --- |
| `ScreenContextBuilder.kt` | 重写 | 只用 UsageStats + 无障碍文字，输出结构化摘要，30 秒缓存 |
| `ProactiveChatManager.kt` | 重写 | 定时检查 → 摘要 → 看屏请求 / 话术池兜底；场景匹配 + 防骚扰 |
| `DeepSeekChatManager.kt` | 修改 | 新增 `sendProactiveWithScreen(summary, sceneHint, cb)`，空回复跳过 |
| `FloatingPetService.kt` | 修改 | 只传文字列表给 builder；收到非空回复才展开气泡 |
| `BaiyunAccessibilityService.kt` | 修改 | 限流 3s → 5s，单屏 20 → 10 条 |
| `SettingsActivity.kt` / 布局 | 修改 | 新增「允许读界面文字」开关 + 隐私说明 |
| `SettingsStore.kt` | 修改 | `KEY_PROACTIVE_USE_SCREEN_TEXT` |

> 这条链路**不截屏、不 OCR、不调视觉 API**；截屏模块仍在，但属于另一套可选功能，不参与主动搭话。

### 10.2 摘要长什么样

```
app=原神 | 时长=47min | 界面文字=挑战失败,角色血量12%,剩余时间2:34
app=微信读书 | 时长=32min | 界面文字=第248章 决战,继续阅读
app=未知 | 时长=5min | 界面文字=无
```

- 文字取前 5 条有意义（过滤广告、纯数字、超过 30 字）；
- 关掉「允许读界面文字」后只剩 `app=… | 时长=… | 界面文字=无`；
- 摘要只存在内存里，30 秒缓存，不落盘、不留历史。

### 10.3 触发 / 请求流程

```
每 3 分钟（安静 5 / 话多 1，23:00~07:00 翻倍）
  → 五个条件（可见/气泡收起/3 分钟没聊/5 分钟没搭话/亮屏）
  → 取摘要
     ├─ 有效 → sendProactiveWithScreen(summary, sceneHint)
     └─ 无效 → 话术池随机一句 + "[主动搭话]" 前缀
  → 模型回复
     ├─ 空字符串 → 不弹气泡，本次跳过
     └─ 非空 → 展开气泡 + 云朵抖三下 + 打字机 + 10 秒后自动收起
```

系统提示里带 `[主动搭话-看屏模式]` 段落，明确“像朋友瞥了一眼屏幕后吐槽”，并禁止播报数据。

### 10.4 场景匹配（只影响语气，不写死台词）

| 界面文字命中 | 提示 |
| --- | --- |
| 失败/阵亡/挑战失败 | 吐槽 + 安慰 |
| 胜利/通关/完成/五杀 | 傲娇庆祝 |
| 第 X 章 / 继续阅读 / 下一章 | 催睡 / 催更 |
| 签到/领取/奖励/每日 | 提醒日常 |
| 匹配中/等待/排队 | 闲聊 |
| 游戏时长 > 60min | 休息提醒 |
| 阅读时长 > 30min | 熬夜警告 |
| 都没命中 | 基于 App 名随口吐槽 |

### 10.5 设置页

- 「白云主动找你聊」总开关；
- 频率：安静 5 分钟 / 正常 3 分钟 / 话多 1 分钟；
- 「允许读界面文字搭话」（关掉只用 App 名）；
- 「10 秒后自动收起气泡」；
- 隐私说明：不会读聊天/密码/支付内容。

### 10.6 防骚扰

- 23:00~07:00 间隔翻倍；
- 连续 3 次发出后 5 分钟没回应 → 暂停 30 分钟；
- 用户主动发消息 → 全部冷却清零；
- 模型主动返回空 → 不打扰、也不计入“没理”。

### 10.7 测试

1. 设置 → 打开「白云主动找你聊」，频率「话多」；
2. 打开一本小说（有“第 X 章”文字）等 1~2 分钟 → 气泡自动弹出、云朵抖动、逐字出现；
3. 关掉「允许读界面文字」→ 摘要只剩 App 名，回复变成通用吐槽；
4. 断网 / 清空 Key → 用话术池原句兜底；
5. 双击隐藏桌宠 / 开着气泡 → 不触发。

---

## 十一、怎么跑起来（从新建项目到看到桌宠）

### 方式 A（推荐，最稳）：新建项目后把文件覆盖进去

> 这样 Gradle Wrapper（`gradle-wrapper.jar`）由 Android Studio 自动生成，不会出现「找不到 Gradle」的报错。

1. **Android Studio → New Project → Empty Views Activity**
   - Name：`BaiYunPet`
   - Package name：`com.baiyun.pet`
   - Language：`Kotlin`
   - Minimum SDK：`API 24: Android 7.0`
   - Build configuration language：`Kotlin DSL (build.gradle.kts)`
   - 点 **Finish**，等首次 Gradle Sync 完成。

2. **把本工程的文件按同路径覆盖/新建到新项目里**（一共要处理的文件）：

   | 文件 | 操作 |
   | --- | --- |
   | `app/src/main/java/com/baiyun/pet/MainActivity.kt` | 覆盖模板生成的同名文件 |
   | `app/src/main/java/com/baiyun/pet/FloatingPetService.kt` | **新建** |
   | `app/src/main/java/com/baiyun/pet/Message.kt` | **新建** |
   | `app/src/main/java/com/baiyun/pet/ChatAdapter.kt` | **新建** |
   | `app/src/main/java/com/baiyun/pet/MaxHeightRecyclerView.kt` | **新建** |
   | `app/src/main/java/com/baiyun/pet/DeepSeekChatManager.kt` | **新建** |
   | `app/src/main/java/com/baiyun/pet/SettingsStore.kt` | **新建** |
   | `app/src/main/java/com/baiyun/pet/SettingsActivity.kt` | **新建** |
   | `app/src/main/java/com/baiyun/pet/BootReceiver.kt` | **新建** |
   | `app/src/main/java/com/baiyun/pet/ManufacturerHelper.kt` | **新建** |
   | `app/src/main/java/com/baiyun/pet/ScreenDetector.kt` | **新建** |
   | `app/src/main/java/com/baiyun/pet/AppNameMapper.kt` | **新建** |
   | `app/src/main/java/com/baiyun/pet/BaiyunAccessibilityService.kt` | **新建** |
   | `app/src/main/java/com/baiyun/pet/ScreenContentParser.kt` | **新建** |
   | `app/src/main/java/com/baiyun/pet/ScreenCaptureManager.kt` | **新建** |
   | `app/src/main/java/com/baiyun/pet/ScreenOcrProcessor.kt` | **新建** |
   | `app/src/main/java/com/baiyun/pet/ScreenContextBuilder.kt` | **新建** |
   | `app/src/main/java/com/baiyun/pet/ProactiveChatManager.kt` | **新建** |
   | `app/src/main/res/xml/accessibilityservice.xml` | **新建** |
   | `app/src/main/AndroidManifest.xml` | 覆盖 |
   | `app/src/main/res/layout/activity_main.xml` | 覆盖 |
   | `app/src/main/res/layout/chat_bubble_layout.xml` | **新建** |
   | `app/src/main/res/layout/activity_settings.xml` | **新建** |
   | `app/src/main/res/layout/item_chat_message.xml` | **新建** |
   | `app/src/main/res/values/strings.xml` | 覆盖 |
   | `app/src/main/res/values/colors.xml` | 覆盖 |
   | `app/src/main/res/values/themes.xml` | 覆盖 |
   | `app/src/main/res/drawable/ic_pet_notification.xml` | **新建** |
   | `app/src/main/res/drawable/bg_chat_bubble.xml` 等 5 个气泡 drawable | **新建**（见 2.1） |
   | `app/src/main/res/drawable/bg_settings_input.xml` | **新建** |
   | `app/src/main/res/drawable/ic_launcher_foreground.xml` | 覆盖 |
   | `app/src/main/res/drawable-nodpi/pet_baiyun.png` | **新建**（拷贝时选 Binary） |
   | `app/src/main/res/drawable-nodpi/ic_launcher_head.png` | **新建** |
   | `app/src/main/res/mipmap-*/ic_launcher.png`、`ic_launcher_round.png` | 覆盖（5 个密度目录） |
   | `app/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml` | 覆盖 |
   | `app/build.gradle.kts` | 用本工程的替换（或只把 `dependencies` 换成下面这份） |

3. **`app/build.gradle.kts` 的依赖**：

   ```kotlin
   dependencies {
       implementation("androidx.core:core-ktx:1.13.1")
       implementation("androidx.appcompat:appcompat:1.7.0")
       implementation("com.google.android.material:material:1.12.0")
       implementation("androidx.activity:activity-ktx:1.9.2")
       implementation("androidx.recyclerview:recyclerview:1.3.2")   // 气泡消息列表
       implementation("com.squareup.okhttp3:okhttp:4.12.0")         // DeepSeek 请求
       implementation("com.google.code.gson:gson:2.10.1")           // JSON
   }
   ```

4. 点右上角 **Sync Now**（大象图标）→ 等它跑完，**Build → Make Project** 确认无红色报错。

5. 手机开 **USB 调试** 并连上电脑（或直接开模拟器）→ 选中设备 → 点绿色 **Run ▶**。

6. 首次进入 App 的流程：
   1. 点 **开启桌宠** → 自动跳到「显示在其他应用上层 / 悬浮窗」设置页；
   2. 打开 **白云** 的开关 → 按返回键回到 App（App 会自动继续启动）；
   3. Android 13+ 会再弹一次 **通知权限**，允许；
   4. 屏幕右上角出现白云，通知栏出现「白云正在陪你」。

### 方式 B：直接打开本工程

`File → Open` 选 `BaiYunPet` 目录。因为压缩包里**没有** `gradle-wrapper.jar`（二进制文件没法凭空生成），如果 Studio 报
`Could not find or load main class org.gradle.wrapper.GradleWrapperMain`，任选一种修：

- 在任意一个已有工程的 `gradle/wrapper/` 里把 `gradle-wrapper.jar` 复制过来；
- 或在装了 Gradle 的机器上执行 `gradle wrapper --gradle-version 8.7` 重新生成；
- 或干脆按 **方式 A** 新建项目再拷文件。

### 方式 C：用 GitHub Actions 自动出 APK（不用装 Android Studio）

工程里已经带好了 `.github/workflows/build.yml`：每次 push 自动构建 debug APK，
打 `v*` tag 还会自动发 Release，手机浏览器点链接就能下载安装。

**第一次配置（5 分钟）**

1. 注册 / 登录 [GitHub](https://github.com)（免费）；
2. 新建一个空仓库（不要勾 README / .gitignore），比如叫 `BaiYunPet`；
3. 把 `BaiYunPet` 文件夹里的**所有内容**（包括隐藏的 `.github/`）当作仓库根目录推上去：

   ```bash
   cd BaiYunPet
   git init -b main
   git add .
   git commit -m "BaiYunPet v0.5.0"
   git remote add origin https://github.com/<你的用户名>/BaiYunPet.git
   git push -u origin main
   ```

4. 打开仓库的 **Actions** 页，如果提示 workflow 未启用，点 **I understand my workflows, go ahead and enable them**；
5. 等第一次构建跑完（约 3~5 分钟），在 run 页面底部的 **Artifacts** 下载 `BaiYunPet-0.5.0`（zip，里面是 APK）。

**以后想拿「可直接点开下载」的链接**：打 tag 推送即可

```bash
git tag v0.5.0
git push origin v0.5.0
```

构建完成后仓库 **Releases** 里会出现 `v0.5.0`，附带的 `BaiYunPet-0.5.0-debug.apk`
手机浏览器直接点就能下载安装（需允许「安装未知应用」）。

**说明**

- **固定签名**：仓库里带了一个自用签名 `keystore/baiyun-release.p12`（密码在 `app/build.gradle.kts`），
  CI 和本地都用它签名，所以新版本可以直接覆盖安装、保留设置。
  这个 key 是公开的，只适合自用；要上架商店请换成自己私有的 release keystore；
- 不需要 `gradle-wrapper.jar`：workflow 里直接安装 Gradle 8.7 构建，绕开这个坑；
- 私有仓库每月有 2000 分钟免费额度，一次构建约 3~5 分钟；公开仓库免费无限量；
- 如果 Actions 页看不到 workflow，通常是 `.github/` 没被提交（有些解压工具会隐藏/丢弃点文件），
  确认仓库根目录存在 `.github/workflows/build.yml`。

> 提示：以后改了代码，`git push` 就会重新构建；版本号在 `app/build.gradle.kts` 里改。

---

## 十二、代码里几个关键点（方便你后面继续加功能）

### 1. 悬浮窗权限（`MainActivity`）

```kotlin
Settings.canDrawOverlays(this)          // 是否已授权
Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName"))  // 直接跳到本应用的授权页
```

授权页返回后系统**不一定**给 result（国产 ROM 尤其如此），所以 `onResume()` 里又补了一次检查：
只要用户回来时权限已开，且刚才点过「开启桌宠」，就自动继续启动。

### 2. 悬浮窗（`FloatingPetService.showPet()`）

```kotlin
WindowManager.LayoutParams(
    80dp, 80dp,
    TYPE_APPLICATION_OVERLAY,          // Android 8.0+ 必须用这个类型
    FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN,
    PixelFormat.TRANSLUCENT
)
```

- `FLAG_NOT_FOCUSABLE`：不抢焦点，不会挡住输入法；
- `FLAG_LAYOUT_IN_SCREEN`：坐标以**整块屏幕左上角**为原点，方便直接用 `rawX/rawY` 换算；
- 初始位置 `x = 屏幕宽 - 80dp`、`y = 96dp`，也就是右上角。

### 3. 拖拽 + 吸附

- `ACTION_DOWN` 记下手指的 `rawX/rawY` 和窗口当前的 `x/y`；
- `ACTION_MOVE` 用**差值**移动窗口，并 `coerceIn` 限制在屏幕内（不会拖出边界）；
- 超过 `ViewConfiguration.scaledTouchSlop` 才算拖动，避免手抖误判；
- `ACTION_UP` 调 `snapToNearestEdge()`：判断窗口中心在屏幕左半还是右半，然后用
  `ValueAnimator.ofInt(当前x, 目标x)` + `DecelerateInterpolator` 播放 260ms 的贴边动画；
- 拖动中还带了一点「歪头」（`rotation`），松手回正，手感更活。

### 4. 前台服务（防杀 + 通知）

- `onCreate()` 里先 `createNotificationChannel()` 再 `startForeground()`，顺序不能反；
- 通知渠道名 = **白云桌宠**，通知标题 = **白云正在陪你**，`setOngoing(true)` 常驻；
- Android 14（targetSdk 34）强制要求前台服务声明类型，所以：
  - `AndroidManifest.xml` 里 `android:foregroundServiceType="specialUse"` + `<property>`；
  - 代码里 `startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`；
  - 少了这两个，Android 14 上会直接崩 `MissingForegroundServiceTypeException`。

---

## 十三、在真机上测试时注意

| 现象 | 原因 / 解决 |
| --- | --- |
| 点了开启没反应 | 悬浮窗权限没给。到「设置 → 应用 → 白云 → 显示在其他应用上层」打开 |
| 小米/红米：授权了还是不显示 | 还要开「后台弹出界面」权限（MIUI 特有），并在最近任务里给白云「加锁」 |
| 华为/荣耀：服务被杀 | 设置 → 电池 → 应用启动管理 → 白云 → 允许后台活动 |
| 通知栏看不到通知 | Android 13+ 没给通知权限；或渠道被手动关了（设置 → 通知 → 白云） |
| 重启手机后桌宠没了 | 这一步没做开机自启（`BOOT_COMPLETED`），属于下一步的内容 |
| 拖到屏幕底部消失 | 已用 `maxY = 屏高 - 80dp` 限制，正常拖不出去 |

---

## 十四、下一步（本次刻意没做）

1. 主动搭话的话术池支持云端下发；
2. 消息持久化（现在关掉服务就没了）；
3. 清空对话按钮（`DeepSeekChatManager.reset()` 已经预留好了）；
4. 待机小动画（呼吸、眨眼）、气泡跟随拖动；
5. 流式输出（`stream: true`）+ 语音输入；
6. 保活策略做成开关（有人不喜欢 HIGH 通知）。

---

图标、形象素材都在 `res` 里，替换 `pet_baiyun.png` 即可换形象（建议透明 PNG，宽 720px 左右）。
