# Keenon Camera Push Probe

这是用于擎朗/Peanut 机器人摄像头验证和对接的 Android 探针 App。

当前交付重点不是继续验证 USB UVC，而是：

1. 通过官方兼容的 Android Camera2/HAL 路径拿到机器人 4 路摄像头画面。
2. 默认把 4 路 JPEG 主动推送到接收端 `ws://192.168.112.194:9090/`。
3. 保留 HTTP MJPEG 拉流地址，作为现场和联调时的回退验证入口。

## 当前版本

- 最新预发布版：`v0.2.0-beta.1`（包含前台服务后台持续推流能力）
- 最新正式版：`v0.1.1`
- 包名：`com.serenegiant.usbcameratest7`
- 预发布 APK：`keenon-uvc-multi-probe-v0.2.0-beta.1.apk`
- 预发布 Release：<https://github.com/jiangdengke/keenon-usb-camera-probe/releases/tag/v0.2.0-beta.1>
- 预发布 APK 下载：<https://github.com/jiangdengke/keenon-usb-camera-probe/releases/download/v0.2.0-beta.1/keenon-uvc-multi-probe-v0.2.0-beta.1.apk>
- 稳定版 Release：<https://github.com/jiangdengke/keenon-usb-camera-probe/releases/tag/v0.1.1>

## 核心能力

### 摄像头获取

- 默认使用 Android Camera2/HAL 的 `cameraIdList` 打开摄像头。
- 该路径对齐 Keenon 官方 `CurrencyCameraActivity` 示例，不再依赖直接 USB/libuvc 打开作为主路径。
- Camera2、JPEG 生成、HTTP 和 WebSocket 由 Android 前台服务持有，不依赖 Activity 的 `TextureView` 生命周期。
- 服务优先选择 Camera2 上报的 `640x480` JPEG 输出；设备不支持时选择最接近的已上报 JPEG 尺寸。
- 为降低现场闪屏，Camera2 预览会优先选择接近 `10-15fps` 的系统支持帧率范围。

### 后台持续运行

获得 CAMERA 权限后，App 会启动 `CameraStreamingService` 前台服务，并显示“机器人摄像头推流运行中”常驻通知。

此后切到机器人主页面或其他 App 时：

- `MainActivity.onStop()` 只解绑界面，不关闭摄像头。
- Camera2 和 JPEG 生成继续运行。
- HTTP `8080` 服务继续运行。
- WebSocket 继续向 `ws://192.168.112.194:9090/` 推送。
- 返回本 App 时只重新连接服务并读取状态，不会重复打开相机或建立第二个推流连接。

点击 App 内“关闭全部”会明确停止前台服务并释放摄像头、HTTP 和 WebSocket 资源。

限制：如果机器人主页面或其他软件也要打开同一组摄像头，Camera HAL 可能拒绝并发访问。前台服务只能解决 Activity 退到后台后被生命周期主动关闭的问题，不能让两个 App 同时独占同一摄像头。

### 主动 WebSocket 推流

App 默认作为 WebSocket Client 主动连接：

```text
ws://192.168.112.194:9090/
```

默认推送行为：

```text
推送路数：4 路
slotIndex：0, 1, 2, 3
推送间隔：每路 500ms 一帧
数据格式：40 字节 KJPG 头 + JPEG 数据
```

### HTTP 拉流回退

原有 HTTP 拉流仍保留，方便浏览器/VLC/脚本快速验证：

```text
http://机器人IP:8080/cameras
http://机器人IP:8080/stream/0.mjpeg
http://机器人IP:8080/stream/1.mjpeg
http://机器人IP:8080/stream/2.mjpeg
http://机器人IP:8080/stream/3.mjpeg
http://机器人IP:8080/snapshot/0.jpg
http://机器人IP:8080/snapshot/1.jpg
http://机器人IP:8080/snapshot/2.jpg
http://机器人IP:8080/snapshot/3.jpg
```

## 安装

```bash
adb install -r keenon-uvc-multi-probe-v0.2.0-beta.1.apk
adb shell monkey -p com.serenegiant.usbcameratest7 1
```

如果签名不一致导致安装失败，先卸载旧包：

```bash
adb uninstall com.serenegiant.usbcameratest7
adb install -r keenon-uvc-multi-probe-v0.2.0-beta.1.apk
```

## 接收端对接说明

接收端需要在 `192.168.112.194:9090` 启动 WebSocket Server。

机器人端 App 会主动连接：

```text
ws://192.168.112.194:9090/
```

一个 WebSocket 连接内会混合推送 4 路摄像头，每条 binary message 是一帧 JPEG。

### 二进制消息格式

每条消息：

```text
40 字节固定头 + JPEG 数据
```

固定头使用 Big Endian：

```text
offset  size  field
0       4     magic        固定 0x4B4A5047，ASCII 为 KJPG
4       2     version      固定 1
6       2     headerBytes  固定 40
8       4     slotIndex    0 到 3，对应第 1 到第 4 路
12      8     timestampMs  Android System.currentTimeMillis()
20      8     sequence     每路单独递增的帧序号
28      4     width        JPEG 宽度
32      4     height       JPEG 高度
36      4     jpegLength   JPEG 数据长度
40      N     jpegData     完整 JPEG 文件内容
```

校验建议：

```text
magic == 0x4B4A5047
headerBytes == 40
jpegLength == message.length - 40
jpegData[0..1] == FF D8
jpegData 尾部通常为 FF D9
```

### Node.js 接收示例

