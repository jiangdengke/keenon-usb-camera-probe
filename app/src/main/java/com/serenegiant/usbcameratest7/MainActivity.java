/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */

package com.serenegiant.usbcameratest7;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.Range;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usb.UVCCamera;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Probe app for Keenon/Peanut robots whose cameras are exposed as USB UVC devices.
 *
 * The app opens up to the detected UVC camera count, capped at eight cameras,
 * shows a preview for every opened device, and registers a frame callback for
 * every camera. If several tiles show non-zero FPS at the same time, the robot
 * can provide multiple video streams concurrently and we can add RTSP/WebRTC/MJPEG
 * output on top of this proof.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "KeenonUvcProbe";

    private static final int MAX_CAMERAS = 8;
    private static final int GRID_COLUMNS = 2;
    private static final int TARGET_WIDTH = 640;
    private static final int TARGET_HEIGHT = 480;
    private static final boolean LOW_BANDWIDTH_DIAGNOSTIC_MODE = false;
    private static final int LOW_BANDWIDTH_TARGET_WIDTH = 320;
    private static final int LOW_BANDWIDTH_TARGET_HEIGHT = 240;
    private static final int PREVIEW_MIN_FPS = 1;
    private static final int PREVIEW_MAX_FPS = 10;
    private static final int PREVIEW_COMPAT_MIN_FPS = 1;
    private static final int PREVIEW_COMPAT_MAX_FPS = 31;
    private static final int USB_CLASS_VIDEO = 14;
    private static final int STREAM_PORT = 8080;
    private static final int MAX_LOG_LINES = 220;
    private static final long FRAME_DEBUG_LOG_INTERVAL_MS = 5000;
    private static final long NO_FRAME_RETRY_WAIT_MS = 5000;
    private static final long NO_FRAME_RETRY_FALLBACK_GRACE_MS = 1000;
    private static final long NO_FRAME_RETRY_DELAY_MS = 1200;
    private static final long OPEN_STAGGER_DELAY_MS = 900;
    private static final long JPEG_OVERLAY_UPDATE_INTERVAL_MS = 600;
    private static final long SURFACE_CAPTURE_INTERVAL_MS = 800;
    private static final long SURFACE_CAPTURE_LOG_INTERVAL_MS = 5000;
    private static final int SURFACE_CAPTURE_JPEG_QUALITY = 60;
    private static final int MAX_NO_FRAME_RETRIES = 2;
    private static final int MAX_FIRST_SLOT_FULL_PROBE_RETRIES = 10;
    private static final int FIRST_SLOT_PROMOTE_AFTER_RETRIES = 3;
    private static final boolean DISABLE_FIRST_SLOT_YUYV_BY_DEFAULT = false;
    private static final boolean PROMOTE_HEALTHY_FIRST_SLOT_BY_DEFAULT = true;
    private static final boolean USE_CAMERA2_BY_DEFAULT = true;
    private static final int CAMERA_PERMISSION_REQUEST = 4102;
    private static final int CAMERA2_TARGET_MIN_FPS = 10;
    private static final int CAMERA2_TARGET_MAX_FPS = 15;
    private static final int PRIMARY_SLOT_LOG_COLOR = Color.rgb(255, 230, 0);
    private static final float BANDWIDTH_FACTOR = 0.20f;
    private static final float RETRY_BANDWIDTH_FACTOR = 0.10f;
    private static final float COMPAT_BANDWIDTH_FACTOR = 1.00f;

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final List<UsbDevice> mPermissionQueue = new ArrayList<UsbDevice>();
    private final Set<String> mQueuedDeviceNames = new HashSet<String>();
    private final Set<String> mFirstSlotSkippedDeviceNames = new HashSet<String>();
    private final Map<String, CameraSlot> mOpenedByDeviceName = new HashMap<String, CameraSlot>();

    private USBMonitor mUSBMonitor;
    private CameraStreamHub mStreamHub;
    private CameraManager mCamera2Manager;
    private CameraSlot[] mSlots;
    private TextView mStatusText;
    private TextView mServerText;
    private Button mLogButton;
    private Button mFirstOnlyButton;
    private LinearLayout mSlotGrid;
    private LinearLayout[] mSlotRows;
    private TextView mLogText;
    private ScrollView mLogScroll;
    private final List<String> mLogLines = new ArrayList<String>();
    private boolean mWaitingForPermission;
    private boolean mPermissionRequestScheduled;
    private String mPermissionDeviceName;
    private long mLastPermissionRequestMs;
    private int mLastUsbCount;
    private int mLastUvcCount;
    private int mLastCamera2Count;
    private int mVisibleSlotCount;
    private long mLastHealthLogMs;
    private int mOpenSequence;
    private boolean mFirstSlotOnlyMode;
    private boolean mDisableFirstSlotYuyv;
    private boolean mPromoteHealthyFirstSlot;
    private boolean mFirstSlotPromotionScheduled;
    private boolean mUseCamera2;
    private int mFirstSlotSkipCount;
    private String mFirstSlotDeviceFilter;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
        mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);
        mStreamHub = new CameraStreamHub(MAX_CAMERAS, STREAM_PORT, new CameraStreamHub.LogSink() {
            @Override
            public void log(final String message) {
                addLog(message);
            }
        });
        mUseCamera2 = getIntent() == null
            || getIntent().getBooleanExtra("use_camera2",
                getIntent().getBooleanExtra("camera2_mode", USE_CAMERA2_BY_DEFAULT));
        mFirstSlotOnlyMode = getIntent() != null
            && getIntent().getBooleanExtra("first_slot_only", false);
        mDisableFirstSlotYuyv = getIntent() != null
            ? getIntent().getBooleanExtra("disable_first_slot_yuyv",
                DISABLE_FIRST_SLOT_YUYV_BY_DEFAULT)
            : DISABLE_FIRST_SLOT_YUYV_BY_DEFAULT;
        mPromoteHealthyFirstSlot = getIntent() == null
            || getIntent().getBooleanExtra("promote_healthy_first_slot",
                getIntent().getBooleanExtra("first_slot_promote_healthy",
                    PROMOTE_HEALTHY_FIRST_SLOT_BY_DEFAULT));
        mFirstSlotSkipCount = Math.max(0, getIntent() != null
            ? getIntent().getIntExtra("first_slot_skip_count", 0) : 0);
        if (getIntent() != null) {
            mFirstSlotDeviceFilter = trimToNull(getIntent().getStringExtra("first_slot_device"));
            if (mFirstSlotDeviceFilter == null) {
                mFirstSlotDeviceFilter = trimToNull(getIntent().getStringExtra("first_slot_device_name"));
            }
        } else {
            mFirstSlotDeviceFilter = null;
        }
        addLog("应用已启动，版本=" + appVersionText() + "，HTTP端口=" + STREAM_PORT);
        if (mUseCamera2) {
            addLog("官方兼容模式已启用：优先使用Android Camera2/HAL cameraIdList打开摄像头，贴近官方CurrencyCameraActivity");
        } else {
            addLog("UVC直连模式已启用：使用USB/libuvc枚举 /dev/bus/usb 设备");
        }
        if (mFirstSlotOnlyMode) {
            addLog("ADB调试：第1路独占模式已启用，本次只打开第1个"
                + (mUseCamera2 ? "Camera2 ID" : "UVC设备"));
        }
        if (!mUseCamera2 && mPromoteHealthyFirstSlot) {
            addLog("ADB调试：第1路健康提升已启用，当前第1路多档位无画面后会临时跳过该USB设备，改开下一颗UVC到/stream/0.mjpeg");
        }
        if (mFirstSlotSkipCount > 0) {
            addLog("ADB调试：第1路会先跳过前" + mFirstSlotSkipCount + "个UVC候选");
        }
        if (mFirstSlotDeviceFilter != null) {
            addLog("ADB调试：第1路只匹配USB设备：" + mFirstSlotDeviceFilter);
        }
        if (mUseCamera2) {
            addLog("Camera2模式：预览尺寸按官方代码固定为 "
                + TARGET_WIDTH + "x" + TARGET_HEIGHT
                + "，通过TextureView抓图生成 /stream/N.mjpeg");
        } else if (mDisableFirstSlotYuyv) {
            addLog("ADB调试：第1路YUYV兜底已关闭，本次第1路只探测MJPEG");
        } else {
            addLog("ADB调试：第1路YUYV兜底与MJPEG原始帧直通已启用，MJPEG无帧后会自动兜底拉流");
        }
        if (!mUseCamera2 && LOW_BANDWIDTH_DIAGNOSTIC_MODE) {
            addLog("强低带宽诊断模式已启用：MJPEG优先，目标<="
                + LOW_BANDWIDTH_TARGET_WIDTH + "x" + LOW_BANDWIDTH_TARGET_HEIGHT
                + "，fps=" + PREVIEW_MIN_FPS + "-" + PREVIEW_MAX_FPS
                + "，失败回退fps=" + PREVIEW_COMPAT_MIN_FPS + "-" + PREVIEW_COMPAT_MAX_FPS
                + "，低分辨率带宽系数=" + BANDWIDTH_FACTOR + "/" + RETRY_BANDWIDTH_FACTOR
                + "，高分辨率回退=" + COMPAT_BANDWIDTH_FACTOR
                + "，错峰=" + OPEN_STAGGER_DELAY_MS + "ms");
        } else if (!mUseCamera2) {
            addLog("兼容恢复模式已启用：MJPEG优先，目标<="
                + TARGET_WIDTH + "x" + TARGET_HEIGHT
                + "，fps=UVCCamera默认" + UVCCamera.DEFAULT_PREVIEW_MIN_FPS
                + "-" + UVCCamera.DEFAULT_PREVIEW_MAX_FPS
                + "，带宽系数=" + COMPAT_BANDWIDTH_FACTOR
                + "，错峰=" + OPEN_STAGGER_DELAY_MS + "ms");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!mUseCamera2 && mUSBMonitor != null) {
            mUSBMonitor.register();
        }
        if (mStreamHub != null) {
            mStreamHub.start();
        }
        updateServerInfo();
        addLog(mUseCamera2 ? "Camera2扫描已启动" : "USB监听已启动");
        mUiHandler.postDelayed(mScanRunnable, 800);
        mUiHandler.postDelayed(mFpsRunnable, 1000);
    }

    @Override
    protected void onStop() {
        mUiHandler.removeCallbacks(mScanRunnable);
        mUiHandler.removeCallbacks(mFpsRunnable);
        mUiHandler.removeCallbacks(mPermissionRequestRunnable);
        mPermissionRequestScheduled = false;
        closeAllCameras();
        if (mStreamHub != null) {
            mStreamHub.stop();
        }
        if (!mUseCamera2 && mUSBMonitor != null) {
            mUSBMonitor.unregister();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (mStreamHub != null) {
            mStreamHub.stop();
            mStreamHub = null;
        }
        if (mUSBMonitor != null) {
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }
        mCamera2Manager = null;
        super.onDestroy();
    }

    private String appVersionText() {
        try {
            final PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return packageInfo.versionName + "(" + packageInfo.versionCode + ")";
        } catch (final Exception e) {
            return "未知";
        }
    }

    private String trimToNull(final String value) {
        if (value == null) return null;
        final String trimmed = value.trim();
        return trimmed.length() > 0 ? trimmed : null;
    }

    private View createContentView() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        final LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(6), dp(8), dp(6));

        final Button scanButton = new Button(this);
        scanButton.setText("扫描/打开");
        scanButton.setAllCaps(false);
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                mFirstSlotOnlyMode = false;
                scanAndOpenActiveCameraMode();
            }
        });
        topBar.addView(scanButton, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final Button closeButton = new Button(this);
        closeButton.setText("关闭全部");
        closeButton.setAllCaps(false);
        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                closeAllCameras();
                updateStatus("已关闭全部摄像头");
            }
        });
        topBar.addView(closeButton, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mFirstOnlyButton = new Button(this);
        mFirstOnlyButton.setText("只开第1路");
        mFirstOnlyButton.setAllCaps(false);
        mFirstOnlyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                openFirstSlotOnly();
            }
        });
        topBar.addView(mFirstOnlyButton, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mLogButton = new Button(this);
        mLogButton.setText("显示日志");
        mLogButton.setAllCaps(false);
        mLogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                toggleLogPanel();
            }
        });
        topBar.addView(mLogButton, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mStatusText = new TextView(this);
        mStatusText.setTextColor(Color.WHITE);
        mStatusText.setTextSize(12);
        mStatusText.setPadding(dp(8), 0, 0, 0);
        mStatusText.setText("等待USB扫描...");
        topBar.addView(mStatusText, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(topBar, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mServerText = new TextView(this);
        mServerText.setTextColor(Color.rgb(220, 240, 255));
        mServerText.setTextSize(12);
        mServerText.setPadding(dp(8), dp(4), dp(8), dp(4));
        mServerText.setBackgroundColor(Color.rgb(20, 20, 32));
        mServerText.setText("HTTP服务启动后会显示局域网访问地址");
        root.addView(mServerText, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mLogScroll = new ScrollView(this);
        mLogScroll.setBackgroundColor(Color.rgb(8, 8, 8));
        mLogScroll.setVisibility(View.GONE);
        mLogText = new TextView(this);
        mLogText.setTextColor(Color.rgb(180, 255, 180));
        mLogText.setTextSize(10);
        mLogText.setPadding(dp(8), dp(4), dp(8), dp(4));
        mLogText.setText("点击上方“显示日志”后，这里会显示运行日志...");
        mLogScroll.addView(mLogText, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(mLogScroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(150)));

        mSlotGrid = new LinearLayout(this);
        mSlotGrid.setOrientation(LinearLayout.VERTICAL);
        mSlotGrid.setPadding(dp(4), 0, dp(4), dp(4));
        root.addView(mSlotGrid, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        mSlots = new CameraSlot[MAX_CAMERAS];
        final int rowCount = (MAX_CAMERAS + GRID_COLUMNS - 1) / GRID_COLUMNS;
        mSlotRows = new LinearLayout[rowCount];
        for (int i = 0; i < MAX_CAMERAS; i++) {
            if (i % GRID_COLUMNS == 0) {
                final LinearLayout rowLayout = new LinearLayout(this);
                rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                mSlotRows[i / GRID_COLUMNS] = rowLayout;
                mSlotGrid.addView(rowLayout, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            }
            mSlots[i] = new CameraSlot(i);
            mSlotRows[i / GRID_COLUMNS].addView(mSlots[i].container, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }
        updateVisibleSlots(0);

        return root;
    }

    private void toggleLogPanel() {
        if (mLogScroll == null) return;
        setLogPanelVisible(mLogScroll.getVisibility() != View.VISIBLE);
    }

    private void setLogPanelVisible(final boolean visible) {
        if (mLogScroll == null) return;
        mLogScroll.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (mLogButton != null) {
            mLogButton.setText(visible ? "隐藏日志" : "显示日志");
        }
        if (visible) {
            mLogScroll.post(new Runnable() {
                @Override
                public void run() {
                    mLogScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    private void openFirstSlotOnly() {
        mFirstSlotOnlyMode = true;
        addLog("第1路独占模式：将关闭其它路，只打开扫描到的第1个"
            + (mUseCamera2 ? "Camera2 ID" : "UVC设备") + "验证/stream/0.mjpeg");
        closeAllCameras();
        scanAndOpenActiveCameraMode();
    }

    private void updateVisibleSlots(final int requestedSlotCount) {
        final int slotCount = Math.max(0, Math.min(requestedSlotCount, MAX_CAMERAS));
        if (mSlotRows == null || mSlots == null) return;
        if (slotCount == mVisibleSlotCount) {
            if (mStreamHub != null) {
                mStreamHub.setActiveSlotCount(slotCount);
            }
            return;
        }

        for (int i = slotCount; i < mVisibleSlotCount; i++) {
            mSlots[i].closeCamera();
        }

        mVisibleSlotCount = slotCount;
        if (mStreamHub != null) {
            mStreamHub.setActiveSlotCount(mVisibleSlotCount);
        }
        trimPermissionQueueToVisibleSlots();

        for (int row = 0; row < mSlotRows.length; row++) {
            final int rowStart = row * GRID_COLUMNS;
            mSlotRows[row].setVisibility(rowStart < mVisibleSlotCount ? View.VISIBLE : View.GONE);
            for (int col = 0; col < GRID_COLUMNS; col++) {
                final int index = rowStart + col;
                if (index < mSlots.length) {
                    mSlots[index].container.setVisibility(index < mVisibleSlotCount ? View.VISIBLE : View.GONE);
                }
            }
        }
    }

    private void trimPermissionQueueToVisibleSlots() {
        final int waitingCount = mPermissionDeviceName != null ? 1 : 0;
        final int allowedQueued = Math.max(0,
            mVisibleSlotCount - mOpenedByDeviceName.size() - waitingCount - countRetryPendingSlots());
        while (mPermissionQueue.size() > allowedQueued) {
            final UsbDevice removed = mPermissionQueue.remove(mPermissionQueue.size() - 1);
            mQueuedDeviceNames.remove(removed.getDeviceName());
        }
    }

    private final Runnable mScanRunnable = new Runnable() {
        @Override
        public void run() {
            scanAndOpenActiveCameraMode();
        }
    };

    private final Runnable mFpsRunnable = new Runnable() {
        @Override
        public void run() {
            for (int i = 0; i < mVisibleSlotCount; i++) {
                mSlots[i].refreshFps();
            }
            updateStatus(null);
            mUiHandler.postDelayed(this, 1000);
        }
    };

    private final Runnable mPermissionRequestRunnable = new Runnable() {
        @Override
        public void run() {
            mPermissionRequestScheduled = false;
            requestNextPermissionIfNeeded();
        }
    };

    private void scanAndOpenActiveCameraMode() {
        if (mUseCamera2) {
            scanAndOpenCamera2Devices();
        } else {
            scanAndRequestUvcDevices();
        }
    }

    private boolean ensureCamera2Permission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        addLog("Camera2模式：等待系统摄像头权限授权");
        requestPermissions(new String[] { Manifest.permission.CAMERA }, CAMERA_PERMISSION_REQUEST);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, final String[] permissions,
        final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            final boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            addLog("Camera2模式：摄像头权限" + (granted ? "已授权" : "被拒绝"));
            if (granted) {
                scanAndOpenCamera2Devices();
            } else {
                updateStatus("Camera2摄像头权限被拒绝");
            }
        }
    }

    private void scanAndOpenCamera2Devices() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            addLog("Camera2模式不可用：系统版本低于Android 5.0，回退到UVC模式");
            mUseCamera2 = false;
            if (mUSBMonitor != null) {
                mUSBMonitor.register();
            }
            scanAndRequestUvcDevices();
            return;
        }
        if (!ensureCamera2Permission()) return;

        try {
            if (mCamera2Manager == null) {
                mCamera2Manager = (CameraManager)getSystemService(CAMERA_SERVICE);
            }
            final String[] cameraIds = mCamera2Manager != null
                ? mCamera2Manager.getCameraIdList() : new String[0];
            mLastCamera2Count = cameraIds.length;
            mLastUsbCount = 0;
            mLastUvcCount = 0;
            final int slotLimit = mFirstSlotOnlyMode ? 1 : MAX_CAMERAS;
            final int slotCount = Math.min(cameraIds.length, slotLimit);
            updateVisibleSlots(slotCount);
            addLog("Camera2扫描结果：cameraId数量=" + cameraIds.length
                + "，本轮打开=" + slotCount);
            for (int i = 0; i < cameraIds.length; i++) {
                addLog("发现Camera2摄像头：id=" + cameraIds[i]
                    + describeCamera2Id(cameraIds[i]));
            }
            for (int i = 0; i < slotCount; i++) {
                final CameraSlot slot = mSlots[i];
                slot.assignCamera2(cameraIds[i], ++mOpenSequence);
            }
            updateStatus("Camera2扫描完成");
        } catch (final Exception e) {
            Log.e(TAG, "Camera2 scan/open failed", e);
            addLog("Camera2扫描/打开失败：" + e.getMessage());
            updateStatus("Camera2扫描失败");
        }
    }

    private Range<Integer> chooseCamera2FpsRange(final String cameraId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
            || mCamera2Manager == null || cameraId == null) {
            return null;
        }
        try {
            final CameraCharacteristics characteristics =
                mCamera2Manager.getCameraCharacteristics(cameraId);
            final Range<Integer>[] ranges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges == null || ranges.length == 0) {
                addLog("Camera2：cameraId=" + cameraId
                    + " 未上报可用fpsRange，使用系统默认");
                return null;
            }

            Range<Integer> best = null;
            int bestScore = Integer.MAX_VALUE;
            final StringBuilder available = new StringBuilder();
            for (final Range<Integer> range : ranges) {
                if (range == null) continue;
                if (available.length() > 0) available.append(',');
                available.append(formatCamera2FpsRange(range));

                final int lower = range.getLower();
                final int upper = range.getUpper();
                int score = Math.abs(upper - CAMERA2_TARGET_MAX_FPS) * 20
                    + Math.abs(lower - CAMERA2_TARGET_MIN_FPS) * 5;
                if (upper >= CAMERA2_TARGET_MIN_FPS && upper <= CAMERA2_TARGET_MAX_FPS) {
                    score -= 1000;
                }
                if (upper > CAMERA2_TARGET_MAX_FPS) {
                    score += (upper - CAMERA2_TARGET_MAX_FPS) * 30;
                }
                if (lower > CAMERA2_TARGET_MAX_FPS) {
                    score += 300;
                }
                if (score < bestScore) {
                    bestScore = score;
                    best = range;
                }
            }
            addLog("Camera2：cameraId=" + cameraId + " 可用fpsRange=" + available
                + "，选用=" + formatCamera2FpsRange(best));
            return best;
        } catch (final Exception e) {
            addLog("Camera2：cameraId=" + cameraId + " 读取fpsRange失败："
                + e.getMessage() + "，使用系统默认");
            return null;
        }
    }

    private String formatCamera2FpsRange(final Range<Integer> range) {
        return range == null ? "系统默认" : range.getLower() + "-" + range.getUpper();
    }

    private String describeCamera2Id(final String cameraId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
            || mCamera2Manager == null || cameraId == null) {
            return "";
        }
        try {
            final CameraCharacteristics characteristics =
                mCamera2Manager.getCameraCharacteristics(cameraId);
            final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            final Integer level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            return " facing=" + camera2FacingName(facing)
                + " level=" + camera2LevelName(level);
        } catch (final Exception e) {
            return " characteristics读取失败=" + e.getMessage();
        }
    }

    private String camera2FacingName(final Integer facing) {
        if (facing == null) return "unknown";
        switch (facing) {
            case CameraCharacteristics.LENS_FACING_FRONT:
                return "front";
            case CameraCharacteristics.LENS_FACING_BACK:
                return "back";
            case CameraCharacteristics.LENS_FACING_EXTERNAL:
                return "external";
            default:
                return String.valueOf(facing);
        }
    }

    private String camera2LevelName(final Integer level) {
        if (level == null) return "unknown";
        switch (level) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                return "legacy";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                return "limited";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                return "full";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                return "level3";
            default:
                return String.valueOf(level);
        }
    }

    private void scanAndRequestUvcDevices() {
        if (mUSBMonitor == null) return;

        final List<UsbDevice> allDevices = mUSBMonitor.getDeviceList();
        final List<UsbDevice> uvcDevices = new ArrayList<UsbDevice>();
        for (final UsbDevice device : allDevices) {
            if (isPotentialUvcDevice(device)) {
                uvcDevices.add(device);
            }
        }

        final List<UsbDevice> openCandidates = buildOpenCandidates(uvcDevices);
        mLastUsbCount = allDevices.size();
        mLastUvcCount = uvcDevices.size();
        final int slotLimit = mFirstSlotOnlyMode ? 1 : MAX_CAMERAS;
        updateVisibleSlots(Math.min(openCandidates.size(), slotLimit));

        Log.i(TAG, "USB devices=" + allDevices.size() + ", UVC candidates=" + uvcDevices.size()
            + ", open candidates=" + openCandidates.size());
        addLog("扫描结果：USB设备=" + allDevices.size() + "，UVC摄像头=" + uvcDevices.size()
            + (openCandidates.size() != uvcDevices.size()
                ? "，本轮可打开=" + openCandidates.size() : ""));
        if (mFirstSlotOnlyMode) {
            addLog("第1路独占模式已启用：本次只会请求第1个UVC设备授权，其它UVC不打开");
        }
        for (final UsbDevice device : allDevices) {
            Log.i(TAG, describeDevice(device));
            addLog("发现USB设备：" + describeDevice(device));
        }

        int queuedOrOpened = mOpenedByDeviceName.size() + mPermissionQueue.size() + countRetryPendingSlots()
            + (mPermissionDeviceName != null ? 1 : 0);
        for (final UsbDevice device : openCandidates) {
            if (!hasFreeSlot()) break;
            if (queuedOrOpened >= mVisibleSlotCount) break;
            final String deviceName = device.getDeviceName();
            if (mOpenedByDeviceName.containsKey(deviceName)
                || mQueuedDeviceNames.contains(deviceName)
                || deviceName.equals(mPermissionDeviceName)
                || isRetryPendingDevice(deviceName)) {
                continue;
            }
            mPermissionQueue.add(device);
            mQueuedDeviceNames.add(deviceName);
            queuedOrOpened++;
            addLog("加入授权队列：" + shortDeviceName(device));
        }

        requestNextPermissionIfNeeded();
        updateStatus("扫描完成");
    }

    private List<UsbDevice> buildOpenCandidates(final List<UsbDevice> uvcDevices) {
        final List<UsbDevice> candidates = new ArrayList<UsbDevice>();
        if (uvcDevices == null || uvcDevices.isEmpty()) return candidates;

        int skippedByIndex = 0;
        for (final UsbDevice device : uvcDevices) {
            if (mFirstSlotDeviceFilter != null && !matchesFirstSlotDeviceFilter(device)) {
                continue;
            }
            if (mFirstSlotSkipCount > 0 && skippedByIndex < mFirstSlotSkipCount) {
                skippedByIndex++;
                addLog("第1路候选过滤：按first_slot_skip_count跳过 "
                    + shortDeviceName(device));
                continue;
            }
            if (mFirstSlotSkippedDeviceNames.contains(device.getDeviceName())) {
                addLog("第1路候选过滤：临时跳过已判定无有效画面的设备 "
                    + shortDeviceName(device));
                continue;
            }
            candidates.add(device);
        }

        if (mFirstSlotDeviceFilter != null && candidates.isEmpty()) {
            addLog("第1路候选过滤：未找到匹配设备 " + mFirstSlotDeviceFilter);
        }
        if (!mFirstSlotSkippedDeviceNames.isEmpty() && candidates.isEmpty()
            && mFirstSlotDeviceFilter == null && mFirstSlotSkipCount == 0) {
            addLog("第1路候选过滤：所有UVC都在临时跳过列表里，本轮不再自动打开，等待重新插拔或重启应用");
        }
        return candidates;
    }

    private boolean matchesFirstSlotDeviceFilter(final UsbDevice device) {
        if (device == null || mFirstSlotDeviceFilter == null) return true;
        final String filter = mFirstSlotDeviceFilter;
        return device.getDeviceName().contains(filter)
            || shortDeviceName(device).contains(filter)
            || String.format(Locale.US, "0x%04x:0x%04x",
                device.getVendorId(), device.getProductId()).contains(filter);
    }

    private void requestNextPermissionIfNeeded() {
        if (mUSBMonitor == null || mPermissionRequestScheduled || mWaitingForPermission
            || mPermissionQueue.isEmpty()) {
            return;
        }
        if (findFreeReadySlot(mPermissionQueue.get(0)) == null) {
            return;
        }

        final long now = System.currentTimeMillis();
        final long elapsedMs = now - mLastPermissionRequestMs;
        if (mLastPermissionRequestMs > 0 && elapsedMs < OPEN_STAGGER_DELAY_MS) {
            final long delayMs = OPEN_STAGGER_DELAY_MS - elapsedMs;
            mPermissionRequestScheduled = true;
            addLog("错峰打开：等待" + delayMs + "ms后再请求下一路USB授权，降低多路同时启动压力");
            mUiHandler.postDelayed(mPermissionRequestRunnable, delayMs);
            return;
        }

        final UsbDevice device = mPermissionQueue.remove(0);
        mQueuedDeviceNames.remove(device.getDeviceName());
        mWaitingForPermission = true;
        mPermissionDeviceName = device.getDeviceName();
        mLastPermissionRequestMs = now;

        updateStatus("正在请求USB授权：" + shortDeviceName(device));
        addLog("请求USB授权：" + shortDeviceName(device));
        final boolean failed = mUSBMonitor.requestPermission(device);
        if (failed) {
            Log.w(TAG, "Failed to request USB permission: " + describeDevice(device));
            addLog("USB授权请求失败：" + shortDeviceName(device));
            mWaitingForPermission = false;
            mPermissionDeviceName = null;
            requestNextPermissionIfNeeded();
        }
    }

    private final OnDeviceConnectListener mOnDeviceConnectListener = new OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            Log.i(TAG, "onAttach: " + describeDevice(device));
            addLog("USB已插入：" + shortDeviceName(device));
            mFirstSlotSkippedDeviceNames.remove(device.getDeviceName());
            mUiHandler.postDelayed(mScanRunnable, 300);
        }

        @Override
        public void onConnect(final UsbDevice device, final UsbControlBlock ctrlBlock, final boolean createNew) {
            Log.i(TAG, "onConnect: " + describeDevice(device));
            addLog("USB已授权并连接：" + shortDeviceName(device));
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        openConnectedDevice(device, ctrlBlock);
                    } finally {
                        mWaitingForPermission = false;
                        mPermissionDeviceName = null;
                        requestNextPermissionIfNeeded();
                    }
                }
            });
        }

        @Override
        public void onDisconnect(final UsbDevice device, final UsbControlBlock ctrlBlock) {
            Log.i(TAG, "onDisconnect: " + describeDevice(device));
            addLog("USB已断开：" + shortDeviceName(device));
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    final CameraSlot slot = mOpenedByDeviceName.remove(device.getDeviceName());
                    if (slot != null) {
                        slot.closeCamera();
                    }
                    updateStatus("USB摄像头已断开");
                }
            });
        }

        @Override
        public void onDettach(final UsbDevice device) {
            Log.i(TAG, "onDettach: " + describeDevice(device));
            addLog("USB已拔出：" + shortDeviceName(device));
            mFirstSlotSkippedDeviceNames.remove(device.getDeviceName());
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    clearRetryPending(device);
                }
            });
        }

        @Override
        public void onCancel(final UsbDevice device) {
            Log.w(TAG, "USB permission canceled: " + (device != null ? describeDevice(device) : "null"));
            addLog("USB授权已取消：" + (device != null ? shortDeviceName(device) : "null"));
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    mWaitingForPermission = false;
                    mPermissionDeviceName = null;
                    clearRetryPending(device);
                    updateStatus("USB授权已取消");
                    requestNextPermissionIfNeeded();
                }
            });
        }
    };

    private void openConnectedDevice(final UsbDevice device, final UsbControlBlock ctrlBlock) {
        if (mOpenedByDeviceName.containsKey(device.getDeviceName())) {
            updateStatus("摄像头已打开：" + shortDeviceName(device));
            return;
        }

        final CameraSlot slot = findFreeReadySlot(device);
        if (slot == null) {
            updateStatus("没有可用预览窗口，请关闭摄像头后重新扫描");
            return;
        }

        UVCCamera camera = null;
        final int openSequence = ++mOpenSequence;
        try {
            addLog("强诊断：准备打开第" + (slot.index + 1) + "路，设备="
                + shortDeviceName(device) + "，打开序号=#" + openSequence
                + "，surface=" + (slot.surface != null ? "已就绪" : "未就绪"));
            camera = new UVCCamera();
            camera.open(ctrlBlock);
            addLog("强诊断：第" + (slot.index + 1) + "路 native open 成功");
            final String supportedSize = camera.getSupportedSize();
            final List<Size> mjpegSizes = UVCCamera.getSupportedSize(6, supportedSize);
            final List<Size> yuyvSizes = UVCCamera.getSupportedSize(4, supportedSize);
            addLog("强诊断：第" + (slot.index + 1) + "路支持分辨率 MJPEG="
                + formatSizes(mjpegSizes) + "；YUYV=" + formatSizes(yuyvSizes));
            if (isFirstSlotYuyvDisabled(slot.index)) {
                addLog("强诊断：第1路YUYV已禁用：现场确认该路径会触发libuvc/libusb native崩溃，本轮只探测MJPEG");
            } else if (slot.index == 0) {
                addLog("强诊断：第1路YUYV兜底已启用：MJPEG仍无帧时会改试YUYV RAW转JPEG");
            }
            if (slot.noFrameRetryCount > 0) {
                addLog("自动重试：第" + (slot.index + 1) + "路第" + slot.noFrameRetryCount
                    + "次重开，策略=" + retryStrategyName(slot.noFrameRetryCount, slot.index));
            }
            final PreviewChoice preview = choosePreviewSize(mjpegSizes, yuyvSizes,
                slot.noFrameRetryCount, slot.index);
            final float bandwidthFactor = chooseBandwidthFactor(preview, slot.noFrameRetryCount);
            final String previewReason = previewSelectionReason(preview, slot.noFrameRetryCount,
                slot.index);
            if (LOW_BANDWIDTH_DIAGNOSTIC_MODE && !isLowBandwidthPreview(preview)) {
                addLog("强诊断：第" + (slot.index + 1)
                    + "路未找到320x240及以下，改用兼容带宽系数="
                    + bandwidthFactor + "，避免高分辨率低系数无帧");
            }
            final int requestedFormat = preview.format;
            final PreviewSettings previewSettings = setPreviewSize(camera, preview, bandwidthFactor,
                slot.index);
            if (preview.format != requestedFormat) {
                addLog("强诊断：第" + (slot.index + 1) + "路 MJPEG 设置失败，已切换为 "
                    + preview.formatName());
            }
            if (previewSettings.fpsFallback) {
                addLog("强诊断：第" + (slot.index + 1) + "路低FPS不兼容，已回退fps="
                    + previewSettings.fpsMin + "-" + previewSettings.fpsMax);
            }
            addLog("第" + (slot.index + 1) + "路"
                + (LOW_BANDWIDTH_DIAGNOSTIC_MODE ? "优先低分辨率" : "兼容参数")
                + "：选用 "
                + preview.width + "x" + preview.height + " " + preview.formatName()
                + "，" + previewReason + "，fps=" + previewSettings.fpsMin + "-"
                + previewSettings.fpsMax
                + "，带宽系数=" + bandwidthFactor + "，打开序号=#" + openSequence);

            final boolean yuyvFallback = isYuyvFallbackPreview(preview);
            final boolean rawMjpegFallback = isRawMjpegPreview(preview);
            if (slot.texture.getSurfaceTexture() != null) {
                slot.texture.getSurfaceTexture().setDefaultBufferSize(preview.width, preview.height);
            }
            if (yuyvFallback) {
                slot.prepareYuyvVisibleSurfaceFallback();
                camera.setPreviewDisplay(slot.surface);
                addLog("强诊断：第" + (slot.index + 1)
                    + "路YUYV RAW兜底，已绑定真实预览窗口引出帧回调，并用覆盖层遮挡绿屏");
            } else if (rawMjpegFallback) {
                slot.prepareJpegOverlayFallback();
                camera.setPreviewDisplay(slot.surface);
                addLog("强诊断：第" + (slot.index + 1)
                    + "路MJPEG原始帧直通兜底，跳过native解码，直接生成HTTP JPEG流");
            } else {
                slot.clearJpegOverlay();
                camera.setPreviewDisplay(slot.surface);
                addLog("强诊断：第" + (slot.index + 1) + "路预览窗口已绑定");
            }
            final int callbackPixelFormat = rawMjpegFallback
                ? UVCCamera.PIXEL_FORMAT_MJPEG
                : (yuyvFallback ? UVCCamera.PIXEL_FORMAT_RAW : UVCCamera.PIXEL_FORMAT_NV21);
            final String callbackFormatName = rawMjpegFallback ? "MJPEG-RAW"
                : (yuyvFallback ? "RAW" : "NV21");
            camera.setFrameCallback(slot.frameCallback, callbackPixelFormat);
            addLog("强诊断：第" + (slot.index + 1) + "路帧回调已注册，格式="
                + callbackFormatName
                + (rawMjpegFallback ? "，原始MJPEG将直接用于HTTP拉流"
                    : (yuyvFallback ? "，YUYV RAW将由Java转JPEG拉流" : "")));
            camera.startPreview();
            addLog("强诊断：第" + (slot.index + 1) + "路 startPreview 已调用，等待帧回调");

            slot.device = device;
            slot.camera = camera;
            slot.preview = preview;
            slot.supportedSizeJson = supportedSize;
            slot.openSequence = openSequence;
            slot.bandwidthFactor = bandwidthFactor;
            slot.previewReason = previewReason;
            slot.openStrategy = slot.noFrameRetryCount > 0
                ? retryStrategyName(slot.noFrameRetryCount, slot.index)
                : (LOW_BANDWIDTH_DIAGNOSTIC_MODE ? "首次强低带宽打开" : "首次兼容参数打开");
            slot.previewFpsMin = previewSettings.fpsMin;
            slot.previewFpsMax = previewSettings.fpsMax;
            slot.fpsFallback = previewSettings.fpsFallback;
            slot.status = "OPEN";
            slot.frameCount.set(0);
            slot.lastFrameCount = 0;
            slot.lastFpsTimestampMs = System.currentTimeMillis();
            slot.openedAtMs = slot.lastFpsTimestampMs;
            slot.firstFrameLogged = false;
            slot.lastFrameDebugLogMs = 0;
            slot.retryPending = false;
            slot.retryLimitLogged = false;
            slot.refreshLabel();
            mOpenedByDeviceName.put(device.getDeviceName(), slot);
            if (mStreamHub != null) {
                mStreamHub.onSlotOpened(slot.index, shortDeviceName(device), preview.width,
                    preview.height, preview.formatName(), openSequence, previewSettings.fpsMin,
                    previewSettings.fpsMax, previewSettings.fpsFallback, bandwidthFactor,
                    previewReason, LOW_BANDWIDTH_DIAGNOSTIC_MODE);
            }
            slot.startNoFrameRetryMonitor();

            Log.i(TAG, "Opened slot " + (slot.index + 1) + ": " + describeDevice(device));
            Log.i(TAG, "Supported sizes slot " + (slot.index + 1) + ": " + supportedSize);
            addLog("已打开第" + (slot.index + 1) + "路：" + shortDeviceName(device)
                + " " + preview.width + "x" + preview.height + " " + preview.formatName()
                + "，打开序号=#" + openSequence);
            updateStatus("已打开摄像头：" + shortDeviceName(device));
        } catch (final Exception e) {
            Log.e(TAG, "Failed to open camera: " + describeDevice(device), e);
            addLog("打开失败：" + shortDeviceName(device) + "，原因=" + e.getMessage());
            if (camera != null) {
                try {
                    camera.destroy();
                } catch (final Exception ignored) {
                }
            }
            slot.status = "OPEN FAILED: " + e.getMessage();
            slot.retryPending = false;
            if (mStreamHub != null) {
                mStreamHub.onSlotClosed(slot.index, slot.status);
            }
            slot.refreshLabel();
            updateStatus("打开失败，请点“显示日志”查看原因");
        }
    }

    private PreviewSettings setPreviewSize(final UVCCamera camera, final PreviewChoice preview,
        final float bandwidthFactor, final int slotIndex) {
        try {
            camera.setPreviewSize(preview.width, preview.height, preview.format, bandwidthFactor);
            return new PreviewSettings(UVCCamera.DEFAULT_PREVIEW_MIN_FPS,
                UVCCamera.DEFAULT_PREVIEW_MAX_FPS, false);
        } catch (final IllegalArgumentException previewError) {
            if (preview.format != UVCCamera.FRAME_FORMAT_MJPEG) {
                throw previewError;
            }
            addLog("强诊断：第" + (slotIndex + 1)
                + "路 MJPEG 默认预览参数失败，尝试YUYV，原因="
                + previewError.getMessage());
            preview.format = UVCCamera.FRAME_FORMAT_YUYV;
            preview.rawMjpegCallback = false;
            camera.setPreviewSize(preview.width, preview.height, preview.format, bandwidthFactor);
            return new PreviewSettings(UVCCamera.DEFAULT_PREVIEW_MIN_FPS,
                UVCCamera.DEFAULT_PREVIEW_MAX_FPS, false);
        }
    }

    private PreviewChoice choosePreviewSize(final String supportedSizeJson) {
        final List<Size> mjpegSizes = UVCCamera.getSupportedSize(6, supportedSizeJson);
        final List<Size> yuyvSizes = UVCCamera.getSupportedSize(4, supportedSizeJson);
        return choosePreviewSize(mjpegSizes, yuyvSizes, 0, 0);
    }

    private PreviewChoice choosePreviewSize(final List<Size> mjpegSizes, final List<Size> yuyvSizes,
        final int retryCount, final int slotIndex) {
        final List<PreviewChoice> candidates = new ArrayList<PreviewChoice>();
        addPreviewCandidates(candidates, mjpegSizes, UVCCamera.FRAME_FORMAT_MJPEG);
        addPreviewCandidates(candidates, yuyvSizes, UVCCamera.FRAME_FORMAT_YUYV);

        final PreviewChoice preview = choosePreviewCandidateForRetry(candidates, mjpegSizes,
            yuyvSizes, retryCount, slotIndex);
        if (preview != null) {
            return preview;
        }

        if (LOW_BANDWIDTH_DIAGNOSTIC_MODE) {
            return new PreviewChoice(LOW_BANDWIDTH_TARGET_WIDTH, LOW_BANDWIDTH_TARGET_HEIGHT,
                UVCCamera.FRAME_FORMAT_MJPEG);
        }
        return new PreviewChoice(TARGET_WIDTH, TARGET_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG);
    }

    private PreviewChoice choosePreviewCandidateForRetry(final List<PreviewChoice> candidates,
        final List<Size> mjpegSizes, final List<Size> yuyvSizes, final int retryCount,
        final int slotIndex) {
        if (LOW_BANDWIDTH_DIAGNOSTIC_MODE) {
            return chooseLowBandwidthPreviewCandidate(candidates);
        }
        if (slotIndex == 0 && retryCount >= 3) {
            final PreviewChoice probe = chooseFirstSlotRawMjpegProbeCandidate(mjpegSizes,
                retryCount);
            if (probe != null) {
                return probe;
            }
            final PreviewChoice fullProbe = chooseFirstSlotFullProbeCandidate(candidates,
                retryCount, slotIndex);
            if (fullProbe != null) {
                return fullProbe;
            }
        }
        if (retryCount == 1) {
            final PreviewChoice alternateMjpeg = chooseAlternateMjpegPreviewCandidate(mjpegSizes);
            return alternateMjpeg != null ? alternateMjpeg : choosePreviewCandidate(candidates, false);
        }
        if (retryCount >= 2) {
            if (isFirstSlotYuyvDisabled(slotIndex)) {
                final PreviewChoice alternateMjpeg = chooseFirstSlotFullProbeCandidate(candidates,
                    retryCount, slotIndex);
                return alternateMjpeg != null ? alternateMjpeg : choosePreviewCandidate(candidates, false);
            }
            final PreviewChoice yuyv = chooseFormatPreviewCandidate(yuyvSizes,
                UVCCamera.FRAME_FORMAT_YUYV);
            if (yuyv != null) {
                return yuyv;
            }
            final PreviewChoice alternateMjpeg = chooseAlternateMjpegPreviewCandidate(mjpegSizes);
            return alternateMjpeg != null ? alternateMjpeg : choosePreviewCandidate(candidates, false);
        }
        return choosePreviewCandidate(candidates, false);
    }

    private PreviewChoice chooseFirstSlotFullProbeCandidate(final List<PreviewChoice> candidates,
        final int retryCount, final int slotIndex) {
        final List<PreviewChoice> probeCandidates = buildFullProbeCandidates(candidates, slotIndex);
        if (probeCandidates.isEmpty()) return null;
        final int candidateIndex = Math.max(0, retryCount - 2);
        if (candidateIndex >= probeCandidates.size()) {
            return probeCandidates.get(probeCandidates.size() - 1);
        }
        return probeCandidates.get(candidateIndex);
    }

    private PreviewChoice chooseFirstSlotRawMjpegProbeCandidate(final List<Size> mjpegSizes,
        final int retryCount) {
        final List<PreviewChoice> candidates = new ArrayList<PreviewChoice>();
        addPreviewCandidates(candidates, mjpegSizes, UVCCamera.FRAME_FORMAT_MJPEG, true);
        if (candidates.isEmpty()) return null;
        final List<PreviewChoice> sorted = buildFullProbeCandidates(candidates, 0);
        final int candidateIndex = Math.max(0, retryCount - 3);
        if (candidateIndex >= sorted.size()) {
            return sorted.get(sorted.size() - 1);
        }
        return sorted.get(candidateIndex);
    }

    private List<PreviewChoice> buildFullProbeCandidates(final List<PreviewChoice> candidates,
        final int slotIndex) {
        final List<PreviewChoice> result = new ArrayList<PreviewChoice>();
        if (candidates == null) return result;
        for (final PreviewChoice candidate : candidates) {
            if (isFirstSlotYuyvDisabled(slotIndex)
                && candidate.format == UVCCamera.FRAME_FORMAT_YUYV) {
                continue;
            }
            if (containsPreviewCandidate(result, candidate)) continue;
            final PreviewChoice copy = new PreviewChoice(candidate.width, candidate.height,
                candidate.format, candidate.rawMjpegCallback);
            int insertIndex = result.size();
            for (int i = 0; i < result.size(); i++) {
                if (compareFullProbeCandidate(copy, result.get(i)) < 0) {
                    insertIndex = i;
                    break;
                }
            }
            result.add(insertIndex, copy);
        }
        return result;
    }

    private boolean containsPreviewCandidate(final List<PreviewChoice> candidates,
        final PreviewChoice target) {
        if (target == null) return true;
        for (final PreviewChoice candidate : candidates) {
            if (candidate.width == target.width && candidate.height == target.height
                && candidate.format == target.format
                && candidate.rawMjpegCallback == target.rawMjpegCallback) {
                return true;
            }
        }
        return false;
    }

    private int compareFullProbeCandidate(final PreviewChoice left, final PreviewChoice right) {
        final int leftArea = area(left);
        final int rightArea = area(right);
        if (leftArea != rightArea) return leftArea - rightArea;
        if (left.width != right.width) return left.width - right.width;
        if (left.height != right.height) return left.height - right.height;
        if (left.rawMjpegCallback != right.rawMjpegCallback) {
            return left.rawMjpegCallback ? 1 : -1;
        }
        if (left.format == right.format) return 0;
        return left.format == UVCCamera.FRAME_FORMAT_MJPEG ? -1 : 1;
    }

    private PreviewChoice chooseAlternateMjpegPreviewCandidate(final List<Size> mjpegSizes) {
        final List<PreviewChoice> candidates = new ArrayList<PreviewChoice>();
        addPreviewCandidates(candidates, mjpegSizes, UVCCamera.FRAME_FORMAT_MJPEG);
        if (candidates.isEmpty()) return null;

        final int targetArea = TARGET_WIDTH * TARGET_HEIGHT;
        PreviewChoice smallestAboveTarget = null;
        PreviewChoice largestBelowTarget = null;
        for (final PreviewChoice candidate : candidates) {
            final int candidateArea = area(candidate);
            if (candidate.width == TARGET_WIDTH && candidate.height == TARGET_HEIGHT) {
                continue;
            }
            if (candidateArea > targetArea) {
                if (smallestAboveTarget == null || candidateArea < area(smallestAboveTarget)) {
                    smallestAboveTarget = candidate;
                }
            } else if (largestBelowTarget == null || candidateArea > area(largestBelowTarget)) {
                largestBelowTarget = candidate;
            }
        }
        return smallestAboveTarget != null ? smallestAboveTarget : largestBelowTarget;
    }

    private PreviewChoice chooseFormatPreviewCandidate(final List<Size> sizes, final int format) {
        final List<PreviewChoice> candidates = new ArrayList<PreviewChoice>();
        addPreviewCandidates(candidates, sizes, format);
        return choosePreviewCandidate(candidates, format == UVCCamera.FRAME_FORMAT_YUYV);
    }

    private float chooseBandwidthFactor(final PreviewChoice preview, final int retryCount) {
        if (!LOW_BANDWIDTH_DIAGNOSTIC_MODE) {
            return COMPAT_BANDWIDTH_FACTOR;
        }
        if (!LOW_BANDWIDTH_DIAGNOSTIC_MODE || isLowBandwidthPreview(preview)) {
            return retryCount >= 2 ? RETRY_BANDWIDTH_FACTOR : BANDWIDTH_FACTOR;
        }
        return COMPAT_BANDWIDTH_FACTOR;
    }

    private boolean isLowBandwidthPreview(final PreviewChoice preview) {
        return preview != null
            && preview.width <= LOW_BANDWIDTH_TARGET_WIDTH
            && preview.height <= LOW_BANDWIDTH_TARGET_HEIGHT;
    }

    private boolean isYuyvFallbackPreview(final PreviewChoice preview) {
        return preview != null && !preview.rawMjpegCallback
            && preview.format == UVCCamera.FRAME_FORMAT_YUYV;
    }

    private boolean isRawMjpegPreview(final PreviewChoice preview) {
        return preview != null && preview.rawMjpegCallback
            && preview.format == UVCCamera.FRAME_FORMAT_MJPEG;
    }

    private boolean usesJpegOverlayPreview(final PreviewChoice preview) {
        return isYuyvFallbackPreview(preview) || isRawMjpegPreview(preview);
    }

    private boolean isFirstSlotYuyvDisabled(final int slotIndex) {
        return slotIndex == 0 && mDisableFirstSlotYuyv;
    }

    private String firstSlotFullProbeStrategyName() {
        if (mDisableFirstSlotYuyv) {
            return "第1路全档位探测，YUYV已关闭，仅轮换MJPEG分辨率";
        }
        return "第1路MJPEG原始帧直通探测，绕过native解码生成拉流";
    }

    private String retryStrategyName(final int retryCount) {
        return retryStrategyName(retryCount, -1);
    }

    private String retryStrategyName(final int retryCount, final int slotIndex) {
        if (!LOW_BANDWIDTH_DIAGNOSTIC_MODE) {
            if (slotIndex == 0 && retryCount >= 3) {
                return firstSlotFullProbeStrategyName();
            }
            if (retryCount >= 2) {
                return "MJPEG仍无帧，改试YUYV兼容格式";
            }
            return "MJPEG无帧，改试其它MJPEG分辨率";
        }
        if (retryCount >= 2) {
            return "低分辨率继续降带宽，高分辨率保持兼容带宽";
        }
        return "MJPEG优先错峰重开";
    }

    private String formatSizes(final List<Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return "无";
        final StringBuilder sb = new StringBuilder();
        for (final Size size : sizes) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(size.width).append('x').append(size.height);
        }
        return sb.toString();
    }

    private void addPreviewCandidates(final List<PreviewChoice> candidates, final List<Size> sizes,
        final int format) {
        addPreviewCandidates(candidates, sizes, format, false);
    }

    private void addPreviewCandidates(final List<PreviewChoice> candidates, final List<Size> sizes,
        final int format, final boolean rawMjpegCallback) {
        if (sizes == null) return;
        for (final Size size : sizes) {
            candidates.add(new PreviewChoice(size.width, size.height, format, rawMjpegCallback));
        }
    }

    private PreviewChoice choosePreviewCandidate(final List<PreviewChoice> candidates, final boolean preferYuyv) {
        if (candidates == null || candidates.isEmpty()) return null;

        PreviewChoice bestUnderTarget = null;
        PreviewChoice smallest = null;
        for (final PreviewChoice candidate : candidates) {
            if (candidate.width == TARGET_WIDTH && candidate.height == TARGET_HEIGHT) {
                return candidate;
            }
            if (isBetterSmallest(candidate, smallest, preferYuyv)) {
                smallest = candidate;
            }
            if (candidate.width <= TARGET_WIDTH && candidate.height <= TARGET_HEIGHT) {
                if (isBetterUnderTarget(candidate, bestUnderTarget, preferYuyv)) {
                    bestUnderTarget = candidate;
                }
            }
        }
        return bestUnderTarget != null ? bestUnderTarget : smallest;
    }

    private PreviewChoice chooseLowBandwidthPreviewCandidate(final List<PreviewChoice> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;

        PreviewChoice bestUnderTarget = null;
        PreviewChoice smallest = null;
        for (final PreviewChoice candidate : candidates) {
            if (isBetterSmallest(candidate, smallest, false)) {
                smallest = candidate;
            }
            if (candidate.width <= LOW_BANDWIDTH_TARGET_WIDTH
                && candidate.height <= LOW_BANDWIDTH_TARGET_HEIGHT) {
                if (isBetterUnderTarget(candidate, bestUnderTarget, false)) {
                    bestUnderTarget = candidate;
                }
            }
        }
        return bestUnderTarget != null ? bestUnderTarget : smallest;
    }

    private boolean isBetterSmallest(final PreviewChoice candidate, final PreviewChoice current,
        final boolean preferYuyv) {
        if (current == null) return true;
        final int candidateArea = area(candidate);
        final int currentArea = area(current);
        if (candidateArea != currentArea) return candidateArea < currentArea;
        return isPreferredFormat(candidate, current, preferYuyv);
    }

    private boolean isBetterUnderTarget(final PreviewChoice candidate, final PreviewChoice current,
        final boolean preferYuyv) {
        if (current == null) return true;
        final int candidateArea = area(candidate);
        final int currentArea = area(current);
        if (candidateArea != currentArea) return candidateArea > currentArea;
        return isPreferredFormat(candidate, current, preferYuyv);
    }

    private boolean isPreferredFormat(final PreviewChoice candidate, final PreviewChoice current,
        final boolean preferYuyv) {
        final int preferredFormat = preferYuyv
            ? UVCCamera.FRAME_FORMAT_YUYV : UVCCamera.FRAME_FORMAT_MJPEG;
        return candidate.format == preferredFormat && current.format != preferredFormat;
    }

    private String previewSelectionReason(final PreviewChoice preview, final int retryCount,
        final int slotIndex) {
        if (LOW_BANDWIDTH_DIAGNOSTIC_MODE) {
            if (isLowBandwidthPreview(preview)) {
                return "强低带宽诊断，优先压低USB压力";
            }
            return "未找到320x240及以下，使用兼容带宽系数";
        }
        if (isRawMjpegPreview(preview)) {
            return "MJPEG原始帧直通，绕过native解码生成拉流";
        }
        if (slotIndex == 0 && retryCount >= 3) {
            return firstSlotFullProbeStrategyName();
        }
        if (retryCount == 1) {
            return "MJPEG无帧，改试其它MJPEG分辨率";
        }
        if (retryCount >= 2) {
            if (preview.format == UVCCamera.FRAME_FORMAT_YUYV) {
                return "MJPEG仍无帧，改试YUYV兼容格式";
            }
            return "YUYV不可用，继续尝试MJPEG候选";
        }
        if (preview.width <= TARGET_WIDTH && preview.height <= TARGET_HEIGHT) {
            return "兼容恢复模式，使用UVCCamera默认预览参数";
        }
        return "兼容恢复模式，未找到640x480及以下，使用最小支持分辨率";
    }

    private int area(final PreviewChoice preview) {
        return preview.width * preview.height;
    }

    private boolean hasFreeReadySlot() {
        return findFreeReadySlot() != null;
    }

    private boolean hasFreeSlot() {
        for (int i = 0; i < mVisibleSlotCount; i++) {
            if (mSlots[i].isFree()) {
                return true;
            }
        }
        return false;
    }

    private boolean isSlotVisible(final int slotIndex) {
        return slotIndex >= 0 && slotIndex < mVisibleSlotCount;
    }

    private boolean isRetryPendingDevice(final String deviceName) {
        if (deviceName == null || mSlots == null) return false;
        for (int i = 0; i < mVisibleSlotCount; i++) {
            final CameraSlot slot = mSlots[i];
            if (slot.retryPending && deviceName.equals(slot.retryDeviceName)) {
                return true;
            }
        }
        return false;
    }

    private int countRetryPendingSlots() {
        if (mSlots == null) return 0;
        int count = 0;
        for (int i = 0; i < mVisibleSlotCount; i++) {
            if (mSlots[i].retryPending) {
                count++;
            }
        }
        return count;
    }

    private int countOpenCamera2Slots() {
        if (mSlots == null) return 0;
        int count = 0;
        for (int i = 0; i < mVisibleSlotCount; i++) {
            if (mSlots[i].camera2Device != null) {
                count++;
            }
        }
        return count;
    }

    private void clearRetryPending(final UsbDevice device) {
        if (device == null || mSlots == null) return;
        final String deviceName = device.getDeviceName();
        for (int i = 0; i < mVisibleSlotCount; i++) {
            final CameraSlot slot = mSlots[i];
            if (slot.retryPending && deviceName.equals(slot.retryDeviceName)) {
                slot.retryPending = false;
                slot.retryDeviceName = null;
                slot.refreshLabel();
            }
        }
    }

    private boolean shouldPromoteFirstSlotAfterNoFrame(final CameraSlot slot) {
        return mPromoteHealthyFirstSlot
            && mFirstSlotOnlyMode
            && slot != null
            && slot.index == 0
            && slot.device != null
            && hasAlternativeFirstSlotCandidate(slot.device.getDeviceName());
    }

    private boolean shouldPromoteFirstSlotEarly(final CameraSlot slot) {
        return shouldPromoteFirstSlotAfterNoFrame(slot)
            && slot.noFrameRetryCount >= FIRST_SLOT_PROMOTE_AFTER_RETRIES;
    }

    private boolean hasAlternativeFirstSlotCandidate(final String currentDeviceName) {
        if (mUSBMonitor == null || mFirstSlotDeviceFilter != null) return false;
        final List<UsbDevice> allDevices = mUSBMonitor.getDeviceList();
        int skippedByIndex = 0;
        for (final UsbDevice device : allDevices) {
            if (!isPotentialUvcDevice(device)) continue;
            if (mFirstSlotSkipCount > 0 && skippedByIndex < mFirstSlotSkipCount) {
                skippedByIndex++;
                continue;
            }
            final String deviceName = device.getDeviceName();
            if (deviceName.equals(currentDeviceName)) continue;
            if (mFirstSlotSkippedDeviceNames.contains(deviceName)) continue;
            return true;
        }
        return false;
    }

    private void promoteNextHealthyDeviceForFirstSlot(final CameraSlot slot) {
        final UsbDevice unhealthyDevice = slot != null ? slot.device : null;
        if (unhealthyDevice == null) return;
        if (mFirstSlotPromotionScheduled) {
            addLog("第1路健康提升：切换下一颗UVC已在执行中，忽略重复触发");
            return;
        }
        mFirstSlotPromotionScheduled = true;
        final String unhealthyDeviceName = unhealthyDevice.getDeviceName();
        if (mFirstSlotSkippedDeviceNames.add(unhealthyDeviceName)) {
            addLog("第1路健康提升：当前设备多档位仍无有效画面，临时跳过 "
                + shortDeviceName(unhealthyDevice)
                + "，改把下一颗UVC打开到/stream/0.mjpeg");
        } else {
            addLog("第1路健康提升：设备已在临时跳过列表，继续寻找下一颗UVC "
                + shortDeviceName(unhealthyDevice));
        }
        mUiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mFirstSlotPromotionScheduled = false;
                closeAllCameras();
                scanAndRequestUvcDevices();
            }
        }, NO_FRAME_RETRY_DELAY_MS);
    }

    private CameraSlot findFreeReadySlot() {
        return findFreeReadySlot(null);
    }

    private CameraSlot findFreeReadySlot(final UsbDevice preferredDevice) {
        final String preferredDeviceName = preferredDevice != null ? preferredDevice.getDeviceName() : null;
        if (preferredDeviceName != null) {
            for (int i = 0; i < mVisibleSlotCount; i++) {
                final CameraSlot slot = mSlots[i];
                if (slot.isFree() && slot.surface != null
                    && preferredDeviceName.equals(slot.retryDeviceName)) {
                    return slot;
                }
            }
        }
        for (int i = 0; i < mVisibleSlotCount; i++) {
            final CameraSlot slot = mSlots[i];
            if (slot.isFree() && slot.surface != null && !slot.retryPending) {
                return slot;
            }
        }
        return null;
    }

    private void closeAllCameras() {
        mUiHandler.removeCallbacks(mPermissionRequestRunnable);
        mPermissionQueue.clear();
        mQueuedDeviceNames.clear();
        mWaitingForPermission = false;
        mPermissionRequestScheduled = false;
        mFirstSlotPromotionScheduled = false;
        mPermissionDeviceName = null;
        mLastPermissionRequestMs = 0;
        mOpenSequence = 0;
        mOpenedByDeviceName.clear();
        if (mSlots != null) {
            for (final CameraSlot slot : mSlots) {
                slot.closeCamera();
            }
        }
    }

    private boolean isPotentialUvcDevice(final UsbDevice device) {
        if (device == null) return false;
        if (device.getDeviceClass() == USB_CLASS_VIDEO) {
            return true;
        }

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            final UsbInterface usbInterface = device.getInterface(i);
            if (usbInterface.getInterfaceClass() == USB_CLASS_VIDEO) {
                return true;
            }
        }
        return false;
    }

    private String describeDevice(final UsbDevice device) {
        if (device == null) return "null";

        final StringBuilder interfaces = new StringBuilder();
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            final UsbInterface usbInterface = device.getInterface(i);
            if (i > 0) interfaces.append("; ");
            interfaces.append(String.format(Locale.US, "if%d class=%d sub=%d proto=%d",
                i,
                usbInterface.getInterfaceClass(),
                usbInterface.getInterfaceSubclass(),
                usbInterface.getInterfaceProtocol()));
        }

        return String.format(Locale.US,
            "%s vid=0x%04x pid=0x%04x devClass=%d devSub=%d devProto=%d interfaces=[%s]",
            device.getDeviceName(),
            device.getVendorId(),
            device.getProductId(),
            device.getDeviceClass(),
            device.getDeviceSubclass(),
            device.getDeviceProtocol(),
            interfaces.toString());
    }

    private String shortDeviceName(final UsbDevice device) {
        return String.format(Locale.US, "0x%04x:0x%04x %s",
            device.getVendorId(), device.getProductId(), device.getDeviceName());
    }

    private void updateStatus(final String prefix) {
        if (mStatusText == null) return;
        final StringBuilder sb = new StringBuilder();
        if (prefix != null) {
            sb.append(prefix).append(" | ");
        }
        if (mUseCamera2) {
            sb.append("Camera2=").append(mLastCamera2Count)
                .append(" opened=").append(countOpenCamera2Slots())
                .append("/").append(mVisibleSlotCount)
                .append(" max=").append(MAX_CAMERAS);
        } else {
            sb.append("USB=").append(mLastUsbCount)
                .append(" UVC=").append(mLastUvcCount)
                .append(" opened=").append(mOpenedByDeviceName.size())
                .append("/").append(mVisibleSlotCount)
                .append(" max=").append(MAX_CAMERAS)
                .append(" pending=").append(mPermissionQueue.size());
        }
        if (mFirstSlotOnlyMode) {
            sb.append(" 第1路独占");
        }
        if (mWaitingForPermission) {
            sb.append(" 等待授权");
        }
        if (mStreamHub != null && mStreamHub.isRunning()) {
            sb.append(" | HTTP ").append(mStreamHub.getBaseUrl())
                .append(" /cameras");
        }
        mStatusText.setText(sb.toString());
        updateServerInfo();
        final long now = System.currentTimeMillis();
        if (mStreamHub != null && prefix == null && now - mLastHealthLogMs > 5000) {
            mLastHealthLogMs = now;
            addLog(mStreamHub.buildHealthSummary());
        }
    }

    private void updateServerInfo() {
        if (mServerText == null) return;
        if (mStreamHub != null && mStreamHub.isRunning()) {
            final String baseUrl = mStreamHub.getBaseUrl();
            mServerText.setText("局域网访问：" + baseUrl
                + "\n摄像头列表：" + baseUrl + "/cameras"
                + " | 第1路：" + baseUrl + "/stream/0.mjpeg"
                + " | 第2路：" + baseUrl + "/stream/1.mjpeg");
        } else {
            mServerText.setText("HTTP服务已停止，保持App打开才能拉流。");
        }
    }

    private void addLog(final String message) {
        if (message == null || message.length() == 0) return;
        Log.i(TAG, message);
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    addLog(message);
                }
            });
            return;
        }
        final String line = String.format(Locale.US, "%1$tH:%1$tM:%1$tS %2$s", new Date(), message);
        mLogLines.add(line);
        while (mLogLines.size() > MAX_LOG_LINES) {
            mLogLines.remove(0);
        }
        if (mLogText == null) return;
        final boolean autoScroll = shouldAutoScrollLog();
        mLogText.setText(buildStyledLogText());
        if (mLogScroll != null && autoScroll) {
            mLogScroll.post(new Runnable() {
                @Override
                public void run() {
                    mLogScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    private boolean shouldAutoScrollLog() {
        if (mLogScroll == null || mLogScroll.getChildCount() == 0) return true;
        final View child = mLogScroll.getChildAt(0);
        final int distanceFromBottom = child.getBottom()
            - (mLogScroll.getHeight() + mLogScroll.getScrollY());
        return distanceFromBottom <= dp(12);
    }

    private SpannableStringBuilder buildStyledLogText() {
        final SpannableStringBuilder builder = new SpannableStringBuilder();
        for (final String logLine : mLogLines) {
            final int start = builder.length();
            builder.append(logLine).append('\n');
            final int end = builder.length();
            if (isPrimarySlotLogLine(logLine)) {
                builder.setSpan(new ForegroundColorSpan(PRIMARY_SLOT_LOG_COLOR), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return builder;
    }

    private boolean isPrimarySlotLogLine(final String logLine) {
        if (logLine == null) return false;
        return logLine.contains("第1路")
            || logLine.contains("第 1 路")
            || logLine.contains("/stream/0.mjpeg")
            || logLine.contains("/snapshot/0.jpg")
            || logLine.contains("[1]")
            || logLine.contains("S1=");
    }

    private int dp(final int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class CameraSlot {
        final int index;
        final FrameLayout container;
        final TextureView texture;
        final TextureView hiddenTexture;
        final ImageView jpegOverlay;
        final TextView label;
        final AtomicLong frameCount = new AtomicLong();
        final IFrameCallback frameCallback;

        Surface surface;
        Surface hiddenSurface;
        UsbDevice device;
        UVCCamera camera;
        String camera2Id;
        CameraDevice camera2Device;
        CameraCaptureSession camera2Session;
        boolean camera2Opening;
        PreviewChoice preview;
        String supportedSizeJson;
        String status = "EMPTY";
        long lastFrameCount;
        long lastFpsTimestampMs = System.currentTimeMillis();
        long lastJpegOverlayUpdateMs;
        long lastSurfaceCaptureMs;
        long lastSurfaceCaptureLogMs;
        long lastSurfaceCaptureJpegMs;
        long surfaceCaptureJpegCount;
        long openedAtMs;
        boolean firstFrameLogged;
        long lastFrameDebugLogMs;
        int noFrameRetryCount;
        boolean retryPending;
        boolean retryLimitLogged;
        String retryDeviceName;
        long noFrameMonitorId;
        float fps;
        int openSequence;
        int previewFpsMin;
        int previewFpsMax;
        boolean fpsFallback;
        float bandwidthFactor;
        String previewReason;
        String openStrategy;

        CameraSlot(final int slotIndex) {
            index = slotIndex;
            container = new FrameLayout(MainActivity.this);
            container.setPadding(dp(3), dp(3), dp(3), dp(3));
            container.setBackgroundColor(Color.rgb(24, 24, 24));

            texture = new TextureView(MainActivity.this);
            texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(final SurfaceTexture surfaceTexture, final int width, final int height) {
                    surface = new Surface(surfaceTexture);
                    status = "READY";
                    if (mStreamHub != null) {
                        mStreamHub.onSlotReady(index);
                    }
                    refreshLabel();
                    if (mUseCamera2) {
                        openCamera2IfReady();
                    } else {
                        requestNextPermissionIfNeeded();
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(final SurfaceTexture surfaceTexture, final int width, final int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(final SurfaceTexture surfaceTexture) {
                    closeCamera();
                    if (surface != null) {
                        surface.release();
                        surface = null;
                    }
                    status = "SURFACE DESTROYED";
                    if (mStreamHub != null) {
                        mStreamHub.onSlotClosed(index, status);
                    }
                    refreshLabel();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(final SurfaceTexture surfaceTexture) {
                    if (camera2Device != null) {
                        handleCamera2SurfaceUpdated();
                    }
                    handleVisibleSurfaceUpdated();
                }
            });
            container.addView(texture, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            hiddenTexture = new TextureView(MainActivity.this);
            hiddenTexture.setAlpha(0f);
            hiddenTexture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(final SurfaceTexture surfaceTexture,
                    final int width, final int height) {
                    hiddenSurface = new Surface(surfaceTexture);
                }

                @Override
                public void onSurfaceTextureSizeChanged(final SurfaceTexture surfaceTexture,
                    final int width, final int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(final SurfaceTexture surfaceTexture) {
                    releaseHiddenPreviewSurface();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(final SurfaceTexture surfaceTexture) {
                }
            });
            container.addView(hiddenTexture, new FrameLayout.LayoutParams(1, 1,
                Gravity.RIGHT | Gravity.BOTTOM));

            jpegOverlay = new ImageView(MainActivity.this);
            jpegOverlay.setBackgroundColor(Color.BLACK);
            jpegOverlay.setScaleType(ImageView.ScaleType.CENTER_CROP);
            jpegOverlay.setVisibility(View.GONE);
            container.addView(jpegOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            label = new TextView(MainActivity.this);
            label.setTextColor(Color.WHITE);
            label.setTextSize(12);
            label.setBackgroundColor(0xaa000000);
            label.setPadding(dp(6), dp(4), dp(6), dp(4));
            container.addView(label, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));

            frameCallback = new IFrameCallback() {
                @Override
                public void onFrame(final ByteBuffer frame) {
                    final long frames = frameCount.incrementAndGet();
                    final PreviewChoice currentPreview = preview;
                    logFrameDiagnostic(frames, frame, currentPreview);
                    if (mStreamHub != null && currentPreview != null) {
                        if (isRawMjpegPreview(currentPreview)) {
                            mStreamHub.onMjpegFrame(index, frame, currentPreview.width,
                                currentPreview.height);
                            updateJpegOverlayIfNeeded();
                        } else if (isYuyvFallbackPreview(currentPreview)) {
                            mStreamHub.onYuyvFrame(index, frame, currentPreview.width,
                                currentPreview.height);
                            updateJpegOverlayIfNeeded();
                        } else {
                            mStreamHub.onFrame(index, frame, currentPreview.width, currentPreview.height);
                        }
                    }
                }
            };
            refreshLabel();
        }

        void assignCamera2(final String newCamera2Id, final int openSeq) {
            if (newCamera2Id == null) return;
            if (camera2Device != null && newCamera2Id.equals(camera2Id)) {
                return;
            }
            closeCamera();
            camera2Id = newCamera2Id;
            openSequence = openSeq;
            preview = new PreviewChoice(TARGET_WIDTH, TARGET_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG);
            previewReason = "官方Camera2/HAL路径，按cameraIdList顺序打开";
            openStrategy = previewReason;
            status = surface != null ? "READY" : "EMPTY";
            frameCount.set(0);
            lastFrameCount = 0;
            lastFpsTimestampMs = System.currentTimeMillis();
            firstFrameLogged = false;
            lastFrameDebugLogMs = 0;
            refreshLabel();
            openCamera2IfReady();
        }

        void handleCamera2SurfaceUpdated() {
            final long frames = frameCount.incrementAndGet();
            final long now = System.currentTimeMillis();
            if (!firstFrameLogged || now - lastFrameDebugLogMs > FRAME_DEBUG_LOG_INTERVAL_MS) {
                firstFrameLogged = true;
                lastFrameDebugLogMs = now;
                addLog("Camera2：第" + (index + 1) + "路画面"
                    + (frames == 1 ? "首次到达" : "持续更新")
                    + "，cameraId=" + camera2Id + "，帧数=" + frames);
            }
        }

        void openCamera2IfReady() {
            if (!mUseCamera2 || camera2Id == null || camera2Device != null || camera2Opening) {
                return;
            }
            if (surface == null || texture.getSurfaceTexture() == null) {
                addLog("Camera2：第" + (index + 1) + "路等待预览Surface就绪，id=" + camera2Id);
                return;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || mCamera2Manager == null) {
                status = "OPEN FAILED: Camera2不可用";
                refreshLabel();
                return;
            }
            if (!ensureCamera2Permission()) return;

            try {
                texture.getSurfaceTexture().setDefaultBufferSize(TARGET_WIDTH, TARGET_HEIGHT);
                camera2Opening = true;
                status = "OPENING";
                refreshLabel();
                final String openCameraId = camera2Id;
                addLog("Camera2：准备打开第" + (index + 1) + "路，cameraId="
                    + openCameraId + "，surface=已就绪，打开序号=#" + openSequence);
                mCamera2Manager.openCamera(openCameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(final CameraDevice cameraDevice) {
                        camera2Opening = false;
                        camera2Device = cameraDevice;
                        status = "OPEN";
                        addLog("Camera2：第" + (index + 1) + "路 open 成功，cameraId="
                            + openCameraId);
                        createCamera2PreviewSession(openCameraId, cameraDevice);
                    }

                    @Override
                    public void onDisconnected(final CameraDevice cameraDevice) {
                        addLog("Camera2：第" + (index + 1) + "路已断开，cameraId="
                            + openCameraId);
                        camera2Opening = false;
                        if (camera2Device == cameraDevice) {
                            camera2Device = null;
                        }
                        try {
                            cameraDevice.close();
                        } catch (final Exception ignored) {
                        }
                        status = surface != null ? "READY" : "EMPTY";
                        refreshLabel();
                    }

                    @Override
                    public void onError(final CameraDevice cameraDevice, final int error) {
                        addLog("Camera2：第" + (index + 1) + "路打开错误，cameraId="
                            + openCameraId + "，error=" + error);
                        camera2Opening = false;
                        if (camera2Device == cameraDevice) {
                            camera2Device = null;
                        }
                        try {
                            cameraDevice.close();
                        } catch (final Exception ignored) {
                        }
                        status = "OPEN FAILED: Camera2 error=" + error;
                        if (mStreamHub != null) {
                            mStreamHub.onSlotClosed(index, status);
                        }
                        refreshLabel();
                    }
                }, null);
            } catch (final SecurityException e) {
                camera2Opening = false;
                status = "OPEN FAILED: 无Camera权限";
                addLog("Camera2：第" + (index + 1) + "路打开失败，无Camera权限");
                refreshLabel();
            } catch (final Exception e) {
                camera2Opening = false;
                status = "OPEN FAILED: " + e.getMessage();
                addLog("Camera2：第" + (index + 1) + "路打开异常：" + e.getMessage());
                refreshLabel();
            }
        }

        void createCamera2PreviewSession(final String openCameraId,
            final CameraDevice cameraDevice) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
            try {
                final Surface previewSurface = surface;
                if (previewSurface == null) {
                    addLog("Camera2：第" + (index + 1) + "路预览Surface为空，等待重试");
                    return;
                }
                cameraDevice.createCaptureSession(Arrays.asList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(final CameraCaptureSession session) {
                            if (camera2Device != cameraDevice) {
                                try {
                                    session.close();
                                } catch (final Exception ignored) {
                                }
                                return;
                            }
                            camera2Session = session;
                            try {
                                final CaptureRequest.Builder builder =
                                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                builder.addTarget(previewSurface);
                                final Range<Integer> fpsRange = chooseCamera2FpsRange(openCameraId);
                                if (fpsRange != null) {
                                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                                }
                                session.setRepeatingRequest(builder.build(), null, null);
                                status = "OPEN";
                                openedAtMs = System.currentTimeMillis();
                                addLog("Camera2：第" + (index + 1)
                                    + "路预览已启动，cameraId=" + openCameraId
                                    + "，尺寸=" + TARGET_WIDTH + "x" + TARGET_HEIGHT
                                    + "，fpsRange=" + formatCamera2FpsRange(fpsRange)
                                    + "，抓图间隔=" + SURFACE_CAPTURE_INTERVAL_MS + "ms");
                                if (mStreamHub != null) {
                                    mStreamHub.onSlotOpened(index,
                                        "Camera2 id=" + openCameraId,
                                        TARGET_WIDTH, TARGET_HEIGHT, "Camera2Surface",
                                        openSequence, 0, 0, false, 0f,
                                        previewReason, false);
                                }
                                refreshLabel();
                            } catch (final Exception e) {
                                status = "OPEN FAILED: " + e.getMessage();
                                addLog("Camera2：第" + (index + 1)
                                    + "路启动预览失败：" + e.getMessage());
                                if (mStreamHub != null) {
                                    mStreamHub.onSlotClosed(index, status);
                                }
                                refreshLabel();
                            }
                        }

                        @Override
                        public void onConfigureFailed(final CameraCaptureSession session) {
                            status = "OPEN FAILED: Camera2 session configure failed";
                            addLog("Camera2：第" + (index + 1)
                                + "路预览Session配置失败，cameraId=" + openCameraId);
                            if (mStreamHub != null) {
                                mStreamHub.onSlotClosed(index, status);
                            }
                            refreshLabel();
                        }
                    }, null);
            } catch (final CameraAccessException e) {
                status = "OPEN FAILED: " + e.getMessage();
                addLog("Camera2：第" + (index + 1) + "路创建Session异常：" + e.getMessage());
                if (mStreamHub != null) {
                    mStreamHub.onSlotClosed(index, status);
                }
                refreshLabel();
            }
        }

        boolean isFree() {
            return camera == null && camera2Device == null && !camera2Opening;
        }

        Surface ensureHiddenPreviewSurface(final int width, final int height) {
            final SurfaceTexture texture = hiddenTexture.getSurfaceTexture();
            if (texture == null) return hiddenSurface;
            texture.setDefaultBufferSize(width, height);
            if (hiddenSurface == null) {
                hiddenSurface = new Surface(texture);
            }
            return hiddenSurface;
        }

        void releaseHiddenPreviewSurface() {
            if (hiddenSurface != null) {
                hiddenSurface.release();
                hiddenSurface = null;
            }
        }

        void clearJpegOverlay() {
            lastJpegOverlayUpdateMs = 0;
            jpegOverlay.setImageDrawable(null);
            jpegOverlay.setVisibility(View.GONE);
        }

        void prepareYuyvVisibleSurfaceFallback() {
            prepareJpegOverlayFallback();
        }

        void prepareJpegOverlayFallback() {
            lastJpegOverlayUpdateMs = 0;
            jpegOverlay.setImageDrawable(null);
            jpegOverlay.setBackgroundColor(Color.BLACK);
            jpegOverlay.setVisibility(View.VISIBLE);
        }

        void updateJpegOverlayIfNeeded() {
            if (mStreamHub == null) return;
            final long now = System.currentTimeMillis();
            if (now - lastJpegOverlayUpdateMs < JPEG_OVERLAY_UPDATE_INTERVAL_MS) return;
            lastJpegOverlayUpdateMs = now;
            final byte[] jpeg = mStreamHub.getLatestJpegData(index);
            if (jpeg == null || jpeg.length == 0) return;
            final Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (bitmap == null) return;
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (preview == null || !usesJpegOverlayPreview(preview)) return;
                    jpegOverlay.setImageBitmap(bitmap);
                    jpegOverlay.setVisibility(View.VISIBLE);
                }
            });
        }

        void handleVisibleSurfaceUpdated() {
            final PreviewChoice currentPreview = preview;
            if (!shouldCaptureSurfaceFallback(currentPreview)) return;

            final long now = System.currentTimeMillis();
            if (now - lastSurfaceCaptureMs < SURFACE_CAPTURE_INTERVAL_MS) return;
            lastSurfaceCaptureMs = now;
            captureSurfaceJpegFallback(currentPreview, now);
        }

        boolean shouldCaptureSurfaceFallback(final PreviewChoice currentPreview) {
            if (mStreamHub == null || !"OPEN".equals(status) || currentPreview == null) {
                return false;
            }
            if (camera2Device != null) {
                return true;
            }
            return index == 0 && camera != null && frameCount.get() == 0;
        }

        void captureSurfaceJpegFallback(final PreviewChoice currentPreview, final long now) {
            Bitmap bitmap = null;
            try {
                bitmap = texture.getBitmap(currentPreview.width, currentPreview.height);
                if (bitmap == null) {
                    logSurfaceCaptureThrottled("强诊断：第1路Surface抓图失败：bitmap为空", now);
                    return;
                }
                if (bitmap.isRecycled()) {
                    logSurfaceCaptureThrottled("强诊断：第1路Surface抓图失败：bitmap已回收", now);
                    return;
                }
                final int bitmapWidth = bitmap.getWidth();
                final int bitmapHeight = bitmap.getHeight();

                final ByteArrayOutputStream jpegOut = new ByteArrayOutputStream(
                    Math.max(1024, bitmapWidth * bitmapHeight / 4));
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, SURFACE_CAPTURE_JPEG_QUALITY, jpegOut)) {
                    logSurfaceCaptureThrottled("强诊断：第1路Surface抓图失败：JPEG压缩失败", now);
                    return;
                }

                final byte[] jpeg = jpegOut.toByteArray();
                mStreamHub.onSurfaceJpegFrame(index, jpeg, bitmapWidth, bitmapHeight);
                lastSurfaceCaptureJpegMs = now;
                surfaceCaptureJpegCount++;
                if (usesJpegOverlayPreview(currentPreview)) {
                    final Bitmap overlayBitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                    if (overlayBitmap != null) {
                        jpegOverlay.setImageBitmap(overlayBitmap);
                        jpegOverlay.setVisibility(View.VISIBLE);
                    }
                }
                if (camera2Device != null) {
                    logSurfaceCaptureThrottled("Camera2：第" + (index + 1)
                        + "路TextureView抓图已生成HTTP JPEG，cameraId=" + camera2Id
                        + "，SurfaceJPEG=" + surfaceCaptureJpegCount, now);
                } else {
                    logSurfaceCaptureThrottled("强诊断：第1路Surface抓图兜底已生成JPEG，预览="
                        + currentPreview.width + "x" + currentPreview.height + " "
                        + currentPreview.formatName() + "，SurfaceJPEG=" + surfaceCaptureJpegCount
                        + "，frameCallback仍为0", now);
                }
            } catch (final Exception e) {
                logSurfaceCaptureThrottled("强诊断：第1路Surface抓图异常：" + e.getMessage(), now);
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
        }

        void logSurfaceCaptureThrottled(final String message, final long now) {
            if (now - lastSurfaceCaptureLogMs < SURFACE_CAPTURE_LOG_INTERVAL_MS) return;
            lastSurfaceCaptureLogMs = now;
            addLog(message);
        }

        void logFrameDiagnostic(final long frames, final ByteBuffer frame, final PreviewChoice currentPreview) {
            final long now = System.currentTimeMillis();
            if (firstFrameLogged && now - lastFrameDebugLogMs < FRAME_DEBUG_LOG_INTERVAL_MS) return;
            firstFrameLogged = true;
            lastFrameDebugLogMs = now;
            if (noFrameRetryCount > 0 && !isRawMjpegPreview(currentPreview)) {
                addLog("自动重试：第" + (index + 1) + "路重试后已收到帧，帧数=" + frames);
                noFrameRetryCount = 0;
                retryDeviceName = null;
                retryLimitLogged = false;
            }
            retryPending = false;
            final int bufferBytes = frame != null ? frame.asReadOnlyBuffer().remaining() : -1;
            final String previewText = currentPreview != null
                ? currentPreview.width + "x" + currentPreview.height + " " + currentPreview.formatName()
                : "预览参数未就绪";
            addLog("强诊断：第" + (index + 1) + "路帧回调"
                + (frames == 1 ? "首次到达" : "持续到达")
                + "，帧数=" + frames + "，buffer=" + bufferBytes + "，" + previewText
                    + (isRawMjpegPreview(currentPreview) ? "，MJPEG原始帧将直通/拼接为JPEG"
                        : (isYuyvFallbackPreview(currentPreview) ? "，YUYV RAW将转JPEG" : "")));
        }

        void refreshFps() {
            final long now = System.currentTimeMillis();
            final long frames = frameCount.get();
            final long elapsed = now - lastFpsTimestampMs;
            if (elapsed > 0) {
                fps = (frames - lastFrameCount) * 1000f / elapsed;
            }
            lastFrameCount = frames;
            lastFpsTimestampMs = now;
            if (mStreamHub != null) {
                mStreamHub.onSlotFps(index, status, frames, fps);
            }
            checkNoFrameRetry(false);
            refreshLabel();
        }

        void startNoFrameRetryMonitor() {
            final long monitorId = ++noFrameMonitorId;
            addLog("自动重试监控：第" + (index + 1) + "路已启动，"
                + (NO_FRAME_RETRY_WAIT_MS / 1000) + "秒后检查无帧状态");
            mUiHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (monitorId != noFrameMonitorId) return;
                    checkNoFrameRetry(true);
                }
            }, NO_FRAME_RETRY_WAIT_MS);
        }

        void checkNoFrameRetry(final boolean verbose) {
            final long now = System.currentTimeMillis();
            final long frames = frameCount.get();
            if (verbose) {
                addLog("自动重试监控：第" + (index + 1) + "路检查结果，状态=" + displayStatus(status)
                    + "，frames=" + frames
                    + "，fps=" + String.format(Locale.US, "%.1f", fps)
                    + "，camera=" + (camera != null ? "存在" : "无")
                    + "，retry=" + noFrameRetryCount + "/" + maxNoFrameRetries());
            }
            if (!"OPEN".equals(status)) {
                if (verbose) addLog("自动重试监控：第" + (index + 1) + "路未重试，当前不是已打开状态");
                return;
            }
            if (camera == null) {
                if (verbose) addLog("自动重试监控：第" + (index + 1) + "路未重试，camera为空");
                return;
            }
            if (frames > 0) {
                final boolean rawMjpegWaitingForJpeg = isRawMjpegPreview(preview)
                    && (mStreamHub == null || mStreamHub.getLatestJpegData(index) == null);
                if (!rawMjpegWaitingForJpeg) {
                    if (verbose) addLog("自动重试监控：第" + (index + 1) + "路已收到帧，不需要重试");
                    return;
                }
                if (verbose) {
                    addLog("自动重试监控：第" + (index + 1)
                        + "路MJPEG原始帧已有回调但尚未拼出JPEG，继续换档位探测");
                }
            }
            if (lastSurfaceCaptureJpegMs > 0 && now - lastSurfaceCaptureJpegMs < 3000) {
                if (verbose) addLog("自动重试监控：第" + (index + 1)
                    + "路Surface抓图已生成JPEG，暂不重试，继续验证/stream/0.mjpeg");
                return;
            }
            if (retryPending) {
                if (verbose) addLog("自动重试监控：第" + (index + 1) + "路已有重试请求在等待");
                return;
            }
            if (openedAtMs <= 0) {
                if (verbose) addLog("自动重试监控：第" + (index + 1) + "路未重试，打开时间未记录");
                return;
            }
            final long requiredWaitMs = verbose
                ? NO_FRAME_RETRY_WAIT_MS : NO_FRAME_RETRY_WAIT_MS + NO_FRAME_RETRY_FALLBACK_GRACE_MS;
            if (now - openedAtMs < requiredWaitMs) {
                if (verbose) addLog("自动重试监控：第" + (index + 1) + "路未到检查时间");
                return;
            }
            if (shouldPromoteFirstSlotEarly(this)) {
                addLog("第1路健康提升：已完成MJPEG/YUYV/MJPEG-RAW关键兜底，仍未得到有效JPEG，提前切到下一颗UVC");
                promoteNextHealthyDeviceForFirstSlot(this);
                return;
            }
            if (noFrameRetryCount >= maxNoFrameRetries()) {
                if (!retryLimitLogged) {
                    retryLimitLogged = true;
                    addLog("自动重试：第" + (index + 1) + "路仍无帧，已达到最大重试次数，请检查USB带宽/供电/摄像头口");
                    if (shouldPromoteFirstSlotAfterNoFrame(this)) {
                        promoteNextHealthyDeviceForFirstSlot(this);
                    }
                }
                return;
            }
            scheduleNoFrameRetry();
        }

        void scheduleNoFrameRetry() {
            final UsbDevice retryDevice = device;
            if (retryDevice == null || mUSBMonitor == null) return;
            retryPending = true;
            noFrameRetryCount++;
            retryDeviceName = retryDevice.getDeviceName();
            addLog("自动重试：第" + (index + 1) + "路打开后无帧，准备第" + noFrameRetryCount
                + "次重开，策略=" + retryStrategyName(noFrameRetryCount, index));
            closeCamera(false);
            mUiHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mUSBMonitor == null || !isSlotVisible(index)) {
                        retryPending = false;
                        refreshLabel();
                        return;
                    }
                    if (!retryPending || !retryDevice.getDeviceName().equals(retryDeviceName)) return;
                    if (!mQueuedDeviceNames.contains(retryDevice.getDeviceName())) {
                        mPermissionQueue.add(0, retryDevice);
                        mQueuedDeviceNames.add(retryDevice.getDeviceName());
                    }
                    addLog("自动重试：第" + (index + 1) + "路已回到授权队列 "
                        + shortDeviceName(retryDevice) + "，等待错峰重开");
                    requestNextPermissionIfNeeded();
                }
            }, NO_FRAME_RETRY_DELAY_MS);
        }

        void refreshLabel() {
            final StringBuilder sb = new StringBuilder();
            sb.append("第").append(index + 1).append("路 ").append(displayStatus(status));
            if (preview != null) {
                sb.append("\n")
                    .append(preview.width).append("x").append(preview.height)
                    .append(" ")
                    .append(camera2Id != null ? "Camera2Surface" : preview.formatName());
                if (openSequence > 0) {
                    sb.append(" #").append(openSequence);
                }
                if (previewFpsMax > 0) {
                    sb.append(" fps=").append(previewFpsMin).append('-').append(previewFpsMax);
                    if (fpsFallback) {
                        sb.append(" 回退");
                    }
                }
            }
            if (device != null) {
                sb.append("\n").append(shortDeviceName(device));
            }
            if (camera2Id != null) {
                sb.append("\nCamera2 id=").append(camera2Id);
            }
            sb.append("\n帧数=").append(frameCount.get())
                .append(" fps=").append(String.format(Locale.US, "%.1f", fps));
            if (noFrameRetryCount > 0 || retryPending) {
                sb.append("\n自动重试=").append(noFrameRetryCount)
                    .append("/").append(maxNoFrameRetries());
                if (retryPending) {
                    sb.append(" 等待重开");
                }
            }
            if (surfaceCaptureJpegCount > 0) {
                sb.append("\nSurface抓图=").append(surfaceCaptureJpegCount);
            }
            if (mStreamHub != null) {
                sb.append(mStreamHub.getSlotLabelExtra(index));
            }
            label.setText(sb.toString());
        }

        int maxNoFrameRetries() {
            return index == 0 ? MAX_FIRST_SLOT_FULL_PROBE_RETRIES : MAX_NO_FRAME_RETRIES;
        }

        private String displayStatus(final String rawStatus) {
            if (rawStatus == null) return "未知";
            if ("EMPTY".equals(rawStatus)) return "未就绪";
            if ("READY".equals(rawStatus)) return "可打开";
            if ("OPENING".equals(rawStatus)) return "打开中";
            if ("OPEN".equals(rawStatus)) return "已打开";
            if ("SURFACE DESTROYED".equals(rawStatus)) return "预览窗口已销毁";
            if (rawStatus.startsWith("OPEN FAILED")) return "打开失败" + rawStatus.substring("OPEN FAILED".length());
            return rawStatus;
        }

        void closeCamera() {
            closeCamera(true);
        }

        void closeCamera(final boolean resetRetryState) {
            final UVCCamera cameraToClose = camera;
            final CameraCaptureSession camera2SessionToClose = camera2Session;
            final CameraDevice camera2DeviceToClose = camera2Device;
            camera = null;
            camera2Session = null;
            camera2Device = null;
            camera2Opening = false;
            if (device != null) {
                mOpenedByDeviceName.remove(device.getDeviceName());
            }
            device = null;
            camera2Id = null;
            preview = null;
            supportedSizeJson = null;
            openSequence = 0;
            previewFpsMin = 0;
            previewFpsMax = 0;
            fpsFallback = false;
            bandwidthFactor = 0f;
            previewReason = null;
            openStrategy = null;
            fps = 0f;
            firstFrameLogged = false;
            lastFrameDebugLogMs = 0;
            lastJpegOverlayUpdateMs = 0;
            lastSurfaceCaptureMs = 0;
            lastSurfaceCaptureLogMs = 0;
            lastSurfaceCaptureJpegMs = 0;
            surfaceCaptureJpegCount = 0;
            openedAtMs = 0;
            noFrameMonitorId++;
            if (resetRetryState) {
                retryPending = false;
                retryLimitLogged = false;
                noFrameRetryCount = 0;
                retryDeviceName = null;
            }
            frameCount.set(0);
            lastFrameCount = 0;
            clearJpegOverlay();
            releaseHiddenPreviewSurface();
            status = surface != null ? "READY" : "EMPTY";
            if (mStreamHub != null) {
                mStreamHub.onSlotClosed(index, status);
            }

            if (cameraToClose != null) {
                addLog("正在关闭第" + (index + 1) + "路摄像头");
                try {
                    cameraToClose.setFrameCallback(null, 0);
                    cameraToClose.stopPreview();
                } catch (final Exception e) {
                    Log.w(TAG, "stopPreview failed on slot " + (index + 1), e);
                }
                try {
                    cameraToClose.destroy();
                } catch (final Exception e) {
                    Log.w(TAG, "destroy failed on slot " + (index + 1), e);
                }
            }
            if (camera2SessionToClose != null) {
                addLog("正在关闭第" + (index + 1) + "路Camera2预览");
                try {
                    camera2SessionToClose.stopRepeating();
                } catch (final Exception ignored) {
                }
                try {
                    camera2SessionToClose.close();
                } catch (final Exception ignored) {
                }
            }
            if (camera2DeviceToClose != null) {
                try {
                    camera2DeviceToClose.close();
                } catch (final Exception ignored) {
                }
            }
            refreshLabel();
        }
    }

    private static final class PreviewChoice {
        final int width;
        final int height;
        int format;
        boolean rawMjpegCallback;

        PreviewChoice(final int previewWidth, final int previewHeight, final int previewFormat) {
            this(previewWidth, previewHeight, previewFormat, false);
        }

        PreviewChoice(final int previewWidth, final int previewHeight, final int previewFormat,
            final boolean useRawMjpegCallback) {
            width = previewWidth;
            height = previewHeight;
            format = previewFormat;
            rawMjpegCallback = useRawMjpegCallback;
        }

        String formatName() {
            if (rawMjpegCallback && format == UVCCamera.FRAME_FORMAT_MJPEG) {
                return "MJPEG-RAW";
            }
            return format == UVCCamera.FRAME_FORMAT_MJPEG ? "MJPEG" : "YUYV";
        }
    }

    private static final class PreviewSettings {
        final int fpsMin;
        final int fpsMax;
        final boolean fpsFallback;

        PreviewSettings(final int minFps, final int maxFps, final boolean usedFpsFallback) {
            fpsMin = minFps;
            fpsMax = maxFps;
            fpsFallback = usedFpsFallback;
        }
    }
}
