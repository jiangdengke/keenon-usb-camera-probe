# Keenon UVC Multi Probe

这个目录里放的是一个用于擎朗/Peanut 机器人摄像头验证的 Android 探针 App。目标不是先做 RTSP/WebRTC 服务，而是先确认机器人系统能不能把多个摄像头当成 USB UVC 设备同时打开。

## 当前实现

- 基于 UVCCamera 的 UVC 能力改造，当前只保留一个 `app` 探针模块。
- App 名称：`Keenon UVC Multi Probe`。
- 包名：`com.serenegiant.usbcameratest7`。
- 启动后自动扫描 USB 设备，筛选 UVC 摄像头。
- 最多同时打开 8 路 UVC 摄像头。
- UI 是 4x2 预览网格，每个格子显示：
  - slot 状态
  - 预览分辨率和格式
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
adb logcat -s KeenonUvcProbe UVCCamera USBMonitor
```

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
   USB=8 UVC=8 opened=8/8 pending=0
   ```

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
- 只能打开一路：可能是 USB 带宽、电源、摄像头格式、机器人内核/驱动限制；可以尝试降低分辨率或只保留 MJPEG。
- 有预览但 `fps=0`：说明预览 surface 可能在跑，但 frame callback 没有数据；后续做流服务前需要解决这个问题。

## 下一步

等机器人上验证通过后，再做服务化输出：

1. 保留当前多 UVC 打开逻辑。
2. 从每路 `IFrameCallback` 拿 NV21/YUV 帧。
3. 根据使用场景选择：
   - 局域网低延迟预览：WebRTC
   - 简单浏览器/内网调试：HTTP MJPEG
   - 对接监控系统：RTSP
