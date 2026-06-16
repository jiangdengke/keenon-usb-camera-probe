# Keenon UVC Multi Probe

这个目录里放的是一个用于擎朗/Peanut 机器人摄像头验证的 Android 探针 App。目标不是先做 RTSP/WebRTC 服务，而是先确认机器人系统能不能把多个摄像头当成 USB UVC 设备同时打开。

## 当前实现

- 基于 `third_party/UVCCamera` 的 `usbCameraTest7` 改造。
- App 名称：`Keenon UVC Multi Probe`。
- 包名：`com.serenegiant.usbcameratest7`。
- 启动后自动扫描 USB 设备，筛选 UVC 摄像头。
- 最多同时打开 4 路 UVC 摄像头。
- UI 是 2x2 预览网格，每个格子显示：
  - slot 状态
  - 预览分辨率和格式
  - USB VID/PID
  - frame 计数
  - FPS
- Logcat tag：`KeenonUvcProbe`。

只要两个或更多格子的 `frames` 持续增加且 `fps > 0`，就说明机器人侧可以同时拿到多路 USB 摄像头视频流。后续就可以在这个基础上继续加 MJPEG/RTSP/WebRTC 输出。

## 项目位置

Android/Gradle 项目根目录：

```bash
keenon-usb-camera-probe/third_party/UVCCamera
```

主要改动文件：

```text
third_party/UVCCamera/usbCameraTest7/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java
third_party/UVCCamera/usbCameraTest7/src/main/AndroidManifest.xml
third_party/UVCCamera/usbCameraTest7/src/main/res/values/strings.xml
third_party/UVCCamera/settings.gradle
third_party/UVCCamera/build.gradle
third_party/UVCCamera/libuvccamera/src/main/jni/Application.mk
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

在 `third_party/UVCCamera` 下创建 `local.properties`，内容参考：

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

在 Android/Gradle 项目根目录执行：

```bash
cd keenon-usb-camera-probe/third_party/UVCCamera
./gradlew :usbCameraTest7:assembleDebug
```

输出 APK：

```text
usbCameraTest7/build/outputs/apk/debug/usbCameraTest7-debug.apk
```

注意：如果当前克隆的上游仓库缺少 `gradle/wrapper/gradle-wrapper.jar`，需要先恢复 wrapper jar，或直接在 Android Studio 里用 JDK 17 / Gradle 8.7 运行 `:usbCameraTest7:assembleDebug`。

## 安装到机器人

```bash
adb devices
adb install -r usbCameraTest7/build/outputs/apk/debug/usbCameraTest7-debug.apk
adb shell monkey -p com.serenegiant.usbcameratest7 1
```

查看日志：

```bash
adb logcat -s KeenonUvcProbe UVCCamera USBMonitor
```

## 验证标准

1. App 顶部状态显示类似：

   ```text
   USB=4 UVC=3 opened=3/4 pending=0
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
