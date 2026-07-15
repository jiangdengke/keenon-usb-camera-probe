# Add foreground camera streaming service

## Goal

Keep the robot camera acquisition and outbound streaming alive when the probe Activity is no longer visible because the robot's main page or another application is in the foreground. Camera2, JPEG frame production, HTTP MJPEG serving, and WebSocket pushing must be owned by an Android foreground service rather than by the Activity lifecycle.

## What I already know

- The current app opens Camera2 cameras from `MainActivity` and uses Activity-owned `TextureView` surfaces to produce JPEG frames for `CameraStreamHub`.
- `MainActivity.onStop()` currently stops `CameraPushClient`, closes cameras, stops `CameraStreamHub`, and unregisters the USB monitor.
- The default production path is Camera2/HAL, with HTTP MJPEG fallback endpoints and WebSocket push to `ws://192.168.112.194:9090/`.
- The app has `minSdkVersion 19`, target SDK 27, and currently declares CAMERA and INTERNET permissions but no foreground service.
- The target behavior is to keep streaming after switching to another foreground app, assuming the other app does not concurrently claim the same camera resources.

## Assumptions

- The first implementation may preserve the existing UVC fallback path in the Activity or service only where practical; the required production path is Camera2/HAL.
- The service will use a service-owned headless Camera2 output for JPEG generation instead of relying on an Activity `TextureView` that can be destroyed in the background.
- The Activity will continue to provide field UI, permission requests, status display, and explicit user controls, but its `onStop()` will not stop a running streaming service.
- A persistent foreground notification is acceptable and required for reliable Android background execution.

## Requirements

- Add a non-exported `CameraStreamingService` declared in the manifest.
- Start the service after CAMERA permission is granted, using `startForegroundService()` on API 26+ and `startService()` on older supported versions.
- Call `startForeground()` immediately from the service and create a notification channel on API 26+.
- Move ownership of Camera2 camera devices, capture sessions, JPEG frame production, `CameraStreamHub`, and `CameraPushClient` into the service.
- Use service-owned Camera2 output surfaces so Activity visibility and TextureView destruction do not stop JPEG production.
- Keep the current WebSocket target, four-slot behavior, KJPG payload, HTTP port, and HTTP endpoints unchanged.
- Make service start/stop and resource cleanup idempotent.
- Re-check CAMERA permission inside the service before opening cameras and handle denied permission without crashing.
- Preserve Activity UI behavior when it returns to the foreground, including logs/status where feasible.
- Document the foreground service behavior, notification, start/stop expectations, and the important limitation that another app cannot simultaneously own the same cameras.

## Acceptance Criteria

- [ ] Debug APK builds successfully with the existing Gradle toolchain.
- [ ] Manifest contains the foreground-service permission and service declaration without exposing the service to other apps.
- [ ] Launching the Activity with CAMERA permission starts a foreground service and displays an ongoing notification.
- [ ] Camera2 JPEG frames continue to update after Activity `onStop()` and after switching to another app.
- [ ] HTTP MJPEG and WebSocket frame counters continue increasing while the Activity is stopped.
- [ ] Returning to the Activity does not open duplicate camera sessions, HTTP servers, or WebSocket connections.
- [ ] Explicit stop/close control releases Camera2, ImageReader/surfaces, HTTP, WebSocket, and service resources.
- [ ] Permission denial and API levels below Camera2 support are handled without a crash.
- [ ] README and `progress.md` document the new background-running behavior and verification steps.

## Definition of Done

- Relevant implementation and manifest changes are complete.
- Focused static/build checks pass.
- Real-device verification steps are documented, including Home/app switching, lock screen, Activity recreation, and explicit stop.
- Any new reusable Android lifecycle or camera-service convention is recorded in the task notes/spec if appropriate.
- Changes are committed as one coherent feature commit after checks pass.

## Out of Scope

- Changing the WebSocket binary protocol or HTTP endpoint contract.
- Upgrading target SDK, Gradle, NDK, or introducing a third-party networking dependency.
- Refactoring the legacy USB/libuvc native fallback beyond what is required to avoid duplicate resource ownership.
- Guaranteeing camera access when another foreground application is using the same camera devices.
- Persisting frames to disk or adding a database/queue for offline delivery.
- Creating a new release tag or GitHub Release in this task.

## Technical Notes

- Relevant implementation files: `MainActivity.java`, `CameraStreamHub.java`, `CameraPushClient.java`, `AndroidManifest.xml`, `app/build.gradle`, `README.md`, and `progress.md`.
- The current Camera2 path must be inspected carefully before implementation because it currently depends on Activity-owned `TextureView` surfaces.
- Cross-layer data flow: Camera2/HAL -> service-owned JPEG output -> `CameraStreamHub` latest-frame cache -> HTTP clients and `CameraPushClient` -> WebSocket receiver.
- Applicable project guidance: `.trellis/spec/backend/index.md`, `.trellis/spec/guides/cross-layer-thinking-guide.md`, and `.trellis/spec/guides/code-reuse-thinking-guide.md`.