```js
const fs = require('fs')
const WebSocket = require('ws')

const server = new WebSocket.Server({ host: '0.0.0.0', port: 9090 })

server.on('connection', (socket) => {
  console.log('camera client connected')

  socket.on('message', (data) => {
    const buffer = Buffer.from(data)
    if (buffer.length < 40) return

    const magic = buffer.readUInt32BE(0)
    if (magic !== 0x4B4A5047) return

    const version = buffer.readUInt16BE(4)
    const headerBytes = buffer.readUInt16BE(6)
    const slotIndex = buffer.readUInt32BE(8)
    const timestampMs = Number(buffer.readBigUInt64BE(12))
    const sequence = Number(buffer.readBigUInt64BE(20))
    const width = buffer.readUInt32BE(28)
    const height = buffer.readUInt32BE(32)
    const jpegLength = buffer.readUInt32BE(36)
    const jpeg = buffer.subarray(headerBytes, headerBytes + jpegLength)

    console.log({ slotIndex, version, timestampMs, sequence, width, height, jpegLength })

    // 调试时可以持续覆盖写每路最新图片。
    fs.writeFileSync(`camera-${slotIndex}.jpg`, jpeg)
  })
})
```

## ADB 参数

默认情况下不需要传参数。需要临时改推流目标或关闭推流时，可用 ADB extra 覆盖。

### 修改推流目标

```bash
adb shell am start -n com.serenegiant.usbcameratest7/.MainActivity \
  --ez push_enabled true \
  --es push_target_url ws://192.168.112.194:9090/ \
  --ei push_slot_count 4 \
  --ei push_interval_ms 500
```

### 关闭主动推流

```bash
adb shell am start -n com.serenegiant.usbcameratest7/.MainActivity \
  --ez push_enabled false
```

### 回退到旧 USB/libuvc 模式

一般不建议现场使用，除非需要复查旧路径：

```bash
adb shell am start -n com.serenegiant.usbcameratest7/.MainActivity \
  --ez use_camera2 false
```

## 现场验证

### App 日志

打开 App 顶部“显示日志”，重点看：

```text
官方兼容模式已启用
Camera2扫描结果
Camera2：第N路画面首次到达
WebSocket主动推流已启用
WebSocket推流已连接
WebSocket推流状态：目标=ws://192.168.112.194:9090/，累计=... S1=... S2=... S3=... S4=...
```

其中 `S1` 到 `S4` 持续增长，说明 4 路都在推。

### 后台持续推流验证

1. 启动 App 并确认出现“机器人摄像头推流运行中”通知。
2. 确认 App 日志出现 `Camera2后台：第N路首帧JPEG已到达`。
3. 在接收端记录 4 路 `sequence` 或接收帧数。
4. 按 Home 或切换到机器人主页面，保持至少 30 秒。
5. 确认接收端 4 路序号仍持续增长；同时可继续访问 `http://机器人IP:8080/cameras`。
6. 返回本 App，确认没有重复 WebSocket 连接，状态仍显示“后台推流服务=运行中”。

如需停止，返回本 App 点击“关闭全部”。强制停止应用、撤销 CAMERA 权限、设备重启，或其他 App 占用同一摄像头时，推流仍会停止。

如果看到：

```text
WebSocket推流连接/发送失败
```

优先检查：

1. 接收端是否已经启动 WebSocket Server。
2. 接收端是否监听 `0.0.0.0:9090` 或 `192.168.112.194:9090`。
3. 机器人与接收端是否在同一网络，且端口 9090 没有被防火墙拦截。

### HTTP 回退验证

如果 WebSocket 联调还没完成，可以先验证 HTTP 拉流：

```text
http://机器人IP:8080/cameras
http://机器人IP:8080/stream/0.mjpeg
http://机器人IP:8080/stream/1.mjpeg
http://机器人IP:8080/stream/2.mjpeg
http://机器人IP:8080/stream/3.mjpeg
```

4 个 `stream` 地址都能看到画面，说明机器人端摄像头取流和 JPEG 生成正常；剩下问题通常在 WebSocket 接收端或网络。

## 构建

本仓库是 Android/Gradle 项目，根目录就是工程目录。

构建环境：

```text
Android Gradle Plugin: 8.5.2
Gradle: 8.7
compileSdk: 34
buildTools: 34.0.0
targetSdk: 27
minSdk: 19
JDK: 17
NDK: 25.1.8937393
ABI: armeabi-v7a, arm64-v8a
```

本地构建：

```bash
./gradlew :app:assembleDebug
```

输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 自动发版

推送 `v*` tag 会触发 GitHub Actions 构建 APK，并创建 GitHub Release。

示例：

```bash
git tag -a v0.1.1 -m "发布 v0.1.1"
git push origin v0.1.1
```

注意：发版前需要先确认是正式版还是 prerelease。prerelease tag 应使用类似：

```text
v0.1.2-beta.1
v0.1.2-rc.1
```

## 历史兼容能力

项目最初用于 USB UVC/libuvc 多路摄像头诊断，历史兼容路径仍保留，主要用于排查旧问题：

- USB/libuvc 枚举和打开 UVC 设备。
- MJPEG / YUYV 格式探测。
- 第 1 路全档位探测。
- YUYV RAW 到 JPEG 的 Java 侧兜底。
- TextureView Surface 抓图到 JPEG 的兜底。
- 内置中文日志面板和第 1 路黄色高亮日志。

当前交付和对接默认不走这些历史路径，除非通过 ADB extra 显式关闭 Camera2 模式。
