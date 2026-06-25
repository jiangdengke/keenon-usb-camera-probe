## 2026-06-22 - Task: Expand UVC probe to 8 cameras

### What was done
- Raised the probe capacity from 4 to 8 UVC cameras.
- Changed the preview layout from 2x2 to 4x2 so all 8 slots can be visible.
- Updated project documentation to describe the new 8-camera limit and expected stream endpoints.

### Testing
- Ran `./gradlew :app:assembleDebug` successfully.
- Checked IDE diagnostics for `MainActivity.java`; no diagnostics were reported.

### Notes
- `app/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java`: changed the maximum camera slot count to 8 and expanded the preview grid to 4 rows by 2 columns.
- `README.md`: updated the user-facing behavior and verification example from 4 cameras to 8 cameras.
- `docs/eight-camera-probe.md`: added usage notes for the 8-camera probe behavior and MJPEG endpoint range.
- Rollback: revert this task's changes in the three files above, or restore the previous commit/state before this task.

## 2026-06-22 - Task: Make UVC probe slots dynamic up to 8 cameras

### What was done
- Changed the probe from a fixed 8-slot display to showing one slot per detected UVC camera, capped at 8 cameras.
- Kept the 8-camera safety limit while making the preview rows and active MJPEG endpoints follow the active slot count.
- Updated documentation to describe dynamic slot display, the 8-camera cap, and the new status format.

### Testing
- Ran `./gradlew :app:assembleDebug` successfully.
- Checked IDE diagnostics for `MainActivity.java` and `CameraStreamHub.java`; no diagnostics were reported.

### Notes
- `app/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java`: added dynamic visible slot count management, capped scan/open behavior at 8 cameras, and updated status output to show active slots plus max capacity.
- `app/src/main/java/com/serenegiant/usbcameratest7/CameraStreamHub.java`: limited HTTP health, camera JSON, stream, and snapshot endpoints to the active slot count.
- `README.md`: updated the current behavior and verification examples for dynamic detected-camera display.
- `docs/eight-camera-probe.md`: changed the 8-camera note into dynamic probe behavior documentation while retaining the 8-camera cap.
- `progress.md`: appended this implementation and verification record.
- Rollback: revert this task's changes in the five files above, or restore the previous commit/state before this task.

## 2026-06-22 - Task: Add on-device Chinese log panel

### What was done
- Added a top-bar log toggle next to scan and close controls so field users can show or hide the built-in log panel without ADB.
- Moved the built-in log panel above the preview grid when visible, keeping it hidden by default to preserve preview space.
- Localized user-facing in-app logs and camera labels to Chinese for scan, permission, open failure, stream readiness, and health status.
- Updated documentation to explain the on-device log button and Chinese field logs.

### Testing
- Ran `./gradlew :app:assembleDebug` successfully.
- Checked IDE diagnostics for `MainActivity.java` and `CameraStreamHub.java`; no diagnostics were reported.

### Notes
- `app/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java`: added the top-bar log button, hide/show log panel behavior, and Chinese user-facing probe logs/status text.
- `app/src/main/java/com/serenegiant/usbcameratest7/CameraStreamHub.java`: changed app-facing stream and health logs to Chinese.
- `README.md`: documented the top-bar log button, updated Logcat tags, and added guidance for one missing preview.
- `docs/eight-camera-probe.md`: documented the Chinese log toggle and localized field logs.
- `progress.md`: appended this implementation and verification record.
- Rollback: revert this task's changes in the five files above, or restore the previous commit/state before this task.

## 2026-06-22 - Task: Prefer low-resolution UVC preview

### What was done
- Changed camera opening to choose 640x480 or lower supported preview sizes first, reducing USB bandwidth pressure when several UVC cameras run together.
- Kept a safe fallback for cameras that only advertise higher resolutions by choosing that camera's smallest supported preview size.
- Added Chinese in-app log text that shows the selected resolution and whether the app had to fall back to a higher minimum resolution.
- Updated documentation to explain the low-resolution preference and how to read the log when one route still has no video.

