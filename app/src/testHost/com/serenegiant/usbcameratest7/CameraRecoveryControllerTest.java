package com.serenegiant.usbcameratest7;

/** Deterministic host checks of the production owner/retry state; no Android or test libraries. */
public final class CameraRecoveryControllerTest {
    private static int assertionCount;

    private static final class FakeDevice {
        int closeCount;
    }

    private static CameraRecoveryController<FakeDevice> createController() {
        return new CameraRecoveryController<FakeDevice>(
            new CameraRecoveryController.DeviceCloser<FakeDevice>() {
                @Override
                public void close(final FakeDevice device) {
                    device.closeCount++;
                }
            });
    }

    public static void main(final String[] arguments) {
        closesFailureBeforeOpened();
        waitsForCloseAfterOpened();
        closesCancelledPendingOpen();
        isolatesLateCallbackAfterSynchronousFailure();
        preventsOverlapWhenEnumerationShifts();
        preservesBackoffAcrossNotificationsAndResetsOnJpeg();
        capsBackoffWithoutDoubleCounting();
        preservesEarliestScanAndEnumerationBackoff();
        drainsPendingCallbacksAfterStop();
        waitsForHandlerSideStopBeforeQuitting();
        closesQueuedErrorAfterSynchronousFailureAndStop();
        System.out.println("PASS: " + assertionCount + " camera recovery assertions");
    }

    private static void closesFailureBeforeOpened() {
        final CameraRecoveryController<FakeDevice> controller = createController();
        final CameraRecoveryController.Attempt<FakeDevice> attempt = controller.beginAttempt("0", 0);
        final FakeDevice failedDevice = new FakeDevice();
        // Both onError and onDisconnected provide a device even without a prior onOpened.
        check(controller.receiveDevice(attempt, failedDevice), "pre-open failure is current");
        check(controller.recordFailure(attempt, 0) == 1500, "first failure waits 1500ms");
        controller.retire(attempt);
        check(failedDevice.closeCount == 1, "pre-open callback device closes");
        check(controller.beginAttempt("0", 5000) == null, "backoff expiry cannot bypass onClosed");
        controller.onClosed(attempt, failedDevice);
        check(controller.beginAttempt("0", 5000) != null, "closed ID can be retried");
    }

    private static void waitsForCloseAfterOpened() {
        final CameraRecoveryController<FakeDevice> controller = createController();
        final CameraRecoveryController.Attempt<FakeDevice> attempt = controller.beginAttempt("0", 0);
        final FakeDevice openedDevice = new FakeDevice();
        controller.receiveDevice(attempt, openedDevice);
        check(controller.receiveDevice(attempt, openedDevice), "later failure uses same owner");
        controller.retire(attempt);
        controller.retire(attempt);
        check(openedDevice.closeCount == 1, "repeated cleanup does not close twice");
        check(controller.ownsCamera("0"), "closing camera remains reserved");
        controller.onClosed(attempt, openedDevice);
        check(!controller.ownsCamera("0"), "onClosed releases ownership");
    }

    private static void closesCancelledPendingOpen() {
        final CameraRecoveryController<FakeDevice> controller = createController();
        final CameraRecoveryController.Attempt<FakeDevice> attempt = controller.beginAttempt("1", 0);
        controller.retire(attempt);
        check(controller.beginAttempt("1", 60000) == null, "pending cancelled open cannot overlap");
        final FakeDevice lateDevice = new FakeDevice();
        check(!controller.receiveDevice(attempt, lateDevice), "late open cannot start a session");
        check(lateDevice.closeCount == 1, "late device is immediately closed");
        check(controller.beginAttempt("1", 60000) == null, "must still wait for close confirmation");
        controller.onClosed(attempt, lateDevice);
        check(controller.beginAttempt("1", 60000) != null, "replacement allowed after confirmation");
    }

