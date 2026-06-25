# Keenon UVC Multi Probe

这个目录里放的是一个用于擎朗/Peanut 机器人摄像头验证的 Android 探针 App。目标不是先做 RTSP/WebRTC 服务，而是先确认机器人系统能不能把多个摄像头当成 USB UVC 设备同时打开。

## 当前实现

- 基于 UVCCamera 的 UVC 能力改造，当前只保留一个 `app` 探针模块。
- App 名称：`Keenon UVC Multi Probe`。
- 包名：`com.serenegiant.usbcameratest7`。
- 启动后自动扫描 USB 设备，筛选 UVC 摄像头。
- 检测到几路 UVC 摄像头就显示几路，默认最多同时打开 8 路。
- 顶部按钮区提供“扫描/打开”、“关闭全部”、“显示日志/隐藏日志”，日志面板展开后显示在预览区上方。
- App 内运行日志尽量使用中文，方便现场人员直接判断扫描、授权、打开失败、拉流和健康状态。
- 内置强诊断日志会记录每路摄像头的打开阶段、支持分辨率、实际选用分辨率、帧回调、JPEG 生成和健康诊断，方便定位单路无画面原因。
- 某一路打开后 5 秒仍无帧回调时会自动重开，最多重试 2 次；兼容恢复模式下会先改试其它 MJPEG 分辨率，再改试 YUYV。YUYV 兜底会绑定真实预览窗口引出帧回调，同时用覆盖层遮挡绿屏，并请求 RAW 回调由 Java 转 JPEG 用于格子显示、拉流和截图。
- 启动日志会显示当前 App 版本；每路打开后会单独打印“自动重试监控”启动和 5 秒检查结果，避免现场看不出为什么没有触发重试。
- 当前启用兼容恢复模式：打开摄像头时优先选择 640x480 或更低的 MJPEG 分辨率，预览 FPS 使用 UVCCamera 默认 1-30，带宽系数固定 1.00。若某一路无帧，第 1 次重开会尝试其它 MJPEG 分辨率，第 2 次重开会尝试 YUYV 转 JPEG 兜底。
- 多路摄像头不会瞬间连续打开，而是按约 900ms 错峰请求 USB 授权，降低多路 startPreview 同时争抢 USB 调度资源的概率。
- 如果摄像头不支持 640x480 或更低分辨率，会退到它支持的最小分辨率并在日志里提示，方便判断该路是否仍然有较高带宽压力。
- UI 会自动生成预览网格，1 路显示 1 个格子，2 路及以上按 2 列递增排列，每个格子显示：
  - slot 状态
  - 预览分辨率和格式
  - 打开序号
  - USB VID/PID
  - frame 计数
  - FPS
- Logcat tag：`KeenonUvcProbe`。

只要两个或更多格子的 `frames` 持续增加且 `fps > 0`，就说明机器人侧可以同时拿到多路 USB 摄像头视频流。后续就可以在这个基础上继续加 MJPEG/RTSP/WebRTC 输出。

## 项目位置

Android/Gradle 项目根目录就是仓库根目录：

```bash
keenon-usb-camera-probe
```

主要改动文件：

```text
app/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java
app/src/main/AndroidManifest.xml
app/src/main/res/values/strings.xml
settings.gradle
build.gradle
libuvccamera/src/main/jni/Application.mk
```

仓库已经删除未使用的上游 sample，只保留：

```text
app/           # 擎朗多路 UVC 探针 App
libuvccamera/  # UVC native/Java 库
gradle/        # Gradle wrapper
```

## 构建环境

这个上游 UVCCamera 项目比较老，但当前已把 Gradle/Android Gradle Plugin 升级到可用 JDK 17 构建：

- Android Gradle Plugin：`8.5.2`
- Gradle：`8.7`
- compileSdk：`34`
- buildTools：`34.0.0`
- targetSdk：`27`，为了保持老 USB permission/PendingIntent 逻辑兼容
- minSdk：`19`，对齐当前 NDK 25 支持的最低 native 平台
- Java：JDK 17
- NDK：需要带 `ndk-build`；当前本机示例路径使用 NDK `25.1.8937393`

本项目已经做了两个兼容性补丁：

- Gradle/AGP 升级到 JDK 17 可运行版本。
- `libcommon` 依赖仓库从 HTTP 改成 HTTPS，并从 jcenter 切到 mavenCentral/google。
- NDK ABI 去掉已废弃的 `armeabi`/`mips` 和机器人上不需要的 x86，保留 `armeabi-v7a arm64-v8a`。

## local.properties

在仓库根目录创建 `local.properties`，内容参考：

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
ndk.dir=/opt/homebrew/share/android-commandlinetools/ndk/25.1.8937393
```

也可以复制示例文件：

```bash
cp local.properties.example local.properties
```

然后把里面路径改成你机器上的 Android SDK/NDK 路径。

## 构建 APK

在仓库根目录执行：

```bash
cd keenon-usb-camera-probe
./gradlew :app:assembleDebug
```

输出 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

注意：如果当前克隆的上游仓库缺少 `gradle/wrapper/gradle-wrapper.jar`，需要先恢复 wrapper jar，或直接在 Android Studio 里用 JDK 17 / Gradle 8.7 运行 `:app:assembleDebug`。

## 安装到机器人

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.serenegiant.usbcameratest7 1
```

查看日志：

