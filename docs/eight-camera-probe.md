# Eight Camera Probe

The probe app now opens up to 8 USB UVC cameras at the same time.

## Runtime behavior

- The app scans USB devices and queues UVC candidates until all 8 slots are used.
- The preview area is a fixed 4x2 grid.
- Each slot still exposes an MJPEG endpoint with the same index pattern:

```text
/stream/0.mjpeg
/stream/1.mjpeg
...
/stream/7.mjpeg
```

## Verification

The expected full-capacity status is:

```text
USB=8 UVC=8 opened=8/8 pending=0
```

Actual stability still depends on robot USB bandwidth, hub power, camera format, and UVC driver support.