    private static void isolatesLateCallbackAfterSynchronousFailure() {
        final CameraRecoveryController<FakeDevice> controller = createController();
        final CameraRecoveryController.Attempt<FakeDevice> previous = controller.beginAttempt("2", 0);
        controller.recordFailure(previous, 0);
        controller.onSynchronousOpenFailure(previous);
        check(!controller.ownsCamera("2"), "thrown open without device does not wait forever");
        final CameraRecoveryController.Attempt<FakeDevice> replacement =
            controller.beginAttempt("2", 1500);
        final FakeDevice replacementDevice = new FakeDevice();
        controller.receiveDevice(replacement, replacementDevice);
        final FakeDevice previousDevice = new FakeDevice();
        check(!controller.receiveDevice(previous, previousDevice), "old queued error is stale");
        check(previousDevice.closeCount == 1, "old error device closes");
        controller.onClosed(previous, previousDevice);
        check(controller.isCurrent(replacement), "old onClosed cannot remove replacement owner");
        check(replacementDevice.closeCount == 0, "old cleanup leaves replacement device open");
    }

    private static void preventsOverlapWhenEnumerationShifts() {
        final CameraRecoveryController<FakeDevice> controller = createController();
        final CameraRecoveryController.Attempt<FakeDevice> first = controller.beginAttempt("A", 0);
        final CameraRecoveryController.Attempt<FakeDevice> second = controller.beginAttempt("B", 0);
        final CameraRecoveryController.Attempt<FakeDevice> third = controller.beginAttempt("C", 0);
        final FakeDevice secondDevice = new FakeDevice();
        controller.receiveDevice(second, secondDevice);
        // A disappears: B moves to slot 0, C to slot 1. C is still awaiting its open callback.
        controller.retire(first);
        controller.retire(second);
        controller.retire(third);
        check(controller.beginAttempt("B", 0) == null, "shift cannot reacquire closing B");
        check(controller.beginAttempt("C", 0) == null, "shift cannot reacquire pending C");
        controller.onClosed(second, secondDevice);
        final CameraRecoveryController.Attempt<FakeDevice> newSecond = controller.beginAttempt("B", 0);
        check(newSecond != null, "B may reopen once released");
        final FakeDevice thirdDevice = new FakeDevice();
        controller.receiveDevice(third, thirdDevice);
        check(thirdDevice.closeCount == 1, "displaced pending C closes when returned");
        controller.onClosed(third, thirdDevice);
        check(controller.beginAttempt("C", 0) != null, "C may reopen once released");
        check(controller.isCurrent(newSecond), "another ID closing does not retire healthy B");
    }

    private static void preservesBackoffAcrossNotificationsAndResetsOnJpeg() {
        final CameraRecoveryController<FakeDevice> controller = createController();
        final CameraRecoveryController.Attempt<FakeDevice> failed = controller.beginAttempt("0", 100);
        controller.recordFailure(failed, 100);
        controller.onSynchronousOpenFailure(failed);
        for (long nowMs = 200; nowMs < 1600; nowMs += 100) {
            controller.requestScan(nowMs, 0);
            controller.onScanStarted();
            check(controller.beginAttempt("0", nowMs) == null, "refresh cannot bypass ID cooldown");
        }
        check(controller.beginAttempt("1", 200) != null, "unrelated camera is not delayed");
        final CameraRecoveryController.Attempt<FakeDevice> retry = controller.beginAttempt("0", 1600);
        final FakeDevice retryDevice = new FakeDevice();
        controller.receiveDevice(retry, retryDevice);
        check(controller.recordFailure(retry, 1600) == 3000, "onOpened alone does not reset backoff");
        controller.retire(retry);
        controller.onClosed(retry, retryDevice);
        final CameraRecoveryController<FakeDevice> jpegController = createController();
        final CameraRecoveryController.Attempt<FakeDevice> oldFailure = jpegController.beginAttempt("0", 0);
        jpegController.recordFailure(oldFailure, 0);
        jpegController.onSynchronousOpenFailure(oldFailure);
        final CameraRecoveryController.Attempt<FakeDevice> producing = jpegController.beginAttempt("0", 1500);
        jpegController.onFirstJpeg(producing);
        check(jpegController.recordFailure(producing, 1600) == 1500, "real JPEG resets failure history");
        jpegController.onFirstJpeg(oldFailure);
        check(jpegController.retryDelay("0", 1600) == 1500, "stale JPEG cannot reset current backoff");
    }

