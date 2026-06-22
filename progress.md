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
