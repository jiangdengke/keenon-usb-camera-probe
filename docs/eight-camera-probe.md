# Dynamic Camera Probe

The probe app now shows the detected USB UVC camera count, capped at 8 cameras.

## Runtime behavior

- The app scans USB devices and displays one preview slot per detected UVC camera.
- The safety cap remains 8 cameras; extra UVC devices are not opened.
- The top bar has a Chinese log toggle next to scan and close controls.
- The built-in strong diagnostics log records each slot's open phase, supported sizes, selected size, frame callback state, JPEG generation, and health diagnosis.
- If an opened slot still has no frame callback after 5 seconds, the app automatically reopens that slot up to 2 times. In compatibility recovery mode, retries first try another MJPEG size and then try YUYV so field users can see whether the route is size-specific, MJPEG-specific, or fully unavailable through third-party UVC.
- The startup log prints the app version, and each opened slot prints an independent `自动重试监控` start line plus the 5-second check result.
- Compatibility recovery mode is enabled: each camera open prefers 640x480 or lower MJPEG, uses UVCCamera's default 1-30 FPS range, and keeps the bandwidth factor at 1.00. If a route has no frames, the first retry tries another MJPEG size and the second retry tries YUYV.
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
- `自动重试` means the app detected an opened slot with no frame callback and reopened it. Failure after both MJPEG-size and YUYV retries points more to third-party UVC access or driver compatibility limits for that route.
- The `/cameras` endpoint includes `openSequence`, `fpsMin`, `fpsMax`, `fpsFallback`, `bandwidthFactor`, `lowBandwidthMode`, `selectionReason`, `lastFrameAgeMs`, `lastFrameBytes`, and `diagnosis` fields for remote checks. Use `openSequence` to see whether the failed route is always the last opened slot.

## On-device logs

Use the top-bar `显示日志` button to show the built-in log panel above the preview grid. The same button changes to `隐藏日志` when the panel is visible.

The app-facing log text is localized for Chinese field users, including scan results, USB permission flow, camera open failures, MJPEG readiness, and health summaries.