```bash
adb logcat -s KeenonUvcProbe KeenonStreamHub UVCCamera USBMonitor
```

也可以在 App 顶部点击“显示日志”，直接展开内置日志窗口；再次点击“隐藏日志”可收起。

## GitHub Release 自动发布

仓库已配置 GitHub Actions：推送 `v*` tag 后会自动构建 APK，并上传到对应的 GitHub Release。

发布一个新版本：

```bash
git tag -a v0.1.0 -m "发布 v0.1.0"
git push origin v0.1.0
```

Workflow 会执行：

1. 安装 JDK 17、Android SDK 34、Build Tools 34.0.0、NDK 25.1.8937393。
2. 生成 CI 用 `local.properties`。
3. 构建 `:app:assembleDebug`。
4. 将 APK 命名为 `keenon-uvc-multi-probe-vX.Y.Z.apk`。
5. 创建/更新 GitHub Release 并上传 APK。

也可以在 GitHub Actions 页面手动触发 workflow。手动触发只上传 workflow artifact，不创建 Release。

## 验证标准

1. App 顶部状态显示类似：

   ```text
   USB=3 UVC=3 opened=3/3 max=8 pending=0
   ```

   如果接满 8 路，则状态会接近 `USB=8 UVC=8 opened=8/8 max=8 pending=0`。

2. 至少两个 slot 同时显示预览画面。

3. 至少两个 slot 的：

   ```text
   frames=...
   fps=...
   ```

   持续增长，且 `fps > 0`。

满足以上条件，就可以认为“同时拿到多个摄像头视频流”在这个机器人系统上是可行的。

## 常见结果解释

- `UVC=0`：Android 系统没有枚举到 UVC 摄像头。需要检查摄像头是否真的是 USB UVC、USB 供电、Hub、机器人 USB Host 权限。
- `UVC>0 opened=0`：通常是 USB 权限被拒绝、预览 surface 未就绪、或 native 打开失败；看 `KeenonUvcProbe` 日志。
- 只能打开一路：可能是 USB 带宽、电源、摄像头格式、机器人内核/驱动限制；当前 App 使用兼容恢复参数和错峰打开，仍不稳定时更应优先检查 Hub、电源、USB 口拓扑或摄像头支持列表。
- 有预览但 `fps=0`：说明预览 surface 可能在跑，但 frame callback 没有数据；后续做流服务前需要解决这个问题。
- 有一路没有画面：先点顶部“显示日志”，看对应路是否有“打开失败”、`fps=0`、`JPEG延迟=暂无`、“需检查”，以及该路实际选用的分辨率是否仍然较高。
- 强诊断里出现“无帧回调”：通常优先怀疑 USB 带宽、供电、摄像头格式或驱动卡住。
- 强诊断里出现“有帧但无JPEG”：说明 Java 层收到帧，但 NV21/JPEG 编码链路异常，需要继续看 buffer 大小和编码失败日志。
- 日志里出现“自动重试监控”：说明该路已进入新版独立无帧检查；5 秒检查结果会打印状态、frames、fps、camera 和 retry 次数，用来判断为什么触发或没有触发重试。
- 日志里出现“错峰打开”：说明 App 正在延后请求下一路 USB 授权，用来降低多路同时启动压力。
- 日志里出现“兼容恢复模式已启用”：说明 App 正在使用 UVCCamera 默认 FPS 和 1.00 带宽系数，不再使用强低 FPS 或强低带宽诊断参数。
- 日志里出现“MJPEG无帧，改试其它MJPEG分辨率”：说明当前路 640x480 MJPEG 不出帧，App 正在尝试同一摄像头的其它 MJPEG 档位。
- 日志里出现“MJPEG仍无帧，改试YUYV兼容格式”：说明 MJPEG 候选仍没有帧，App 正在尝试 YUYV 格式。
- 日志里出现“真实预览窗口引出帧回调”、“格式=RAW”、“YUYV RAW将转JPEG”或“来源=YUYV->NV21”：说明这一路 YUYV 正在用真实 Surface 驱动底层出帧，同时由覆盖层遮挡原生绿屏，并由 Java 尝试把 RAW YUYV 转成 JPEG 给格子显示、拉流和截图使用。
- 日志里出现“自动重试”：说明某一路打开后持续无帧，App 已主动重开；如果其它 MJPEG 和 YUYV 都无帧，优先怀疑该路第三方 UVC 访问受限或驱动兼容问题；如果 YUYV 有帧且 JPEG 帧增长，说明 MJPEG 通道不兼容但 YUYV 兜底已可用于 HTTP 拉流。
- `/cameras` 会返回每路 `openSequence`、`fpsMin`、`fpsMax`、`fpsFallback`、`bandwidthFactor`、`lowBandwidthMode`、`selectionReason`、`lastFrameAgeMs`、`lastFrameBytes` 和 `diagnosis`，可以远程判断失败是否总是发生在最后打开的一路、是否已进入强低带宽策略、是否底层根本没有帧回调。

## 下一步

等机器人上验证通过后，再做服务化输出：

1. 保留当前多 UVC 打开逻辑。
2. 从每路 `IFrameCallback` 拿 NV21/YUV 帧。
3. 根据使用场景选择：
   - 局域网低延迟预览：WebRTC
   - 简单浏览器/内网调试：HTTP MJPEG
   - 对接监控系统：RTSP
