package com.serenegiant.usbcameratest7;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Camera-handler-confined ownership and retry policy, independent of Android callbacks. */
final class CameraRecoveryController<Device> {
    interface DeviceCloser<Device> {
        void close(Device device);
    }

    static final long INITIAL_RETRY_MS = 1500;
    static final long MAX_RETRY_MS = 30000;

    static final class Attempt<Device> {
        final String cameraId;
        final long sequence;
        private Device device;
        private boolean retired;
        private boolean closeRequested;
        private boolean closed;
        private boolean failureRecorded;

        private Attempt(final String identifier, final long attemptSequence) {
            cameraId = identifier;
            sequence = attemptSequence;
        }
    }

    private static final class RetryWindow {
        private long delayMs;
        private long nextAttemptAtMs;

        long fail(final long nowMs) {
            delayMs = delayMs == 0 ? INITIAL_RETRY_MS : Math.min(MAX_RETRY_MS, delayMs * 2);
            nextAttemptAtMs = nowMs + delayMs;
            return delayMs;
        }

        long remaining(final long nowMs) {
            return Math.max(0, nextAttemptAtMs - nowMs);
        }

        void reset() {
            delayMs = 0;
            nextAttemptAtMs = 0;
        }
    }

    private final DeviceCloser<Device> deviceCloser;
    private final Map<String, Attempt<Device>> ownedAttempts =
        new HashMap<String, Attempt<Device>>();
    private final Map<String, RetryWindow> cameraRetries = new HashMap<String, RetryWindow>();
    private final RetryWindow enumerationRetry = new RetryWindow();
    private long attemptSequence;
    private long scheduledScanAtMs = Long.MAX_VALUE;
    private boolean stopped;

    CameraRecoveryController(final DeviceCloser<Device> closer) {
        deviceCloser = closer;
    }

    Attempt<Device> beginAttempt(final String cameraId, final long nowMs) {
        if (stopped || ownedAttempts.containsKey(cameraId) || retryDelay(cameraId, nowMs) > 0) {
            return null;
        }
        final Attempt<Device> attempt = new Attempt<Device>(cameraId, ++attemptSequence);
        ownedAttempts.put(cameraId, attempt);
        return attempt;
    }

    boolean ownsCamera(final String cameraId) {
        return ownedAttempts.containsKey(cameraId);
    }

    boolean hasOwnedAttempts() {
        return !ownedAttempts.isEmpty();
    }

    boolean canFinishShutdown() {
        return stopped && ownedAttempts.isEmpty();
    }

    boolean isCurrent(final Attempt<Device> attempt) {
        return !stopped && attempt != null && !attempt.retired
            && ownedAttempts.get(attempt.cameraId) == attempt;
    }

    boolean receiveDevice(final Attempt<Device> attempt, final Device device) {
        if (attempt.device == null) {
            attempt.device = device;
        } else if (attempt.device != device) {
            deviceCloser.close(device);
            return false;
        }
        if (!isCurrent(attempt)) {
            retire(attempt);
            return false;
        }
        return true;
    }

    void retire(final Attempt<Device> attempt) {
        if (attempt == null) return;
        attempt.retired = true;
        if (attempt.device != null && !attempt.closeRequested && !attempt.closed) {
            attempt.closeRequested = true;
            deviceCloser.close(attempt.device);
        }
        // A pending open still owns the ID. Its eventual callback must close before reuse.
    }

    void onClosed(final Attempt<Device> attempt, final Device device) {
        if (attempt.device != null && attempt.device != device) return;
        attempt.closed = true;
        attempt.retired = true;
        if (ownedAttempts.get(attempt.cameraId) == attempt) {
            ownedAttempts.remove(attempt.cameraId);
        }
    }

    void onSynchronousOpenFailure(final Attempt<Device> attempt) {
        retire(attempt);
        // A thrown openCamera call has no successfully acquired device. Some Android versions
        // also enqueue an error callback: receiveDevice still closes that stale object later.
        if (attempt.device == null && ownedAttempts.get(attempt.cameraId) == attempt) {
            ownedAttempts.remove(attempt.cameraId);
        }
    }

    long recordFailure(final Attempt<Device> attempt, final long nowMs) {
        if (attempt.failureRecorded) return retryDelay(attempt.cameraId, nowMs);
        attempt.failureRecorded = true;
        RetryWindow retry = cameraRetries.get(attempt.cameraId);
        if (retry == null) {
            retry = new RetryWindow();
            cameraRetries.put(attempt.cameraId, retry);
        }
        return retry.fail(nowMs);
    }

    long retryDelay(final String cameraId, final long nowMs) {
        final RetryWindow retry = cameraRetries.get(cameraId);
        return retry == null ? 0 : retry.remaining(nowMs);
    }

    void onFirstJpeg(final Attempt<Device> attempt) {
        if (isCurrent(attempt)) cameraRetries.remove(attempt.cameraId);
    }

    boolean requestScan(final long nowMs, final long delayMs) {
        if (stopped) return false;
        final long requestedAtMs = nowMs + Math.max(Math.max(0, delayMs),
            enumerationRetry.remaining(nowMs));
        if (requestedAtMs >= scheduledScanAtMs) return false;
        scheduledScanAtMs = requestedAtMs;
        return true;
    }

    long scheduledScanAtMs() {
        return scheduledScanAtMs;
    }

    void onScanStarted() {
        scheduledScanAtMs = Long.MAX_VALUE;
    }

    long enumerationDelay(final long nowMs) {
        return enumerationRetry.remaining(nowMs);
    }

    long onEnumerationFailure(final long nowMs) {
        return enumerationRetry.fail(nowMs);
    }

    void onEnumerationSuccess() {
        enumerationRetry.reset();
    }

    void stop() {
        stopped = true;
        scheduledScanAtMs = Long.MAX_VALUE;
        for (final Attempt<Device> attempt :
            new ArrayList<Attempt<Device>>(ownedAttempts.values())) {
            retire(attempt);
        }
    }
}