### Testing
- Ran `./gradlew :app:assembleDebug` successfully.
- Checked IDE diagnostics for `MainActivity.java`; no diagnostics were reported.

### Notes
- `app/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java`: changed preview-size selection to compare MJPEG/YUYV candidates together, prefer 640x480-or-lower choices, and log the selected resolution per slot.
- `README.md`: documented the low-resolution opening behavior and updated the troubleshooting guidance for one missing route.
- `docs/eight-camera-probe.md`: documented the bandwidth-reduction behavior and the log line to check when a route still has no video.
- `progress.md`: appended this implementation and verification record.
- Rollback: revert this task's changes in the four files above, or restore the previous commit/state before this task.

## 2026-06-23 - Task: Add strong UVC diagnostics logs

### What was done
- Added stronger Chinese diagnostics around each camera open phase, including native open, supported MJPEG/YUYV sizes, preview-size selection, preview binding, frame callback registration, and startPreview.
- Added throttled frame and JPEG diagnostics so field logs can distinguish no frame callback, frame callback stall, frames without JPEG, JPEG delay, and normal streaming.
- Extended the on-screen slot label and `/cameras` response with a diagnosis field plus recent frame age and buffer-size details.
- Updated documentation to explain which strong diagnostics lines to check when one route still has no video.

### Testing
- Ran `./gradlew :app:assembleDebug` successfully.

### Notes
- `app/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java`: added strong open-stage and frame-callback diagnostics while keeping logs throttled to avoid per-frame spam.
- `app/src/main/java/com/serenegiant/usbcameratest7/CameraStreamHub.java`: added JPEG generation diagnostics, health diagnosis text, and extra `/cameras` diagnostic fields.
- `README.md`: documented the strong diagnostics behavior and common diagnosis meanings.
- `docs/eight-camera-probe.md`: documented the strong diagnostics lines and remote `/cameras` fields for field verification.
- `progress.md`: appended this implementation and verification record.
- Rollback: revert this task's changes in the five files above, or restore the previous commit/state before this task.

## 2026-06-23 - Task: Add no-frame auto retry

### What was done
- Added automatic reopen for any opened camera slot that still has no frame callback after 5 seconds.
- Limited no-frame recovery to 2 retries: the first retry reopens with the same strategy, and the second retry prefers YUYV with a lower bandwidth factor.
- Kept the retrying device tied to its original slot while retry is pending, so the route number stays stable for field diagnosis.
- Updated documentation to explain what first-retry recovery, second-retry recovery, and two-retry failure indicate.

### Testing
- Ran `./gradlew :app:assembleDebug` successfully.
- Checked IDE diagnostics for `MainActivity.java`, `README.md`, and `docs/eight-camera-probe.md`; no diagnostics were reported.

### Notes
- `app/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java`: added no-frame retry timing, retry strategy selection, retry-state display, and retry-aware queue/slot handling.
- `README.md`: documented no-frame auto retry and how to interpret each retry outcome in Chinese.
- `docs/eight-camera-probe.md`: documented the retry behavior and field-diagnosis meaning for first retry, second retry, and persistent failure.
- `progress.md`: appended this implementation and verification record.
- Rollback: revert this task's changes in the four files above, or restore the previous commit/state before this task.

## 2026-06-23 - Task: Strengthen no-frame retry monitor logs

### What was done
- Added the app version to the startup log so field users can confirm which APK is installed.
- Added an independent no-frame retry monitor after each successful camera open, with a clear start log and a 5-second check-result log.
- Logged the retry monitor decision inputs (`status`, `frames`, `fps`, `camera`, and retry count) so missing retries can be diagnosed from the on-device log.
- Kept the FPS-loop retry path as a fallback with a short grace period, while the independent monitor remains the primary 5-second trigger.
- Updated README and docs to explain the new `自动重试监控` lines.

