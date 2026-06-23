# Dynamic Camera Probe

The probe app now shows the detected USB UVC camera count, capped at 8 cameras.

## Runtime behavior

- The app scans USB devices and displays one preview slot per detected UVC camera.
- The safety cap remains 8 cameras; extra UVC devices are not opened.
- The top bar has a Chinese log toggle next to scan and close controls.
- The built-in strong diagnostics log records each slot's open phase, supported sizes, selected size, frame callback state, JPEG generation, and health diagnosis.
- If an opened slot still has no frame callback after 5 seconds, the app automatically reopens that slot up to 2 times; the second retry prefers YUYV and uses a lower bandwidth factor to separate first-open timing, format/bandwidth pressure, and hardware/hub/driver issues.
- The startup log prints the app version, and each opened slot prints an independent `自动重试监控` start line plus the 5-second check result.
- Each camera open first prefers 640x480 or lower supported preview sizes to reduce USB bandwidth pressure across multiple cameras.
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

When one route still has no video, open `显示日志` and check the selected size line for that slot. A line that says the app did not find 640x480 or lower means that camera is still using a higher minimum resolution, so USB bandwidth may remain the bottleneck.

For stronger diagnosis, also check the Chinese `强诊断` lines:

- `native open 成功`, `预览窗口已绑定`, `帧回调已注册`, and `startPreview 已调用` show how far the open pipeline reached.
- `帧回调首次到达` or `帧回调持续到达` means Java is receiving camera frames.
- `JPEG已生成` means the MJPEG/snapshot path has usable frames.
- `无帧回调` points first to USB bandwidth, power, camera format, or driver blocking.
- `有帧但无JPEG` means frames reached Java, but the NV21/JPEG encoding path needs further investigation.
- `自动重试监控` means the independent no-frame check is active; its 5-second result logs status, frames, fps, camera state, and retry count so field users can see why retry did or did not trigger.
- `自动重试` means the app detected an opened slot with no frame callback and reopened it. Recovery on the first retry points more to opening timing; recovery on the second YUYV low-bandwidth retry points more to format/bandwidth pressure; failure after both retries points more to hardware, hub, cable, or driver limits.
- The `/cameras` endpoint includes `lastFrameAgeMs`, `lastFrameBytes`, and `diagnosis` fields for remote checks.

## On-device logs

Use the top-bar `显示日志` button to show the built-in log panel above the preview grid. The same button changes to `隐藏日志` when the panel is visible.

The app-facing log text is localized for Chinese field users, including scan results, USB permission flow, camera open failures, MJPEG readiness, and health summaries.
