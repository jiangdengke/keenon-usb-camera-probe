package com.serenegiant.usbcameratest7;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Owns Camera2 capture and network streaming independently from the Activity lifecycle.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public final class CameraStreamingService extends Service {
    static final String ACTION_START =
        "com.serenegiant.usbcameratest7.action.START_CAMERA_STREAMING";
    static final String ACTION_STOP =
        "com.serenegiant.usbcameratest7.action.STOP_CAMERA_STREAMING";
    static final String EXTRA_PUSH_ENABLED = "push_enabled";
    static final String EXTRA_PUSH_TARGET_URL = "push_target_url";
    static final String EXTRA_PUSH_SLOT_COUNT = "push_slot_count";
    static final String EXTRA_PUSH_INTERVAL_MS = "push_interval_ms";
    static final String EXTRA_CAMERA_SLOT_COUNT = "camera_slot_count";

    private static final String TAG = "KeenonCameraService";
    private static final String NOTIFICATION_CHANNEL_ID = "keenon_camera_streaming";
    private static final String NOTIFICATION_CHANNEL_NAME = "摄像头持续推流";
    private static final int NOTIFICATION_ID = 9201;
    private static final int MAX_CAMERA_SLOTS = 4;
    private static final int DEFAULT_CAMERA_SLOT_COUNT = 4;
    private static final int STREAM_PORT = 8080;
    private static final int TARGET_WIDTH = 640;
    private static final int TARGET_HEIGHT = 480;
    private static final int MAX_LOG_LINES = 220;
    private static final int SURFACE_READBACK_JPEG_QUALITY = 60;

    private final LocalBinder mBinder = new LocalBinder();
    private final Object mLifecycleLock = new Object();
    private final Object mLogLock = new Object();
    private final List<String> mLogLines = new ArrayList<String>();
    private final CameraSlotState[] mCameraSlots = new CameraSlotState[MAX_CAMERA_SLOTS];
    private final CameraRecoveryController<CameraDevice> mCameraRecovery =
        new CameraRecoveryController<CameraDevice>(
            new CameraRecoveryController.DeviceCloser<CameraDevice>() {
                @Override
                public void close(final CameraDevice cameraDevice) {
                    try {
                        cameraDevice.close();
                    } catch (final RuntimeException exception) {
                        log("Camera2关闭异常，cameraId=" + cameraDevice.getId()
                            + "，等待关闭确认，不重复打开：" + describeCameraException(exception));
                    }
                }
            });

    private volatile HandlerThread mCameraThread;
    private volatile Handler mCameraHandler;
    private CameraManager mCameraManager;
    private CameraSurfaceFrameReader mSurfaceFrameReader;
    private CameraStreamHub mStreamHub;
    private CameraPushClient mPushClient;
    private volatile boolean mStreamingStarted;
    private volatile int mActiveSlotCount;
    private volatile int mCameraSlotLimit = DEFAULT_CAMERA_SLOT_COUNT;
    private String mLastCameraEnumeration;
    private boolean mPushEnabled = PushSettings.DEFAULT_ENABLED;
    private String mPushTargetUrl = PushSettings.DEFAULT_TARGET_URL;
    private int mPushSlotCount = PushSettings.DEFAULT_SLOT_COUNT;
    private int mPushIntervalMs = PushSettings.DEFAULT_INTERVAL_MS;

    private final Runnable mCameraReconcileRunnable = new Runnable() {
        @Override
        public void run() {
            mCameraRecovery.onScanStarted();
            reconcileCameraSlots();
        }
    };

    private final CameraManager.AvailabilityCallback mAvailabilityCallback =
        new CameraManager.AvailabilityCallback() {
            @Override
            public void onCameraAvailable(final String cameraId) {
                scheduleCameraReconcile(300);
            }
        };

    public final class LocalBinder extends Binder {
        CameraStreamingService getService() {
            return CameraStreamingService.this;
        }
    }

    static final class SlotSnapshot {
        final int slotIndex;
        final String cameraId;
        final String status;
        final long frameCount;
        final float fps;
        final int width;
        final int height;
        final CameraPushClient.JpegFrame latestFrame;

        SlotSnapshot(final int slotIndex, final String cameraId, final String status,
            final long frameCount, final float fps, final int width, final int height,
            final CameraPushClient.JpegFrame latestFrame) {
            this.slotIndex = slotIndex;
            this.cameraId = cameraId;
            this.status = status;
            this.frameCount = frameCount;
            this.fps = fps;
            this.width = width;
            this.height = height;
            this.latestFrame = latestFrame;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        for (int slotIndex = 0; slotIndex < mCameraSlots.length; slotIndex++) {
            mCameraSlots[slotIndex] = new CameraSlotState(slotIndex);
        }
        createNotificationChannelIfNeeded();
        startForeground(NOTIFICATION_ID, buildForegroundNotification("正在启动摄像头推流"));
        log("前台推流服务已创建，Activity切到后台后仍会继续运行");
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            log("收到停止前台推流服务请求");
            stopSelf();
            return START_NOT_STICKY;
        }

        final boolean pushConfigurationChanged = configureFromIntent(intent);
        startOrRefreshStreaming(pushConfigurationChanged);
        return START_REDELIVER_INTENT;
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        stopStreamingResources();
        stopForeground(true);
        log("前台推流服务已销毁");
        super.onDestroy();
    }

    int getActiveSlotCount() {
        return mActiveSlotCount;
    }

    boolean isStreamingStarted() {
        return mStreamingStarted;
    }

    boolean areActiveSlotsProducingJpeg() {
        final int activeSlotCount = mActiveSlotCount;
        if (activeSlotCount == 0) return false;
        for (int slotIndex = 0; slotIndex < activeSlotCount; slotIndex++) {
            final CameraSlotState slot = mCameraSlots[slotIndex];
            if (!"STREAMING".equals(slot.status) || slot.jpegFrameCount == 0) return false;
        }
        return true;
    }

    boolean isPushConnected() {
        final CameraPushClient pushClient = mPushClient;
        return pushClient != null && pushClient.isConnected();
    }

    String getPushStatus() {
        if (!mPushEnabled) return "已关闭";
        return isPushConnected() ? "已连接" : "连接中";
    }

    String getPushTargetUrl() {
        return mPushTargetUrl;
    }

    String getHttpBaseUrl() {
        final CameraStreamHub streamHub = mStreamHub;
        return streamHub != null ? streamHub.getBaseUrl() : "http://机器人IP:" + STREAM_PORT;
    }

    SlotSnapshot getSlotSnapshot(final int slotIndex) {
        if (slotIndex < 0 || slotIndex >= mActiveSlotCount) return null;
        final CameraSlotState slot = mCameraSlots[slotIndex];
        final CameraStreamHub streamHub = mStreamHub;
        final CameraPushClient.JpegFrame latestFrame = streamHub != null
            ? streamHub.getLatestJpegFrame(slotIndex) : null;
        return new SlotSnapshot(slotIndex, slot.cameraId, slot.status, slot.frameCount,
            slot.calculateFps(), slot.width, slot.height, latestFrame);
    }

    String getStatusSummary() {
        final StringBuilder summary = new StringBuilder();
        summary.append("后台推流服务=").append(mStreamingStarted ? "运行中" : "未启动");
        summary.append(" Camera2=").append(countOpenedSlots()).append('/').append(mActiveSlotCount);
        final CameraStreamHub streamHub = mStreamHub;
        summary.append(" HTTP=").append(streamHub != null && streamHub.isRunning()
            ? "已启动" : "不可用");
        summary.append(" WebSocket=").append(getPushStatus());
        return summary.toString();
    }

    String getRecentLogText() {
        synchronized (mLogLock) {
            final StringBuilder logText = new StringBuilder();
            for (final String logLine : mLogLines) {
                logText.append(logLine).append('\n');
            }
            return logText.toString();
        }
    }

    private boolean configureFromIntent(final Intent intent) {
        // The boot start carries no extras, so it falls back to the saved push settings.
        final Intent startIntent = intent != null ? intent : new Intent();
        final PushSettings savedSettings = PushSettings.load(this);
        final boolean previousPushEnabled = mPushEnabled;
        final String previousPushTargetUrl = mPushTargetUrl;
        final int previousPushSlotCount = mPushSlotCount;
        final int previousPushIntervalMs = mPushIntervalMs;
        mCameraSlotLimit = clamp(startIntent.getIntExtra(EXTRA_CAMERA_SLOT_COUNT,
            DEFAULT_CAMERA_SLOT_COUNT), 1, MAX_CAMERA_SLOTS);
        mPushEnabled = startIntent.getBooleanExtra(EXTRA_PUSH_ENABLED, savedSettings.enabled);
        mPushTargetUrl = normalizeText(startIntent.getStringExtra(EXTRA_PUSH_TARGET_URL),
            savedSettings.targetUrl);
        mPushSlotCount = clamp(startIntent.getIntExtra(EXTRA_PUSH_SLOT_COUNT,
            savedSettings.slotCount), 1, MAX_CAMERA_SLOTS);
        mPushIntervalMs = Math.max(PushSettings.MIN_INTERVAL_MS,
            startIntent.getIntExtra(EXTRA_PUSH_INTERVAL_MS, savedSettings.intervalMs));
        return previousPushEnabled != mPushEnabled
            || !previousPushTargetUrl.equals(mPushTargetUrl)
            || previousPushSlotCount != mPushSlotCount
            || previousPushIntervalMs != mPushIntervalMs;
    }

    private void startOrRefreshStreaming(final boolean pushConfigurationChanged) {
        synchronized (mLifecycleLock) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                log("Camera2后台推流不可用：系统版本低于Android 5.0");
                updateForegroundNotification("系统版本不支持 Camera2");
                stopSelf();
                return;
            }
            if (!hasCameraPermission()) {
                log("Camera2后台推流未启动：缺少CAMERA权限，请返回App授权");
                updateForegroundNotification("等待摄像头权限");
                stopSelf();
                return;
            }

            if (mStreamingStarted) {
                if (mStreamHub != null && !mStreamHub.isRunning()) {
                    mStreamHub.start();
                }
                if (pushConfigurationChanged) {
                    restartPushClient();
                }
                log("前台推流服务已在运行，重新扫描并恢复不可用摄像头");
                scheduleCameraReconcile(0);
                return;
            }

            startCameraThread();
            mCameraManager = (CameraManager)getSystemService(CAMERA_SERVICE);
            mStreamHub = new CameraStreamHub(MAX_CAMERA_SLOTS, STREAM_PORT,
                new CameraStreamHub.LogSink() {
                    @Override
                    public void log(final String message) {
                        CameraStreamingService.this.log(message);
                    }
                }, new CameraStreamHub.LogSource() {
                    @Override
                    public String getRecentLogText() {
                        return CameraStreamingService.this.getRecentLogText();
                    }
                });
            mStreamHub.start();
            configurePushClient();
            if (mPushClient != null) {
                mPushClient.start();
            }
            mStreamingStarted = true;
            if (mCameraManager != null) {
                mCameraManager.registerAvailabilityCallback(mAvailabilityCallback, mCameraHandler);
            }
            scheduleCameraReconcile(0);
        }
    }

    private void restartPushClient() {
        if (mPushClient != null) {
            mPushClient.stop();
            mPushClient = null;
        }
        configurePushClient();
        if (mPushClient != null) {
            mPushClient.start();
        }
    }

    private void configurePushClient() {
        if (!mPushEnabled) {
            mPushClient = null;
            log("WebSocket主动推流已关闭，HTTP拉流仍保持运行");
            return;
        }
        mPushClient = new CameraPushClient(mPushTargetUrl, mPushSlotCount, mPushIntervalMs,
            new CameraPushClient.FrameSource() {
                @Override
                public CameraPushClient.JpegFrame getLatestJpegFrame(final int slotIndex) {
                    final CameraStreamHub streamHub = mStreamHub;
                    return streamHub != null ? streamHub.getLatestJpegFrame(slotIndex) : null;
                }
            }, new CameraPushClient.LogSink() {
                @Override
                public void log(final String message) {
                    CameraStreamingService.this.log(message);
                }
            });
        log("WebSocket主动推流已启用：目标=" + mPushTargetUrl
            + "，路数=" + mPushSlotCount + "，间隔=" + mPushIntervalMs + "ms");
    }

    private void scheduleCameraReconcile(final long delayMs) {
        final Handler cameraHandler = mCameraHandler;
        if (!mStreamingStarted || cameraHandler == null) return;
        if (Thread.currentThread() != mCameraThread) {
            cameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    scheduleCameraReconcile(delayMs);
                }
            });
            return;
        }
        if (mCameraRecovery.requestScan(SystemClock.uptimeMillis(), delayMs)) {
            cameraHandler.removeCallbacks(mCameraReconcileRunnable);
            cameraHandler.postAtTime(mCameraReconcileRunnable,
                mCameraRecovery.scheduledScanAtMs());
        }
    }

    @SuppressLint("MissingPermission")
    private void reconcileCameraSlots() {
        if (!mStreamingStarted) return;
        final long enumerationDelay = mCameraRecovery.enumerationDelay(SystemClock.uptimeMillis());
        if (enumerationDelay > 0) {
            scheduleCameraReconcile(enumerationDelay);
            return;
        }
        if (!hasCameraPermission()) {
            log("Camera2后台扫描已暂停：缺少CAMERA权限，请返回App授权");
            return;
        }
        if (mCameraManager == null) {
            log("Camera2扫描失败：CameraManager为空");
            return;
        }
        try {
            final String[] cameraIds = mCameraManager.getCameraIdList();
            mCameraRecovery.onEnumerationSuccess();
            final int desiredSlotCount = Math.min(cameraIds.length, mCameraSlotLimit);
            // Retire every displaced owner before acquiring any replacement ID.
            for (int slotIndex = 0; slotIndex < mCameraSlots.length; slotIndex++) {
                final CameraSlotState slot = mCameraSlots[slotIndex];
                if (slotIndex >= desiredSlotCount
                    || (slot.cameraAttempt != null && !cameraIds[slotIndex].equals(slot.cameraId))) {
                    closeCameraSlot(slot, "CLOSED");
                }
            }
            mActiveSlotCount = desiredSlotCount;
            if (mStreamHub != null) {
                mStreamHub.setActiveSlotCount(mActiveSlotCount);
            }
            final String enumeration = Arrays.toString(cameraIds) + "/limit=" + mCameraSlotLimit;
            if (!enumeration.equals(mLastCameraEnumeration)) {
                mLastCameraEnumeration = enumeration;
                log("Camera2后台扫描结果：cameraIds=" + Arrays.toString(cameraIds)
                    + "，本轮打开=" + mActiveSlotCount);
                updateForegroundNotification("正在推送 " + mActiveSlotCount + " 路摄像头");
            }
            for (int slotIndex = 0; slotIndex < mActiveSlotCount; slotIndex++) {
                final CameraSlotState slot = mCameraSlots[slotIndex];
                final String cameraId = cameraIds[slotIndex];
                final boolean matchingCamera = cameraId.equals(slot.cameraId);
                final boolean activeCamera = "OPENING".equals(slot.status)
                    || "OPEN".equals(slot.status) || "STREAMING".equals(slot.status);
                if (!matchingCamera || !activeCamera) {
                    final long retryDelay = mCameraRecovery.retryDelay(cameraId,
                        SystemClock.uptimeMillis());
                    if (retryDelay > 0) {
                        scheduleCameraReconcile(retryDelay);
                    } else if (!mCameraRecovery.ownsCamera(cameraId)) {
                        openCameraSlot(slot, cameraId);
                    }
                }
            }
        } catch (final SecurityException securityException) {
            scheduleEnumerationRetry(securityException);
        } catch (final CameraAccessException cameraAccessException) {
            scheduleEnumerationRetry(cameraAccessException);
        }
    }

    private void scheduleEnumerationRetry(final Exception exception) {
        final long retryDelay = mCameraRecovery.onEnumerationFailure(SystemClock.uptimeMillis());
        log("Camera2后台扫描失败，stage=enumerate，" + describeCameraException(exception)
            + "，重试等待=" + retryDelay + "ms");
        scheduleCameraReconcile(retryDelay);
    }

    @SuppressLint("MissingPermission")
    private void openCameraSlot(final CameraSlotState slot, final String cameraId) {
        closeCameraSlot(slot, "CLOSED");
        final CameraRecoveryController.Attempt<CameraDevice> attempt =
            mCameraRecovery.beginAttempt(cameraId, SystemClock.uptimeMillis());
        if (attempt == null) return;
        slot.cameraAttempt = attempt;
        final long openGeneration = ++slot.openGeneration;
        slot.cameraId = cameraId;
        slot.status = "OPENING";
        slot.resetFrameMetrics();
        String failureStage = "createSurface";
        try {
            slot.width = TARGET_WIDTH;
            slot.height = TARGET_HEIGHT;
            final CameraSurfaceFrameReader surfaceFrameReader = getOrCreateSurfaceFrameReader();
            final CameraSurfaceFrameReader.FrameTarget frameTarget =
                surfaceFrameReader.createTarget(slot.slotIndex,
                    new CameraSurfaceFrameReader.FrameListener() {
                @Override
                public int getCaptureIntervalMs() {
                    return mPushIntervalMs;
                }

                @Override
                public void onFrameAvailable(final long timestampMs) {
                    if (!isCurrentGeneration(slot, cameraId, openGeneration)) return;
                    slot.recordFrame(timestampMs);
                    if (slot.frameCount == 1) {
                        log("Camera2后台：第" + (slot.slotIndex + 1)
                            + "路官方640x480 Surface首帧已到达");
                    }
                }

                @Override
                public void onJpegFrame(final byte[] jpegData, final int width,
                    final int height, final long timestampMs) {
                    if (!isCurrentGeneration(slot, cameraId, openGeneration)) return;
                    final CameraStreamHub streamHub = mStreamHub;
                    if (streamHub != null) {
                        streamHub.onCamera2JpegFrame(slot.slotIndex, jpegData, width, height);
                    }
                    slot.jpegFrameCount++;
                    if (slot.jpegFrameCount == 1) {
                        mCameraRecovery.onFirstJpeg(attempt);
                        log("Camera2后台：第" + (slot.slotIndex + 1)
                            + "路官方640x480 Surface首帧JPEG已生成，大小="
                            + jpegData.length + "字节");
                    }
                }

                @Override
                public void onReadbackError(final String message, final Throwable throwable) {
                    if (!isCurrentGeneration(slot, cameraId, openGeneration)) return;
                    final long now = System.currentTimeMillis();
                    if (now - slot.lastReadbackErrorLogMs < 5000) return;
                    slot.lastReadbackErrorLogMs = now;
                    Log.e(TAG, message, throwable);
                    log(message + "：" + throwable.getMessage());
                }
            });
            slot.frameTarget = frameTarget;
            log("Camera2后台：准备打开第" + (slot.slotIndex + 1) + "路，cameraId="
                + cameraId + "，attempt=" + attempt.sequence
                + "，官方SurfaceTexture=" + TARGET_WIDTH + "x" + TARGET_HEIGHT);
            failureStage = "openCamera";
            mCameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(final CameraDevice cameraDevice) {
                    if (!mCameraRecovery.receiveDevice(attempt, cameraDevice)) return;
                    if (!isCurrentGeneration(slot, cameraId, openGeneration)) {
                        mCameraRecovery.retire(attempt);
                        return;
                    }
                    slot.cameraDevice = cameraDevice;
                    slot.status = "OPEN";
                    log("Camera2后台：第" + (slot.slotIndex + 1)
                        + "路open成功，cameraId=" + cameraId);
                    createCaptureSession(slot, cameraDevice, frameTarget, openGeneration);
                }

                @Override
                public void onDisconnected(final CameraDevice cameraDevice) {
                    if (!mCameraRecovery.receiveDevice(attempt, cameraDevice)) return;
                    if (!isCurrentGeneration(slot, cameraId, openGeneration)) {
                        mCameraRecovery.retire(attempt);
                        return;
                    }
                    failCameraSlot(slot, attempt, "onDisconnected", "设备已断开");
                }

                @Override
                public void onError(final CameraDevice cameraDevice, final int error) {
                    if (!mCameraRecovery.receiveDevice(attempt, cameraDevice)) return;
                    if (!isCurrentGeneration(slot, cameraId, openGeneration)) {
                        mCameraRecovery.retire(attempt);
                        return;
                    }
                    failCameraSlot(slot, attempt, "onError", "callbackError=" + error);
                }

                @Override
                public void onClosed(final CameraDevice cameraDevice) {
                    final boolean wasCurrent = isCurrentGeneration(slot, cameraId, openGeneration);
                    mCameraRecovery.onClosed(attempt, cameraDevice);
                    releaseFrameTarget(frameTarget);
                    if (wasCurrent) {
                        failCameraSlot(slot, attempt, "onClosed", "设备意外关闭");
                    }
                    log("Camera2关闭已确认，cameraId=" + cameraId + "，attempt=" + attempt.sequence);
                    scheduleCameraReconcile(0);
                    finishCameraShutdownIfIdle();
                }
            }, mCameraHandler);
        } catch (final Exception exception) {
            final CameraSurfaceFrameReader.FrameTarget failedTarget = slot.frameTarget;
            mCameraRecovery.onSynchronousOpenFailure(attempt);
            failCameraSlot(slot, attempt, failureStage, describeCameraException(exception));
            releaseFrameTarget(failedTarget);
        }
    }

    private void failCameraSlot(final CameraSlotState slot,
        final CameraRecoveryController.Attempt<CameraDevice> attempt,
        final String stage, final String reason) {
        if (attempt == null || slot.cameraAttempt != attempt) return;
        final long retryDelay = mCameraRecovery.recordFailure(attempt, SystemClock.uptimeMillis());
        log("Camera2失败：第" + (slot.slotIndex + 1) + "路，cameraId=" + attempt.cameraId
            + "，attempt=" + attempt.sequence + "，stage=" + stage + "，" + reason
            + "，重试等待=" + retryDelay + "ms");
        closeCameraSlot(slot, "OPEN FAILED: " + stage + " " + reason);
        scheduleCameraReconcile(retryDelay);
    }

    private static String describeCameraException(final Exception exception) {
        final String reason = exception instanceof CameraAccessException
            ? "，reason=" + ((CameraAccessException)exception).getReason() : "";
        return exception.getClass().getSimpleName() + reason + ": " + exception.getMessage();
    }

    private CameraSurfaceFrameReader getOrCreateSurfaceFrameReader() {
        if (mSurfaceFrameReader == null) {
            if (mCameraHandler == null) {
                throw new IllegalStateException("Camera线程未启动");
            }
            mSurfaceFrameReader = new CameraSurfaceFrameReader(TARGET_WIDTH, TARGET_HEIGHT,
                SURFACE_READBACK_JPEG_QUALITY, mCameraHandler);
            log("Camera2后台：官方640x480 SurfaceTexture离屏读回已初始化");
        }
        return mSurfaceFrameReader;
    }

    private boolean isCurrentGeneration(final CameraSlotState slot, final String cameraId,
        final long openGeneration) {
        return mStreamingStarted && slot.openGeneration == openGeneration
            && cameraId != null && cameraId.equals(slot.cameraId)
            && mCameraRecovery.isCurrent(slot.cameraAttempt);
    }

    private void createCaptureSession(final CameraSlotState slot,
        final CameraDevice cameraDevice,
        final CameraSurfaceFrameReader.FrameTarget frameTarget,
        final long openGeneration) {
        if (frameTarget == null || slot.frameTarget != frameTarget) {
            failCameraSlot(slot, slot.cameraAttempt, "validateSurface", "官方预览Surface为空");
            return;
        }
        final Surface previewSurface = frameTarget.getSurface();
        if (previewSurface == null || !previewSurface.isValid()) {
            failCameraSlot(slot, slot.cameraAttempt, "validateSurface", "官方预览Surface无效");
            return;
        }
        try {
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(final CameraCaptureSession session) {
                        if (!mStreamingStarted || slot.openGeneration != openGeneration
                            || slot.cameraDevice != cameraDevice
                            || slot.frameTarget != frameTarget) {
                            session.close();
                            return;
                        }
                        slot.captureSession = session;
                        try {
                            final CaptureRequest.Builder requestBuilder =
                                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            requestBuilder.addTarget(previewSurface);
                            session.setRepeatingRequest(requestBuilder.build(), null, null);
                            slot.status = "STREAMING";
                            slot.lastFpsTimestampMs = System.currentTimeMillis();
                            if (mStreamHub != null) {
                                mStreamHub.onSlotOpened(slot.slotIndex,
                                    "Camera2 id=" + slot.cameraId, slot.width, slot.height,
                                    "Camera2SurfaceTexture", slot.slotIndex + 1,
                                    0, 0, false, 0f,
                                    "官方SurfaceTexture 640x480后台采集", false);
                            }
                            log("Camera2后台：第" + (slot.slotIndex + 1)
                                + "路官方调用已启动，cameraId=" + slot.cameraId
                                + "，SurfaceTexture=" + TARGET_WIDTH + "x" + TARGET_HEIGHT
                                + "，request=TEMPLATE_PREVIEW，target=单Surface");
                        } catch (final Exception exception) {
                            failCameraSlot(slot, slot.cameraAttempt, "setRepeatingRequest",
                                describeCameraException(exception));
                        }
                    }

                    @Override
                    public void onConfigureFailed(final CameraCaptureSession session) {
                        session.close();
                        if (!mStreamingStarted || slot.openGeneration != openGeneration
                            || slot.cameraDevice != cameraDevice) return;
                        failCameraSlot(slot, slot.cameraAttempt, "onConfigureFailed",
                            "Camera2 session configure failed");
                    }
                }, mCameraHandler);
        } catch (final CameraAccessException cameraAccessException) {
            failCameraSlot(slot, slot.cameraAttempt, "createCaptureSession",
                describeCameraException(cameraAccessException));
        } catch (final RuntimeException runtimeException) {
            failCameraSlot(slot, slot.cameraAttempt, "createCaptureSession",
                describeCameraException(runtimeException));
        }
    }

    private void stopStreamingResources() {
        synchronized (mLifecycleLock) {
            mStreamingStarted = false;
            closeCameraResourcesOnCameraThread();
            mActiveSlotCount = 0;
            if (mPushClient != null) {
                mPushClient.stop();
                mPushClient = null;
            }
            if (mStreamHub != null) {
                mStreamHub.stop();
                mStreamHub = null;
            }
        }
    }

    private void closeCameraSlot(final CameraSlotState slot) {
        closeCameraSlot(slot, "CLOSED");
    }

    private void closeCameraSlot(final CameraSlotState slot, final String finalStatus) {
        if (slot == null) return;
        slot.openGeneration++;
        final CameraCaptureSession captureSession = slot.captureSession;
        final CameraRecoveryController.Attempt<CameraDevice> attempt = slot.cameraAttempt;
        final CameraSurfaceFrameReader.FrameTarget frameTarget = slot.frameTarget;
        slot.captureSession = null;
        slot.cameraDevice = null;
        slot.cameraAttempt = null;
        slot.frameTarget = null;
        slot.cameraId = null;
        slot.status = finalStatus;

        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
            } catch (final Exception ignored) {
            }
            try {
                captureSession.abortCaptures();
            } catch (final Exception ignored) {
            }
            try {
                captureSession.close();
            } catch (final RuntimeException exception) {
                log("Camera2 Session关闭异常：" + describeCameraException(exception));
            }
        }
        mCameraRecovery.retire(attempt);
        if (attempt == null) {
            releaseFrameTarget(frameTarget);
        }
        // A submitted open owns its Surface until onClosed; close() is asynchronous.
        final CameraStreamHub streamHub = mStreamHub;
        if (streamHub != null) {
            streamHub.onSlotClosed(slot.slotIndex, slot.status);
        }
    }

    private void releaseFrameTarget(final CameraSurfaceFrameReader.FrameTarget frameTarget) {
        if (frameTarget == null) return;
        try {
            frameTarget.release();
        } catch (final RuntimeException exception) {
            log("Camera2 Surface释放异常：" + describeCameraException(exception));
        }
    }

    private void closeCameraResourcesOnCameraThread() {
        final Handler cameraHandler = mCameraHandler;
        if (cameraHandler == null || Thread.currentThread() == mCameraThread) {
            closeCameraResourcesNow();
            return;
        }

        final CountDownLatch closeLatch = new CountDownLatch(1);
        cameraHandler.removeCallbacks(mCameraReconcileRunnable);
        final boolean cleanupPosted = cameraHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    closeCameraResourcesNow();
                } finally {
                    closeLatch.countDown();
                }
            }
        });
        if (!cleanupPosted) {
            log("Camera2后台：关闭任务无法提交，相机回调线程已退出");
            return;
        }
        try {
            if (!closeLatch.await(3, TimeUnit.SECONDS)) {
                log("Camera2后台：等待相机资源关闭超时");
            }
        } catch (final InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeCameraResourcesNow() {
        unregisterAvailabilityCallback();
        mCameraManager = null;
        closeAllCameraSlots();
        mCameraRecovery.stop();
        if (mCameraRecovery.hasOwnedAttempts()) {
            log("Camera2后台：已请求关闭，保留回调线程等待未完成的打开/关闭确认");
        }
        finishCameraShutdownIfIdle();
    }

    private void unregisterAvailabilityCallback() {
        if (mCameraManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        try {
            mCameraManager.unregisterAvailabilityCallback(mAvailabilityCallback);
        } catch (final Exception ignored) {
        }
    }

    private void closeAllCameraSlots() {
        for (final CameraSlotState slot : mCameraSlots) {
            closeCameraSlot(slot, "CLOSED");
        }
    }

    private void startCameraThread() {
        if (mCameraThread != null) return;
        mCameraThread = new HandlerThread("KeenonCameraStreaming");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());
    }

    private void finishCameraShutdownIfIdle() {
        // A last onClosed may race with the main thread posting cleanup. Only the camera-thread
        // stop() call, after unregistering availability, is allowed to authorize thread exit.
        if (mStreamingStarted || !mCameraRecovery.canFinishShutdown()) return;
        if (mSurfaceFrameReader != null) {
            try {
                mSurfaceFrameReader.release();
            } catch (final RuntimeException exception) {
                log("Camera2读回器释放异常：" + describeCameraException(exception));
            }
            mSurfaceFrameReader = null;
        }
        final HandlerThread cameraThread = mCameraThread;
        mCameraThread = null;
        mCameraHandler = null;
        if (cameraThread == null) return;
        // Do not discard late onOpened/onError callbacks before their device has been closed.
        cameraThread.quitSafely();
    }

    private boolean hasCameraPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
            || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        final NotificationManager notificationManager =
            (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager == null) return;
        final NotificationChannel notificationChannel = new NotificationChannel(
            NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW);
        notificationChannel.setDescription("保持机器人摄像头在后台持续推流");
        notificationChannel.setShowBadge(false);
        notificationManager.createNotificationChannel(notificationChannel);
    }

    private Notification buildForegroundNotification(final String contentText) {
        final Intent activityIntent = new Intent(this, MainActivity.class);
        final int pendingIntentFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            : PendingIntent.FLAG_UPDATE_CURRENT;
        final PendingIntent contentIntent = PendingIntent.getActivity(this, 0, activityIntent,
            pendingIntentFlags);

        final Notification.Builder notificationBuilder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            : new Notification.Builder(this);
        notificationBuilder
            .setSmallIcon(com.serenegiant.usbcameratest7.R.drawable.ic_stat_camera_streaming)
            .setContentTitle("机器人摄像头推流运行中")
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE);
        return notificationBuilder.build();
    }

    private void updateForegroundNotification(final String contentText) {
        final NotificationManager notificationManager =
            (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildForegroundNotification(contentText));
        }
    }

    private int countOpenedSlots() {
        int openedSlotCount = 0;
        for (int slotIndex = 0; slotIndex < mActiveSlotCount; slotIndex++) {
            if ("STREAMING".equals(mCameraSlots[slotIndex].status)) {
                openedSlotCount++;
            }
        }
        return openedSlotCount;
    }

    private void log(final String message) {
        if (message == null) return;
        Log.i(TAG, message);
        final String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (mLogLock) {
            mLogLines.add(timestamp + " " + message);
            while (mLogLines.size() > MAX_LOG_LINES) {
                mLogLines.remove(0);
            }
        }
    }

    private int clamp(final int value, final int minimum, final int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private String normalizeText(final String value, final String fallbackValue) {
        if (value == null) return fallbackValue;
        final String normalizedValue = value.trim();
        return normalizedValue.length() > 0 ? normalizedValue : fallbackValue;
    }

    private static final class CameraSlotState {
        final int slotIndex;
        volatile String cameraId;
        volatile String status = "EMPTY";
        volatile CameraDevice cameraDevice;
        CameraRecoveryController.Attempt<CameraDevice> cameraAttempt;
        volatile CameraCaptureSession captureSession;
        volatile CameraSurfaceFrameReader.FrameTarget frameTarget;
        volatile long frameCount;
        volatile long jpegFrameCount;
        volatile int width;
        volatile int height;
        volatile float fps;
        volatile long openGeneration;
        long lastReadbackErrorLogMs;
        long lastFpsFrameCount;
        long lastFpsTimestampMs;

        CameraSlotState(final int slotIndex) {
            this.slotIndex = slotIndex;
        }

        void resetFrameMetrics() {
            frameCount = 0;
            jpegFrameCount = 0;
            fps = 0f;
            lastReadbackErrorLogMs = 0;
            lastFpsFrameCount = 0;
            lastFpsTimestampMs = System.currentTimeMillis();
        }

        void recordFrame(final long now) {
            frameCount++;
            final long elapsedMs = now - lastFpsTimestampMs;
            if (elapsedMs >= 1000) {
                final long frameDelta = frameCount - lastFpsFrameCount;
                fps = frameDelta * 1000f / elapsedMs;
                lastFpsFrameCount = frameCount;
                lastFpsTimestampMs = now;
            }
        }

        float calculateFps() {
            return fps;
        }
    }
}
