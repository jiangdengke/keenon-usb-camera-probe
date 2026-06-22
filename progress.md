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