    private static void capsBackoffWithoutDoubleCounting() {
        final CameraRecoveryController<FakeDevice> controller = createController();
        long nowMs = 0;
        final long[] delays = {1500, 3000, 6000, 12000, 24000, 30000, 30000};
        for (final long expectedDelay : delays) {
            final CameraRecoveryController.Attempt<FakeDevice> attempt = controller.beginAttempt("3", nowMs);
            check(attempt != null, "retry begins after deadline");
            check(controller.recordFailure(attempt, nowMs) == expectedDelay, "bounded exponential backoff");
            check(controller.recordFailure(attempt, nowMs) == expectedDelay, "duplicate failure is counted once");
            controller.onSynchronousOpenFailure(attempt);
            nowMs += expectedDelay;
        }
    }

    private static void preservesEarliestScanAndEnumerationBackoff() {
        final CameraRecoveryController<FakeDevice> controller = createController();
        check(controller.requestScan(0, 300), "first scan is scheduled");
        check(!controller.requestScan(100, 300), "availability flood cannot postpone pending scan");
        check(controller.scheduledScanAtMs() == 300, "original deadline retained");
        check(controller.requestScan(100, 0), "earlier scan can advance deadline");
        controller.onScanStarted();
        check(controller.onEnumerationFailure(100) == 1500, "transient scan error has retry");
        check(controller.requestScan(100, 1500), "failed scan schedules own recovery");
        check(!controller.requestScan(200, 0), "refresh cannot bypass enumeration cooldown");
        check(controller.scheduledScanAtMs() == 1600, "enumeration deadline enforced");
        controller.onScanStarted();
        check(controller.enumerationDelay(1600) == 0, "enumeration retry becomes due");
        controller.onEnumerationSuccess();
        check(controller.onEnumerationFailure(1600) == 1500, "successful enumeration resets scan backoff");
    }

    private static void drainsPendingCallbacksAfterStop() {
        final CameraRecoveryController<FakeDevice> controller = createController();
        final CameraRecoveryController.Attempt<FakeDevice> pending = controller.beginAttempt("0", 0);
        controller.stop();
        check(controller.hasOwnedAttempts(), "stop must retain pending callback ownership");
        check(!controller.canFinishShutdown(), "stop cannot quit before pending open returns");
        check(!controller.requestScan(0, 0), "stop rejects scheduling");
        check(controller.beginAttempt("1", 60000) == null, "stop rejects new opens");
        final FakeDevice lateDevice = new FakeDevice();
        check(!controller.receiveDevice(pending, lateDevice), "post-stop callback cannot be activated");
        check(lateDevice.closeCount == 1, "post-stop callback closes its device");
        controller.onClosed(pending, lateDevice);
        check(!controller.hasOwnedAttempts(), "all callbacks drained allows handler shutdown");
        check(controller.canFinishShutdown(), "completed handler-side stop can quit");
    }

    private static void waitsForHandlerSideStopBeforeQuitting() {
        final CameraRecoveryController<FakeDevice> controller = createController();
        final CameraRecoveryController.Attempt<FakeDevice> attempt = controller.beginAttempt("0", 0);
        final FakeDevice device = new FakeDevice();
        controller.receiveDevice(attempt, device);
        controller.retire(attempt);
        // The main thread has requested stop but has not yet posted unregister/cleanup.
        controller.onClosed(attempt, device);
        check(!controller.hasOwnedAttempts(), "last close may precede stop cleanup submission");
        check(!controller.canFinishShutdown(), "last close alone must not quit callback handler");
        controller.stop();
        check(controller.canFinishShutdown(), "handler-side cleanup authorizes termination");
    }

    private static void closesQueuedErrorAfterSynchronousFailureAndStop() {
        final CameraRecoveryController<FakeDevice> controller = createController();
        final CameraRecoveryController.Attempt<FakeDevice> attempt = controller.beginAttempt("0", 0);
        controller.recordFailure(attempt, 0);
        controller.onSynchronousOpenFailure(attempt);
        controller.stop();
        // quitSafely drains already queued error callbacks even when openCamera threw.
        final FakeDevice failedDevice = new FakeDevice();
        check(!controller.receiveDevice(attempt, failedDevice), "queued post-stop error is stale");
        check(failedDevice.closeCount == 1, "queued post-stop error device is closed");
        controller.receiveDevice(attempt, failedDevice);
        check(failedDevice.closeCount == 1, "duplicate stale error does not close twice");
        check(controller.beginAttempt("0", 60000) == null, "queued errors never restart stopped service");
    }

    private static void check(final boolean condition, final String description) {
        assertionCount++;
        if (!condition) throw new AssertionError(description);
    }
}