### Testing
- Ran `./gradlew :app:assembleDebug` successfully.
- Checked IDE diagnostics for `MainActivity.java`, `README.md`, and `docs/eight-camera-probe.md`; no diagnostics were reported.

### Notes
- `app/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java`: added startup version logging and independent no-frame retry monitor logging/triggering.
- `README.md`: documented version logging and how to interpret `自动重试监控` lines.
- `docs/eight-camera-probe.md`: documented the independent retry monitor and its 5-second check result.
- `progress.md`: appended this implementation and verification record.
- Rollback: revert this task's changes in the four files above, or restore the previous commit/state before this task.

## 2026-06-23 - Task: Add low-bandwidth UVC diagnosis mode

### What was done
- Enabled a strong low-bandwidth diagnosis mode that keeps MJPEG first, prefers 320x240-or-lower preview sizes, requests 1-10 FPS, and uses lower UVC bandwidth factors.
- Staggered USB permission/open requests by about 900 ms so multiple camera starts do not hit the USB scheduler at the same instant.
- Changed no-frame retry to re-enter the same staggered open queue and keep MJPEG-first low-bandwidth behavior instead of switching to YUYV first.
- Added per-route open sequence and selected open parameters to logs, labels, and `/cameras`, making it easier to see whether the failing route follows the last-opened slot or a specific USB device.
- Updated field documentation with the new low-bandwidth mode, staggered opening, retry meaning, and remote diagnosis fields.

### Testing
- Ran `./gradlew :app:assembleDebug` successfully.
- Checked IDE diagnostics for `MainActivity.java`, `CameraStreamHub.java`, `README.md`, and `docs/eight-camera-probe.md`; no diagnostics were reported.

### Notes
- `app/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java`: added strong low-bandwidth preview selection, 1-10 FPS preview requests, staggered permission/open scheduling, MJPEG-first retry queueing, and per-slot open sequence display.
- `app/src/main/java/com/serenegiant/usbcameratest7/CameraStreamHub.java`: added open sequence, FPS request, bandwidth factor, low-bandwidth mode, and selection reason to stream readiness logs and `/cameras` JSON.
- `README.md`: documented the strong low-bandwidth diagnosis behavior, staggered opening, retry interpretation, and new `/cameras` fields.
- `docs/eight-camera-probe.md`: documented the updated field verification behavior and diagnosis fields.
- `progress.md`: appended this implementation and verification record.
- Rollback: revert this task's changes in the five files above, or restore the previous commit/state before this task.

## 2026-06-23 - Task: Add FPS compatibility fallback

### What was done
- Added a fallback when cameras reject the low-bandwidth 1-10 FPS preview request, automatically retrying the same size and format with the wider 1-31 FPS range.
- Kept the low-bandwidth MJPEG-first size selection and bandwidth factor while avoiding a hard open failure on cameras that only accept their broader advertised FPS interval.
- Added `fpsFallback` to the on-device label and `/cameras` response so field users can tell whether a route had to use the compatibility FPS range.
- Updated field documentation to explain `低FPS不兼容` and `fpsFallback=true`.

### Testing
- Ran `./gradlew :app:assembleDebug` successfully.
- Checked IDE diagnostics for `MainActivity.java`, `CameraStreamHub.java`, `README.md`, and `docs/eight-camera-probe.md`; no diagnostics were reported.

### Notes
- `app/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java`: added preview FPS compatibility fallback and per-slot fallback display.
- `app/src/main/java/com/serenegiant/usbcameratest7/CameraStreamHub.java`: added `fpsFallback` to stream readiness logs and `/cameras` JSON.
- `README.md`: documented the compatibility FPS fallback and new diagnosis field.
- `docs/eight-camera-probe.md`: documented the field interpretation for low-FPS incompatibility.
- `progress.md`: appended this implementation and verification record.
- Rollback: revert this task's changes in the five files above, or restore the previous commit/state before this task.

