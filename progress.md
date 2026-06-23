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
