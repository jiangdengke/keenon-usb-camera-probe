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
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
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
    private static final int MAX_CAMERA_SLOTS = 8;
    private static final int DEFAULT_CAMERA_SLOT_COUNT = 8;
    private static final int STREAM_PORT = 8080;
    private static final int TARGET_WIDTH = 640;
    private static final int TARGET_HEIGHT = 480;
    private static final int CAMERA2_TARGET_MIN_FPS = 10;
    private static final int CAMERA2_TARGET_MAX_FPS = 15;
    private static final String DEFAULT_PUSH_TARGET_URL = "ws://192.168.112.194:9090/";
    private static final int DEFAULT_PUSH_SLOT_COUNT = 4;
    private static final int DEFAULT_PUSH_INTERVAL_MS = 500;
    private static final int MIN_PUSH_INTERVAL_MS = 200;
    private static final int MAX_LOG_LINES = 220;
    private static final int CAMERA_RECONNECT_DELAY_MS = 1500;
    private static final byte JPEG_QUALITY = 60;

    private final LocalBinder mBinder = new LocalBinder();
    private final Object mLifecycleLock = new Object();
    private final Object mLogLock = new Object();
    private final List<String> mLogLines = new ArrayList<String>();
    private final CameraSlotState[] mCameraSlots = new CameraSlotState[MAX_CAMERA_SLOTS];

    private HandlerThread mCameraThread;
    private Handler mCameraHandler;
    private CameraManager mCameraManager;
    private CameraStreamHub mStreamHub;
    private CameraPushClient mPushClient;
    private volatile boolean mStreamingStarted;
    private volatile int mActiveSlotCount;
    private int mCameraSlotLimit = DEFAULT_CAMERA_SLOT_COUNT;
    private boolean mPushEnabled = true;
    private String mPushTargetUrl = DEFAULT_PUSH_TARGET_URL;
    private int mPushSlotCount = DEFAULT_PUSH_SLOT_COUNT;
    private int mPushIntervalMs = DEFAULT_PUSH_INTERVAL_MS;

    private final Runnable mCameraReconcileRunnable = new Runnable() {
        @Override
        public void run() {
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

    boolean isPushConnected() {
        final CameraPushClient pushClient = mPushClient;
        return pushClient != null && pushClient.isConnected();
    }

    String getPushStatus() {
        if (!mPushEnabled) return "已关闭";
        return isPushConnected() ? "已连接" : "连接中";
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
        if (intent == null) return false;
        final boolean previousPushEnabled = mPushEnabled;
        final String previousPushTargetUrl = mPushTargetUrl;
        final int previousPushSlotCount = mPushSlotCount;
        final int previousPushIntervalMs = mPushIntervalMs;
        mCameraSlotLimit = clamp(intent.getIntExtra(EXTRA_CAMERA_SLOT_COUNT,
            DEFAULT_CAMERA_SLOT_COUNT), 1, MAX_CAMERA_SLOTS);
        mPushEnabled = intent.getBooleanExtra(EXTRA_PUSH_ENABLED, true);
        mPushTargetUrl = normalizeText(intent.getStringExtra(EXTRA_PUSH_TARGET_URL),
            DEFAULT_PUSH_TARGET_URL);
        mPushSlotCount = clamp(intent.getIntExtra(EXTRA_PUSH_SLOT_COUNT,
            DEFAULT_PUSH_SLOT_COUNT), 1, MAX_CAMERA_SLOTS);
        mPushIntervalMs = Math.max(MIN_PUSH_INTERVAL_MS,
            intent.getIntExtra(EXTRA_PUSH_INTERVAL_MS, DEFAULT_PUSH_INTERVAL_MS));
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
        cameraHandler.removeCallbacks(mCameraReconcileRunnable);
        cameraHandler.postDelayed(mCameraReconcileRunnable, Math.max(0, delayMs));
    }

    @SuppressLint("MissingPermission")
    private void reconcileCameraSlots() {
        if (!mStreamingStarted) return;
        if (mCameraManager == null) {
            log("Camera2扫描失败：CameraManager为空");
            return;
        }
        try {
            final String[] cameraIds = mCameraManager.getCameraIdList();
            final int desiredSlotCount = Math.min(cameraIds.length, mCameraSlotLimit);
            for (int slotIndex = desiredSlotCount; slotIndex < mActiveSlotCount; slotIndex++) {
                closeCameraSlot(mCameraSlots[slotIndex], "CLOSED");
            }
            mActiveSlotCount = desiredSlotCount;
            if (mStreamHub != null) {
                mStreamHub.setActiveSlotCount(mActiveSlotCount);
            }
            log("Camera2后台扫描结果：cameraId数量=" + cameraIds.length
                + "，本轮打开=" + mActiveSlotCount);
            updateForegroundNotification("正在推送 " + mActiveSlotCount + " 路摄像头");
            for (int slotIndex = 0; slotIndex < mActiveSlotCount; slotIndex++) {
                final CameraSlotState slot = mCameraSlots[slotIndex];
                final String cameraId = cameraIds[slotIndex];
                final boolean matchingCamera = cameraId.equals(slot.cameraId);
                final boolean activeCamera = "OPENING".equals(slot.status)
                    || "OPEN".equals(slot.status) || "STREAMING".equals(slot.status);
                if (!matchingCamera || !activeCamera) {
                    openCameraSlot(slot, cameraId);
                }
            }
        } catch (final SecurityException securityException) {
            log("Camera2后台扫描失败：CAMERA权限不可用");
        } catch (final CameraAccessException cameraAccessException) {
            log("Camera2后台扫描失败：" + cameraAccessException.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    private void openCameraSlot(final CameraSlotState slot, final String cameraId) {
        closeCameraSlot(slot, "CLOSED");
        final long openGeneration = ++slot.openGeneration;
        slot.cameraId = cameraId;
        slot.status = "OPENING";
        slot.resetFrameMetrics();
        try {
            final Size jpegSize = chooseJpegSize(cameraId);
            slot.width = jpegSize.getWidth();
            slot.height = jpegSize.getHeight();
            final ImageReader imageReader = ImageReader.newInstance(slot.width, slot.height,
                ImageFormat.JPEG, 2);
            slot.imageReader = imageReader;
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(final ImageReader callbackImageReader) {
                    handleImageAvailable(slot, callbackImageReader, openGeneration);
                }
            }, mCameraHandler);
            log("Camera2后台：准备打开第" + (slot.slotIndex + 1) + "路，cameraId="
                + cameraId + "，JPEG=" + slot.width + "x" + slot.height);
            mCameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(final CameraDevice cameraDevice) {
                    if (!isCurrentGeneration(slot, cameraId, openGeneration)) {
                        cameraDevice.close();
                        return;
                    }
                    slot.cameraDevice = cameraDevice;
                    slot.status = "OPEN";
                    log("Camera2后台：第" + (slot.slotIndex + 1)
                        + "路open成功，cameraId=" + cameraId);
                    createCaptureSession(slot, cameraDevice, imageReader, openGeneration);
                }

                @Override
                public void onDisconnected(final CameraDevice cameraDevice) {
                    if (!isCurrentGeneration(slot, cameraId, openGeneration)) {
                        cameraDevice.close();
                        return;
                    }
                    log("Camera2后台：第" + (slot.slotIndex + 1)
                        + "路已断开，cameraId=" + cameraId);
                    closeCameraSlot(slot, "DISCONNECTED");
                    scheduleCameraReconcile(CAMERA_RECONNECT_DELAY_MS);
                }

                @Override
                public void onError(final CameraDevice cameraDevice, final int error) {
                    if (!isCurrentGeneration(slot, cameraId, openGeneration)) {
                        cameraDevice.close();
                        return;
                    }
                    log("Camera2后台：第" + (slot.slotIndex + 1)
                        + "路打开错误，cameraId=" + cameraId + "，error=" + error);
                    closeCameraSlot(slot, "OPEN FAILED: Camera2 error=" + error);
                    scheduleCameraReconcile(CAMERA_RECONNECT_DELAY_MS);
                }
            }, mCameraHandler);
        } catch (final SecurityException securityException) {
            log("Camera2后台：第" + (slot.slotIndex + 1) + "路打开失败，无Camera权限");
            closeCameraSlot(slot, "OPEN FAILED: 无Camera权限");
        } catch (final Exception exception) {
            log("Camera2后台：第" + (slot.slotIndex + 1)
                + "路打开异常：" + exception.getMessage());
            closeCameraSlot(slot, "OPEN FAILED: " + exception.getMessage());
            scheduleCameraReconcile(CAMERA_RECONNECT_DELAY_MS);
        }
    }

    private boolean isCurrentGeneration(final CameraSlotState slot, final String cameraId,
        final long openGeneration) {
        return mStreamingStarted && slot.openGeneration == openGeneration
            && cameraId != null && cameraId.equals(slot.cameraId);
    }

    private void createCaptureSession(final CameraSlotState slot,
        final CameraDevice cameraDevice, final ImageReader imageReader,
        final long openGeneration) {
        if (imageReader == null || slot.imageReader != imageReader) {
            closeCameraSlot(slot, "OPEN FAILED: ImageReader为空");
            return;
        }
        final Surface jpegSurface = imageReader.getSurface();
        try {
            cameraDevice.createCaptureSession(Arrays.asList(jpegSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(final CameraCaptureSession session) {
                        if (!mStreamingStarted || slot.openGeneration != openGeneration
                            || slot.cameraDevice != cameraDevice
                            || slot.imageReader != imageReader) {
                            session.close();
                            return;
                        }
                        slot.captureSession = session;
                        try {
                            final CaptureRequest.Builder requestBuilder =
                                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            requestBuilder.addTarget(jpegSurface);
                            final Range<Integer> fpsRange = chooseFpsRange(slot.cameraId);
                            if (fpsRange != null) {
                                requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                    fpsRange);
                            }
                            requestBuilder.set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY);
                            session.setRepeatingRequest(requestBuilder.build(), null,
                                mCameraHandler);
                            slot.status = "STREAMING";
                            slot.lastFpsTimestampMs = System.currentTimeMillis();
                            if (mStreamHub != null) {
                                mStreamHub.onSlotOpened(slot.slotIndex,
                                    "Camera2 id=" + slot.cameraId, slot.width, slot.height,
                                    "Camera2JPEG", slot.slotIndex + 1, 0, 0, false, 0f,
                                    "前台服务ImageReader后台采集", false);
                            }
                            log("Camera2后台：第" + (slot.slotIndex + 1)
                                + "路采集已启动，cameraId=" + slot.cameraId
                                + "，尺寸=" + slot.width + "x" + slot.height
                                + "，fpsRange=" + formatFpsRange(fpsRange));
                        } catch (final Exception exception) {
                            log("Camera2后台：第" + (slot.slotIndex + 1)
                                + "路启动采集失败：" + exception.getMessage());
                            closeCameraSlot(slot, "OPEN FAILED: " + exception.getMessage());
                            scheduleCameraReconcile(CAMERA_RECONNECT_DELAY_MS);
                        }
                    }

                    @Override
                    public void onConfigureFailed(final CameraCaptureSession session) {
                        session.close();
                        if (slot.openGeneration != openGeneration) return;
                        log("Camera2后台：第" + (slot.slotIndex + 1)
                            + "路Session配置失败，cameraId=" + slot.cameraId);
                        closeCameraSlot(slot,
                            "OPEN FAILED: Camera2 session configure failed");
                        scheduleCameraReconcile(CAMERA_RECONNECT_DELAY_MS);
                    }
                }, mCameraHandler);
        } catch (final CameraAccessException cameraAccessException) {
            log("Camera2后台：第" + (slot.slotIndex + 1)
                + "路创建Session失败：" + cameraAccessException.getMessage());
            closeCameraSlot(slot, "OPEN FAILED: " + cameraAccessException.getMessage());
            scheduleCameraReconcile(CAMERA_RECONNECT_DELAY_MS);
        }
    }

    private void handleImageAvailable(final CameraSlotState slot,
        final ImageReader imageReader, final long openGeneration) {
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) return;
            if (!mStreamingStarted || slot.openGeneration != openGeneration
                || slot.imageReader != imageReader) {
                return;
            }
            final Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) return;
            final ByteBuffer jpegBuffer = planes[0].getBuffer();
            final byte[] jpegData = new byte[jpegBuffer.remaining()];
            jpegBuffer.get(jpegData);
            final long now = System.currentTimeMillis();
            slot.recordFrame(now);
            final CameraStreamHub streamHub = mStreamHub;
            if (streamHub != null) {
                streamHub.onCamera2JpegFrame(slot.slotIndex, jpegData, slot.width, slot.height);
            }
            if (slot.frameCount == 1) {
                log("Camera2后台：第" + (slot.slotIndex + 1)
                    + "路首帧JPEG已到达，大小=" + jpegData.length + "字节");
            }
        } catch (final Exception exception) {
            log("Camera2后台：第" + (slot.slotIndex + 1)
                + "路读取JPEG失败：" + exception.getMessage());
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private Size chooseJpegSize(final String cameraId) throws CameraAccessException {
        final CameraCharacteristics characteristics =
            mCameraManager.getCameraCharacteristics(cameraId);
        final StreamConfigurationMap configurationMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        final Size[] jpegSizes = configurationMap != null
            ? configurationMap.getOutputSizes(ImageFormat.JPEG) : null;
        if (jpegSizes == null || jpegSizes.length == 0) {
            throw new IllegalStateException("cameraId=" + cameraId + "未上报JPEG输出尺寸");
        }

        Size closestSize = jpegSizes[0];
        long closestScore = Long.MAX_VALUE;
        for (final Size candidateSize : jpegSizes) {
            if (candidateSize.getWidth() == TARGET_WIDTH
                && candidateSize.getHeight() == TARGET_HEIGHT) {
                return candidateSize;
            }
            final long widthDifference = Math.abs(candidateSize.getWidth() - TARGET_WIDTH);
            final long heightDifference = Math.abs(candidateSize.getHeight() - TARGET_HEIGHT);
            final long oversizedPenalty = candidateSize.getWidth() > TARGET_WIDTH
                || candidateSize.getHeight() > TARGET_HEIGHT ? 1000000L : 0L;
            final long score = oversizedPenalty + widthDifference * 10L + heightDifference * 10L;
            if (score < closestScore) {
                closestScore = score;
                closestSize = candidateSize;
            }
        }
        return closestSize;
    }

    private Range<Integer> chooseFpsRange(final String cameraId) {
        if (cameraId == null || mCameraManager == null) return null;
        try {
            final CameraCharacteristics characteristics =
                mCameraManager.getCameraCharacteristics(cameraId);
            final Range<Integer>[] availableRanges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (availableRanges == null || availableRanges.length == 0) return null;

            Range<Integer> bestRange = null;
            int bestScore = Integer.MAX_VALUE;
            for (final Range<Integer> availableRange : availableRanges) {
                if (availableRange == null) continue;
                final int lower = availableRange.getLower();
                final int upper = availableRange.getUpper();
                int score = Math.abs(upper - CAMERA2_TARGET_MAX_FPS) * 20
                    + Math.abs(lower - CAMERA2_TARGET_MIN_FPS) * 5;
                if (upper >= CAMERA2_TARGET_MIN_FPS && upper <= CAMERA2_TARGET_MAX_FPS) {
                    score -= 1000;
                }
                if (upper > CAMERA2_TARGET_MAX_FPS) {
                    score += (upper - CAMERA2_TARGET_MAX_FPS) * 30;
                }
                if (score < bestScore) {
                    bestScore = score;
                    bestRange = availableRange;
                }
            }
            return bestRange;
        } catch (final Exception exception) {
            log("Camera2后台：读取cameraId=" + cameraId
                + " fpsRange失败：" + exception.getMessage());
            return null;
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
            stopCameraThread();
            mCameraManager = null;
        }
    }

    private void closeCameraSlot(final CameraSlotState slot) {
        closeCameraSlot(slot, "CLOSED");
    }

    private void closeCameraSlot(final CameraSlotState slot, final String finalStatus) {
        if (slot == null) return;
        slot.openGeneration++;
        final CameraCaptureSession captureSession = slot.captureSession;
        final CameraDevice cameraDevice = slot.cameraDevice;
        final ImageReader imageReader = slot.imageReader;
        slot.captureSession = null;
        slot.cameraDevice = null;
        slot.imageReader = null;
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
            captureSession.close();
        }
        if (cameraDevice != null) {
            cameraDevice.close();
        }
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
        }
        final CameraStreamHub streamHub = mStreamHub;
        if (streamHub != null) {
            streamHub.onSlotClosed(slot.slotIndex, slot.status);
        }
    }

    private void closeCameraResourcesOnCameraThread() {
        final Handler cameraHandler = mCameraHandler;
        if (cameraHandler == null || Thread.currentThread() == mCameraThread) {
            unregisterAvailabilityCallback();
            closeAllCameraSlots();
            return;
        }

        final CountDownLatch closeLatch = new CountDownLatch(1);
        cameraHandler.removeCallbacks(mCameraReconcileRunnable);
        cameraHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    unregisterAvailabilityCallback();
                    closeAllCameraSlots();
                } finally {
                    closeLatch.countDown();
                }
            }
        });
        try {
            if (!closeLatch.await(3, TimeUnit.SECONDS)) {
                log("Camera2后台：等待相机资源关闭超时");
            }
        } catch (final InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
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

    private void stopCameraThread() {
        final HandlerThread cameraThread = mCameraThread;
        mCameraThread = null;
        mCameraHandler = null;
        if (cameraThread == null) return;
        cameraThread.quitSafely();
        try {
            cameraThread.join(2000);
        } catch (final InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
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

    private String formatFpsRange(final Range<Integer> fpsRange) {
        return fpsRange == null ? "系统默认"
            : fpsRange.getLower() + "-" + fpsRange.getUpper();
    }

    private static final class CameraSlotState {
        final int slotIndex;
        volatile String cameraId;
        volatile String status = "EMPTY";
        volatile CameraDevice cameraDevice;
        volatile CameraCaptureSession captureSession;
        volatile ImageReader imageReader;
        volatile long frameCount;
        volatile int width;
        volatile int height;
        volatile float fps;
        volatile long openGeneration;
        long lastFpsFrameCount;
        long lastFpsTimestampMs;

        CameraSlotState(final int slotIndex) {
            this.slotIndex = slotIndex;
        }

        void resetFrameMetrics() {
            frameCount = 0;
            fps = 0f;
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