## 2026-06-23 - Task: Restore compatible bandwidth for higher camera sizes

### What was done
- Kept the low-bandwidth diagnosis behavior for 320x240-or-lower previews while restoring a compatible 1.00 bandwidth factor when a camera only supports a higher minimum size such as 640x480.
- Preserved MJPEG-first selection, FPS fallback, staggered opening, and no-frame retry, but stopped lowering the bandwidth factor on higher-resolution fallback previews.
- Added logs that explicitly call out the compatible bandwidth fallback so field users can tell why 640x480 routes are not using 0.20/0.10.
- Updated README and field documentation to explain the beta.11 bandwidth behavior and how to interpret persistent no-frame routes after the fallback.

### Testing
- Ran `./gradlew :app:assembleDebug` successfully.
- Checked IDE diagnostics for `MainActivity.java`, `README.md`, `docs/eight-camera-probe.md`, and `progress.md`; no diagnostics were reported.

### Notes
- `app/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java`: added compatible bandwidth selection for previews above 320x240 and updated related diagnostics.
- `README.md`: documented that higher-resolution fallback previews use a 1.00 compatible bandwidth factor.
- `docs/eight-camera-probe.md`: documented the field interpretation for high-resolution compatible bandwidth fallback.
- `progress.md`: appended this implementation record.
- Rollback: revert this task's changes in the four files above, or restore the previous commit/state before this task.

## 2026-06-23 - Task: Restore compatible UVC preview parameters

### What was done
- Disabled strong low-bandwidth diagnosis mode so the probe uses compatibility recovery parameters for field retesting.
- Restored UVCCamera default preview FPS and a fixed 1.00 bandwidth factor while keeping MJPEG-first 640x480-or-lower preview selection.
- Kept staggered opening, Chinese diagnostics, `/cameras`, and no-frame retry, but prevented retry from changing FPS or bandwidth parameters.
- Updated README and field documentation to explain the compatibility recovery mode and how to interpret persistent no-frame routes.

### Testing
- Ran `./gradlew :app:assembleDebug` successfully.
- Checked IDE diagnostics for `MainActivity.java`, `README.md`, `docs/eight-camera-probe.md`, and `progress.md`; no diagnostics were reported.

### Notes
- `app/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java`: switched to compatibility recovery mode with UVCCamera default FPS and 1.00 bandwidth factor.
- `README.md`: documented the compatibility recovery mode and field interpretation.
- `docs/eight-camera-probe.md`: documented the compatibility recovery mode for onsite verification.
- `progress.md`: appended this implementation record.
- Rollback: revert this task's changes in the four files above, or restore the previous commit/state before this task.

## 2026-06-23 - Task: Add UVC no-frame parameter ladder

### What was done
- Added a no-frame retry ladder for compatibility recovery mode: first retry tries another MJPEG resolution, and second retry tries YUYV.
- Kept the first open on the beta.12-compatible 640x480-or-lower MJPEG, UVCCamera default FPS, and 1.00 bandwidth factor.
- Added Chinese retry reasons so field users can tell whether the app is testing another MJPEG size or YUYV after a route has no frame callback.
- Updated README and field documentation to explain how to interpret the MJPEG-size and YUYV fallback logs.

### Testing
- Ran `./gradlew :app:assembleDebug` successfully.
- Checked IDE diagnostics for `MainActivity.java`, `README.md`, `docs/eight-camera-probe.md`, and `progress.md`; no diagnostics were reported.

### Notes
- `app/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java`: added retry-aware preview candidate selection for alternate MJPEG and YUYV attempts.
- `README.md`: documented the no-frame parameter ladder.
- `docs/eight-camera-probe.md`: documented field interpretation for MJPEG-size and YUYV fallback logs.
- `progress.md`: appended this implementation record.
- Rollback: revert this task's changes in the four files above, or restore the previous commit/state before this task.

