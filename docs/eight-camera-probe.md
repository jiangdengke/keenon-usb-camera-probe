# Dynamic Camera Probe

The probe app now shows the detected USB UVC camera count, capped at 8 cameras.

## Runtime behavior

- The app scans USB devices and displays one preview slot per detected UVC camera.
- The safety cap remains 8 cameras; extra UVC devices are not opened.
- The top bar has scan, close, first-slot-only, and Chinese log toggle controls.
- The built-in strong diagnostics log records each slot's open phase, supported sizes, selected size, frame callback state, JPEG generation, and health diagnosis.
- By default the app now uses the official-compatible Android Camera2/HAL path first, following the same `cameraIdList` style as Keenon's `CurrencyCameraActivity`. The old USB/libuvc path remains available by disabling Camera2 mode through ADB extras.
- In Camera2 mode, the app keeps the existing HTTP MJPEG/snapshot endpoints by capturing JPEG from each `TextureView`. To reduce field flicker, the capture interval is 800 ms and the preview request chooses a supported Camera2 fps range closest to 10-15 fps when available.
- If an opened slot still has no frame callback after 5 seconds, the app automatically reopens that slot up to 2 times. In compatibility recovery mode, retries first try another MJPEG size and then try YUYV. The YUYV fallback binds the real visible preview surface to drive native frame delivery, covers direct green preview output with an overlay, and requests RAW callbacks so Java can convert raw YUYV to JPEG for the on-tile overlay, HTTP streaming, and snapshots. If the first slot's real preview Surface refreshes while Java frame callbacks remain at zero, the app also tries to capture the `TextureView` into JPEG as a fallback source for `/stream/0.mjpeg`.
- The startup log prints the app version, and each opened slot prints an independent `自动重试监控` start line plus the 5-second check result.
- Compatibility recovery mode is enabled: each camera open prefers 640x480 or lower MJPEG, uses UVCCamera's default 1-30 FPS range, and keeps the bandwidth factor at 1.00. If a route has no frames, the first retry tries another MJPEG size and the second retry tries YUYV-to-JPEG fallback.
- The app staggers USB permission/open requests by about 900 ms so multiple `startPreview` calls do not hit the USB scheduler at the same instant.
- If a camera only advertises higher resolutions, the app uses that camera's smallest supported size and logs the selected size in Chinese.
- The preview area is generated dynamically: 1 camera uses one tile, 2 or more cameras use 2 columns.
- Each active slot exposes an MJPEG endpoint with the same index pattern:

```text
/stream/0.mjpeg
/stream/1.mjpeg
...
/stream/{active-slot-index}.mjpeg
```

When all 8 slots are active, the endpoint range is `/stream/0.mjpeg` through `/stream/7.mjpeg`.

## Verification

An expected 3-camera status is:

```text
USB=3 UVC=3 opened=3/3 max=8 pending=0
```

The expected full-capacity status is:

```text
USB=8 UVC=8 opened=8/8 max=8 pending=0
```

Actual stability still depends on robot USB bandwidth, hub power, camera format, and UVC driver support.

When one route still has no video, open `显示日志` and check the selected size line for that slot. In compatibility recovery mode, a persistent no-frame route after two retries should be checked against USB topology, hub power, cable, or the camera itself.

To check whether the first route is blocked by multi-camera concurrency, tap `只开第1路`. The app closes the other routes, opens only the first scanned UVC device, and keeps `/stream/0.mjpeg` as the verification endpoint.

For stronger diagnosis, also check the Chinese `强诊断` lines:

- `native open 成功`, `预览窗口已绑定`, `帧回调已注册`, and `startPreview 已调用` show how far the open pipeline reached.
- `帧回调首次到达` or `帧回调持续到达` means Java is receiving camera frames.
- `JPEG已生成` means the MJPEG/snapshot path has usable frames.
- `无帧回调` points first to USB bandwidth, power, camera format, or driver blocking.
- `有帧但无JPEG` means frames reached Java, but the NV21/JPEG encoding path needs further investigation.
- `自动重试监控` means the independent no-frame check is active; its 5-second result logs status, frames, fps, camera state, and retry count so field users can see why retry did or did not trigger.
- `错峰打开` means the app is delaying the next USB permission request to reduce simultaneous multi-camera startup pressure.
- `兼容恢复模式已启用` means the app is using UVCCamera's default FPS range and 1.00 bandwidth factor instead of the previous strong low-FPS or low-bandwidth diagnosis parameters.
- `MJPEG无帧，改试其它MJPEG分辨率` means the app is testing another MJPEG size after the initial 640x480 MJPEG route had no frame callback.
- `MJPEG仍无帧，改试YUYV兼容格式` means MJPEG candidates still had no frame callback and the app is testing YUYV.
- `第1路全档位探测` means the first slot still has no frames after the earlier fallbacks, so the app is automatically rotating through the declared MJPEG/YUYV sizes from smaller to larger for up to 10 retries. If any candidate logs `帧回调首次到达`, `JPEG已生成`, or `Surface抓图JPEG已生成`, field users should immediately test `/stream/0.mjpeg` and `/snapshot/0.jpg`.
- `官方兼容模式已启用`, `Camera2扫描结果`, `fpsRange`, or `抓图间隔=800ms` means the app is using Android Camera2/HAL instead of the direct USB/libuvc frame callback path. Field users should verify that each tile shows live video, that `Camera2：第N路画面首次到达` appears, and that `/stream/N.mjpeg` is reachable.
- `真实预览窗口引出帧回调`, `格式=RAW`, `YUYV RAW将转JPEG`, or `来源=YUYV->NV21` means YUYV is being driven through the real Surface while an overlay hides direct green preview output, and Java is trying to generate JPEG frames for the on-tile overlay, HTTP streaming, and snapshots.
- `Surface抓图JPEG已生成` or `来源=TextureView->JPEG` means the first slot produced JPEG from the real preview Surface even though Java `frameCallback` is still zero; field users should immediately test `/stream/0.mjpeg` and `/snapshot/0.jpg`.
- `自动重试` means the app detected an opened slot with no frame callback and reopened it. Failure after both MJPEG-size and YUYV retries points more to third-party UVC access or driver compatibility limits for that route; YUYV frames with increasing JPEG count indicate MJPEG incompatibility but working HTTP streaming through the YUYV fallback.
- The `/cameras` endpoint includes `openSequence`, `fpsMin`, `fpsMax`, `fpsFallback`, `bandwidthFactor`, `lowBandwidthMode`, `selectionReason`, `lastFrameAgeMs`, `lastFrameBytes`, and `diagnosis` fields for remote checks. Use `openSequence` to see whether the failed route is always the last opened slot.

## On-device logs

Use the top-bar `显示日志` button to show the built-in log panel above the preview grid. The same button changes to `隐藏日志` when the panel is visible.

The app-facing log text is localized for Chinese field users, including scan results, USB permission flow, camera open failures, MJPEG readiness, and health summaries.

Logs related to the first slot are highlighted in bold yellow in the on-device log panel so field users can photograph the important lines more easily. These highlighted lines should be checked first for `格式=RAW`, `帧回调首次到达`, `buffer=614400`, and `JPEG已生成`.

When field users scroll upward to review older logs, new log lines no longer force the log panel back to the bottom. Scroll back to the bottom or reopen the log panel to resume following the latest lines.