## 2026-06-24 - Task: Stabilize YUYV fallback diagnosis

### What was done
- Kept the beta.13 no-frame parameter ladder but made the YUYV fallback diagnostic-only.
- Skipped preview surface binding for YUYV fallback to avoid green preview output and native preview crashes.
- Added a stream-hub path that records YUYV frame arrival and buffer size without attempting NV21/JPEG encoding.
- Updated field documentation to explain that YUYV frames prove the route can deliver data, while visible output still requires explicit YUYV transcoding.

### Testing
- Ran `./gradlew :app:assembleDebug` successfully.
- Checked IDE diagnostics for `MainActivity.java`, `CameraStreamHub.java`, `README.md`, `docs/eight-camera-probe.md`, and `progress.md`; no diagnostics were reported.

### Notes
- `app/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java`: made YUYV fallback skip preview binding and route frames to format-only diagnostics.
- `app/src/main/java/com/serenegiant/usbcameratest7/CameraStreamHub.java`: added format-only frame handling that updates frame health without JPEG generation.
- `README.md`: documented diagnostic-only YUYV fallback.
- `docs/eight-camera-probe.md`: documented diagnostic-only YUYV fallback for onsite verification.
- `progress.md`: appended this implementation record.
- Rollback: revert this task's changes in the five files above, or restore the previous commit/state before this task.

## 2026-06-24 - Task: Add YUYV-to-JPEG fallback streaming

### What was done
- Kept the beta.14 safety behavior that skips preview surface binding for YUYV fallback to avoid green preview and native crashes.
- Changed the YUYV fallback from diagnostic-only to HTTP-output capable by converting YUYV frames to NV21 and then JPEG.
- Added a buffer-size guard so the fallback can also accept callbacks that native code already converted to NV21.
- Updated field documentation to tell onsite users to check `YUYV转JPEG模式`, `来源=YUYV->NV21`, and increasing JPEG frame counts.

### Testing
- Ran `./gradlew :app:assembleDebug` successfully.
- Checked implementation paths for stale YUYV diagnostic-only calls; no `onFormatOnlyFrame` or `isYuyvDiagnosticPreview` references remain.

### Notes
- `app/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java`: routes YUYV fallback frames to the YUYV-to-JPEG stream path while still skipping preview binding.
- `app/src/main/java/com/serenegiant/usbcameratest7/CameraStreamHub.java`: added YUYV/NV21 fallback handling and shared NV21 JPEG encoding.
- `README.md`: documented YUYV-to-JPEG fallback behavior and onsite log interpretation.
- `docs/eight-camera-probe.md`: documented YUYV-to-JPEG fallback behavior for onsite verification.
- `progress.md`: appended this implementation record.
- Rollback: revert this task's changes in the five files above, or restore the previous commit/state before this task.

## 2026-06-24 - Task: Add hidden-surface YUYV preview fallback

### What was done
- Kept YUYV from rendering directly into the visible preview surface to avoid green preview and native crashes.
- Added a hidden 1x1 preview `TextureView`/`Surface` for YUYV fallback so UVCCamera can start native preview and trigger frame callbacks.
- Reused the YUYV/NV21-to-JPEG stream path and added an on-tile JPEG overlay so onsite users can see fallback output directly in the app.
- Updated field documentation to explain the hidden preview surface log and the expected JPEG/on-tile output behavior.

### Testing
- Ran `./gradlew :app:assembleDebug` successfully.
- Checked implementation paths for hidden-surface YUYV fallback, JPEG overlay updates, and stale diagnostic-only documentation references.

### Notes
- `app/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java`: added hidden YUYV preview surface binding, JPEG overlay display, and cleanup on camera close.
- `app/src/main/java/com/serenegiant/usbcameratest7/CameraStreamHub.java`: exposed latest JPEG data for in-app overlay display.
- `README.md`: documented hidden-surface YUYV fallback behavior and onsite log interpretation.
- `docs/eight-camera-probe.md`: documented hidden-surface YUYV fallback behavior for onsite verification.
- `progress.md`: appended this implementation record.
- Rollback: revert this task's changes in the five files above, or restore the previous commit/state before this task.

## 2026-06-24 - Task: Request RAW callbacks for YUYV fallback

### What was done
- Changed YUYV fallback frame callbacks from native `NV21` output to `RAW` output so Java can receive raw YUYV without relying on native YUYV-to-NV21 conversion.
- Kept the hidden preview surface from beta.16 to satisfy UVCCamera's native preview pipeline while avoiding direct green YUYV preview output.
- Reused the existing Java `YUYV -> NV21 -> JPEG` path for on-tile display, HTTP MJPEG streaming, and snapshots.
- Updated field documentation so onsite users can identify beta.17 by `格式=RAW` and `YUYV RAW将转JPEG` logs.

### Testing
- Ran `./gradlew :app:assembleDebug` successfully.
- Checked implementation and documentation paths for RAW YUYV fallback references.

### Notes
- `app/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java`: registers `PIXEL_FORMAT_RAW` for YUYV fallback and logs the RAW callback mode.
- `app/src/main/java/com/serenegiant/usbcameratest7/CameraStreamHub.java`: continues to handle raw YUYV buffers and NV21-sized fallback buffers.
- `README.md`: documented RAW YUYV fallback behavior and onsite log interpretation.
- `docs/eight-camera-probe.md`: documented RAW YUYV fallback behavior for onsite verification.
- `progress.md`: appended this implementation record.
- Rollback: revert this task's changes in the five files above, or restore the previous commit/state before this task.

## 2026-06-25 - Task: Use visible Surface for YUYV RAW fallback

### What was done
- Changed YUYV fallback to bind the real visible preview Surface again so UVCCamera can use the same native path that previously produced YUYV frames.
- Kept RAW callbacks so Java still receives raw YUYV and performs `YUYV -> NV21 -> JPEG` without relying on native NV21 conversion.
- Kept the on-tile JPEG overlay visible during YUYV fallback so the native green preview is covered until Java-produced JPEG frames appear.
- Updated field documentation to explain the real-Surface-plus-overlay fallback and the logs to check onsite.

### Testing
- Ran `./gradlew :app:assembleDebug` successfully.
- Checked implementation paths for visible-Surface YUYV fallback, RAW callback registration, and overlay documentation.

### Notes
- `app/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java`: binds YUYV fallback to the real preview Surface while enabling the overlay and RAW callback path.
- `README.md`: documented visible-Surface YUYV RAW fallback behavior and onsite log interpretation.
- `docs/eight-camera-probe.md`: documented visible-Surface YUYV RAW fallback behavior for onsite verification.
- `progress.md`: appended this implementation record.
- Rollback: revert this task's changes in the four files above, or restore the previous commit/state before this task.

## 2026-06-25 - Task: Highlight first-route logs for onsite photos

### What was done
- Changed the on-device log panel to render first-route related log lines in bold yellow so onsite users can photograph the important lines more easily.
- Kept the existing camera open, YUYV RAW fallback, frame callback, and JPEG conversion logic unchanged.
- Documented that field users should prioritize the highlighted first-route lines when checking `格式=RAW`, `帧回调首次到达`, `buffer=614400`, and `JPEG已生成`.

### Testing
- Ran `./gradlew :app:assembleDebug` successfully.

### Notes
- `app/src/main/java/com/serenegiant/usbcameratest7/MainActivity.java`: renders first-route log lines with bold yellow spans in the in-app log panel.
- `README.md`: documented the bold yellow first-route log highlighting for onsite verification.
- `docs/eight-camera-probe.md`: documented the bold yellow first-route log highlighting in the probe guide.
- `progress.md`: appended this implementation record.
- Rollback: revert this task's changes in the four files above, or restore the previous commit/state before this task.
