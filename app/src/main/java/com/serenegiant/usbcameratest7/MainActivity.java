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

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usb.UVCCamera;

import java.nio.ByteBuffer;
import java.util.ArrayList;
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
    private static final int USB_CLASS_VIDEO = 14;
    private static final int STREAM_PORT = 8080;
    private static final int MAX_LOG_LINES = 220;
    private static final long FRAME_DEBUG_LOG_INTERVAL_MS = 5000;
    private static final long NO_FRAME_RETRY_WAIT_MS = 5000;
    private static final long NO_FRAME_RETRY_FALLBACK_GRACE_MS = 1000;
    private static final long NO_FRAME_RETRY_DELAY_MS = 1200;
    private static final int MAX_NO_FRAME_RETRIES = 2;
    private static final float BANDWIDTH_FACTOR = 0.5f;
    private static final float RETRY_BANDWIDTH_FACTOR = 0.25f;

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final List<UsbDevice> mPermissionQueue = new ArrayList<UsbDevice>();
    private final Set<String> mQueuedDeviceNames = new HashSet<String>();
    private final Map<String, CameraSlot> mOpenedByDeviceName = new HashMap<String, CameraSlot>();

    private USBMonitor mUSBMonitor;
    private CameraStreamHub mStreamHub;
    private CameraSlot[] mSlots;
    private TextView mStatusText;
    private TextView mServerText;
    private Button mLogButton;
    private LinearLayout mSlotGrid;
    private LinearLayout[] mSlotRows;
    private TextView mLogText;
    private ScrollView mLogScroll;
    private final List<String> mLogLines = new ArrayList<String>();
    private boolean mWaitingForPermission;
    private String mPermissionDeviceName;
    private int mLastUsbCount;
    private int mLastUvcCount;
    private int mVisibleSlotCount;
    private long mLastHealthLogMs;

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
        addLog("应用已启动，版本=" + appVersionText() + "，HTTP端口=" + STREAM_PORT);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mUSBMonitor.register();
        if (mStreamHub != null) {
            mStreamHub.start();
        }
        updateServerInfo();
        addLog("USB监听已启动");
        mUiHandler.postDelayed(mScanRunnable, 800);
        mUiHandler.postDelayed(mFpsRunnable, 1000);
    }

    @Override
    protected void onStop() {
        mUiHandler.removeCallbacks(mScanRunnable);
        mUiHandler.removeCallbacks(mFpsRunnable);
        closeAllCameras();
        if (mStreamHub != null) {
            mStreamHub.stop();
        }
        mUSBMonitor.unregister();
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
                scanAndRequestUvcDevices();
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
            scanAndRequestUvcDevices();
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

    private void scanAndRequestUvcDevices() {
        if (mUSBMonitor == null) return;

        final List<UsbDevice> allDevices = mUSBMonitor.getDeviceList();
        final List<UsbDevice> uvcDevices = new ArrayList<UsbDevice>();
        for (final UsbDevice device : allDevices) {
            if (isPotentialUvcDevice(device)) {
                uvcDevices.add(device);
            }
        }

        mLastUsbCount = allDevices.size();
        mLastUvcCount = uvcDevices.size();
        updateVisibleSlots(Math.min(mLastUvcCount, MAX_CAMERAS));

        Log.i(TAG, "USB devices=" + allDevices.size() + ", UVC candidates=" + uvcDevices.size());
        addLog("扫描结果：USB设备=" + allDevices.size() + "，UVC摄像头=" + uvcDevices.size());
        for (final UsbDevice device : allDevices) {
            Log.i(TAG, describeDevice(device));
            addLog("发现USB设备：" + describeDevice(device));
        }

        int queuedOrOpened = mOpenedByDeviceName.size() + mPermissionQueue.size() + countRetryPendingSlots()
            + (mPermissionDeviceName != null ? 1 : 0);
        for (final UsbDevice device : uvcDevices) {
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

    private void requestNextPermissionIfNeeded() {
        if (mWaitingForPermission || mPermissionQueue.isEmpty() || !hasFreeReadySlot()) {
            return;
        }

        final UsbDevice device = mPermissionQueue.remove(0);
        mQueuedDeviceNames.remove(device.getDeviceName());
        mWaitingForPermission = true;
        mPermissionDeviceName = device.getDeviceName();

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
        try {
            addLog("强诊断：准备打开第" + (slot.index + 1) + "路，设备="
                + shortDeviceName(device) + "，surface=" + (slot.surface != null ? "已就绪" : "未就绪"));
            camera = new UVCCamera();
            camera.open(ctrlBlock);
            addLog("强诊断：第" + (slot.index + 1) + "路 native open 成功");
            final String supportedSize = camera.getSupportedSize();
            final List<Size> mjpegSizes = UVCCamera.getSupportedSize(6, supportedSize);
            final List<Size> yuyvSizes = UVCCamera.getSupportedSize(4, supportedSize);
            addLog("强诊断：第" + (slot.index + 1) + "路支持分辨率 MJPEG="
                + formatSizes(mjpegSizes) + "；YUYV=" + formatSizes(yuyvSizes));
            if (slot.noFrameRetryCount > 0) {
                addLog("自动重试：第" + (slot.index + 1) + "路第" + slot.noFrameRetryCount
                    + "次重开，策略=" + retryStrategyName(slot.noFrameRetryCount));
            }
            final PreviewChoice preview = choosePreviewSize(mjpegSizes, yuyvSizes,
                slot.noFrameRetryCount >= 2);
            final float bandwidthFactor = slot.noFrameRetryCount >= 2
                ? RETRY_BANDWIDTH_FACTOR : BANDWIDTH_FACTOR;
            final int requestedFormat = preview.format;
            setPreviewSize(camera, preview, bandwidthFactor);
            if (preview.format != requestedFormat) {
                addLog("强诊断：第" + (slot.index + 1) + "路 MJPEG 设置失败，已切换为 "
                    + preview.formatName());
            }
            addLog("第" + (slot.index + 1) + "路优先低分辨率：选用 "
                + preview.width + "x" + preview.height + " " + preview.formatName()
                + "，" + previewSelectionReason(preview) + "，带宽系数=" + bandwidthFactor);

            if (slot.texture.getSurfaceTexture() != null) {
                slot.texture.getSurfaceTexture().setDefaultBufferSize(preview.width, preview.height);
            }
            camera.setPreviewDisplay(slot.surface);
            addLog("强诊断：第" + (slot.index + 1) + "路预览窗口已绑定");
            camera.setFrameCallback(slot.frameCallback, UVCCamera.PIXEL_FORMAT_NV21);
            addLog("强诊断：第" + (slot.index + 1) + "路帧回调已注册，格式=NV21");
            camera.startPreview();
            addLog("强诊断：第" + (slot.index + 1) + "路 startPreview 已调用，等待帧回调");

            slot.device = device;
            slot.camera = camera;
            slot.preview = preview;
            slot.supportedSizeJson = supportedSize;
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
                    preview.height, preview.formatName());
            }
            slot.startNoFrameRetryMonitor();

            Log.i(TAG, "Opened slot " + (slot.index + 1) + ": " + describeDevice(device));
            Log.i(TAG, "Supported sizes slot " + (slot.index + 1) + ": " + supportedSize);
            addLog("已打开第" + (slot.index + 1) + "路：" + shortDeviceName(device)
                + " " + preview.width + "x" + preview.height + " " + preview.formatName());
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

    private void setPreviewSize(final UVCCamera camera, final PreviewChoice preview,
        final float bandwidthFactor) {
        try {
            camera.setPreviewSize(preview.width, preview.height, 1, 31, preview.format, bandwidthFactor);
        } catch (final IllegalArgumentException e) {
            if (preview.format == UVCCamera.FRAME_FORMAT_MJPEG) {
                camera.setPreviewSize(preview.width, preview.height, 1, 31,
                    UVCCamera.FRAME_FORMAT_YUYV, bandwidthFactor);
                preview.format = UVCCamera.FRAME_FORMAT_YUYV;
            } else {
                throw e;
            }
        }
    }

    private PreviewChoice choosePreviewSize(final String supportedSizeJson) {
        final List<Size> mjpegSizes = UVCCamera.getSupportedSize(6, supportedSizeJson);
        final List<Size> yuyvSizes = UVCCamera.getSupportedSize(4, supportedSizeJson);
        return choosePreviewSize(mjpegSizes, yuyvSizes, false);
    }

    private PreviewChoice choosePreviewSize(final List<Size> mjpegSizes, final List<Size> yuyvSizes,
        final boolean preferYuyv) {
        final List<PreviewChoice> candidates = new ArrayList<PreviewChoice>();
        if (preferYuyv) {
            addPreviewCandidates(candidates, yuyvSizes, UVCCamera.FRAME_FORMAT_YUYV);
            addPreviewCandidates(candidates, mjpegSizes, UVCCamera.FRAME_FORMAT_MJPEG);
        } else {
            addPreviewCandidates(candidates, mjpegSizes, UVCCamera.FRAME_FORMAT_MJPEG);
            addPreviewCandidates(candidates, yuyvSizes, UVCCamera.FRAME_FORMAT_YUYV);
        }

        final PreviewChoice preview = choosePreviewCandidate(candidates, preferYuyv);
        if (preview != null) {
            return preview;
        }

        return new PreviewChoice(TARGET_WIDTH, TARGET_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG);
    }

    private String retryStrategyName(final int retryCount) {
        if (retryCount >= 2) {
            return "优先YUYV并降低带宽系数";
        }
        return "同参数重开";
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
        if (sizes == null) return;
        for (final Size size : sizes) {
            candidates.add(new PreviewChoice(size.width, size.height, format));
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

    private String previewSelectionReason(final PreviewChoice preview) {
        if (preview.width <= TARGET_WIDTH && preview.height <= TARGET_HEIGHT) {
            return "降低带宽压力";
        }
        return "未找到640x480及以下，使用最小支持分辨率";
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
        mPermissionQueue.clear();
        mQueuedDeviceNames.clear();
        mWaitingForPermission = false;
        mPermissionDeviceName = null;
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
        sb.append("USB=").append(mLastUsbCount)
            .append(" UVC=").append(mLastUvcCount)
            .append(" opened=").append(mOpenedByDeviceName.size())
            .append("/").append(mVisibleSlotCount)
            .append(" max=").append(MAX_CAMERAS)
            .append(" pending=").append(mPermissionQueue.size());
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
        final StringBuilder sb = new StringBuilder();
        for (final String logLine : mLogLines) {
            sb.append(logLine).append('\n');
        }
        mLogText.setText(sb.toString());
        if (mLogScroll != null) {
            mLogScroll.post(new Runnable() {
                @Override
                public void run() {
                    mLogScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    private int dp(final int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class CameraSlot {
        final int index;
        final FrameLayout container;
        final TextureView texture;
        final TextView label;
        final AtomicLong frameCount = new AtomicLong();
        final IFrameCallback frameCallback;

        Surface surface;
        UsbDevice device;
        UVCCamera camera;
        PreviewChoice preview;
        String supportedSizeJson;
        String status = "EMPTY";
        long lastFrameCount;
        long lastFpsTimestampMs = System.currentTimeMillis();
        long openedAtMs;
        boolean firstFrameLogged;
        long lastFrameDebugLogMs;
        int noFrameRetryCount;
        boolean retryPending;
        boolean retryLimitLogged;
        String retryDeviceName;
        long noFrameMonitorId;
        float fps;

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
                    requestNextPermissionIfNeeded();
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
                }
            });
            container.addView(texture, new FrameLayout.LayoutParams(
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
                        mStreamHub.onFrame(index, frame, currentPreview.width, currentPreview.height);
                    }
                }
            };
            refreshLabel();
        }

        boolean isFree() {
            return camera == null;
        }

        void logFrameDiagnostic(final long frames, final ByteBuffer frame, final PreviewChoice currentPreview) {
            final long now = System.currentTimeMillis();
            if (firstFrameLogged && now - lastFrameDebugLogMs < FRAME_DEBUG_LOG_INTERVAL_MS) return;
            firstFrameLogged = true;
            lastFrameDebugLogMs = now;
            if (noFrameRetryCount > 0) {
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
                + "，帧数=" + frames + "，buffer=" + bufferBytes + "，" + previewText);
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
                    + "，retry=" + noFrameRetryCount + "/" + MAX_NO_FRAME_RETRIES);
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
                if (verbose) addLog("自动重试监控：第" + (index + 1) + "路已收到帧，不需要重试");
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
            if (noFrameRetryCount >= MAX_NO_FRAME_RETRIES) {
                if (!retryLimitLogged) {
                    retryLimitLogged = true;
                    addLog("自动重试：第" + (index + 1) + "路仍无帧，已达到最大重试次数，请检查USB带宽/供电/摄像头口");
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
                + "次重开，策略=" + retryStrategyName(noFrameRetryCount));
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
                    addLog("自动重试：第" + (index + 1) + "路重新请求打开 " + shortDeviceName(retryDevice));
                    final boolean failed = mUSBMonitor.requestPermission(retryDevice);
                    if (failed) {
                        retryPending = false;
                        addLog("自动重试：第" + (index + 1) + "路重新请求打开失败");
                        refreshLabel();
                    }
                }
            }, NO_FRAME_RETRY_DELAY_MS);
        }

        void refreshLabel() {
            final StringBuilder sb = new StringBuilder();
            sb.append("第").append(index + 1).append("路 ").append(displayStatus(status));
            if (preview != null) {
                sb.append("\n")
                    .append(preview.width).append("x").append(preview.height)
                    .append(" ").append(preview.formatName());
            }
            if (device != null) {
                sb.append("\n").append(shortDeviceName(device));
            }
            sb.append("\n帧数=").append(frameCount.get())
                .append(" fps=").append(String.format(Locale.US, "%.1f", fps));
            if (noFrameRetryCount > 0 || retryPending) {
                sb.append("\n自动重试=").append(noFrameRetryCount)
                    .append("/").append(MAX_NO_FRAME_RETRIES);
                if (retryPending) {
                    sb.append(" 等待重开");
                }
            }
            if (mStreamHub != null) {
                sb.append(mStreamHub.getSlotLabelExtra(index));
            }
            label.setText(sb.toString());
        }

        private String displayStatus(final String rawStatus) {
            if (rawStatus == null) return "未知";
            if ("EMPTY".equals(rawStatus)) return "未就绪";
            if ("READY".equals(rawStatus)) return "可打开";
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
            camera = null;
            if (device != null) {
                mOpenedByDeviceName.remove(device.getDeviceName());
            }
            device = null;
            preview = null;
            supportedSizeJson = null;
            fps = 0f;
            firstFrameLogged = false;
            lastFrameDebugLogMs = 0;
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
            refreshLabel();
        }
    }

    private static final class PreviewChoice {
        final int width;
        final int height;
        int format;

        PreviewChoice(final int previewWidth, final int previewHeight, final int previewFormat) {
            width = previewWidth;
            height = previewHeight;
            format = previewFormat;
        }

        String formatName() {
            return format == UVCCamera.FRAME_FORMAT_MJPEG ? "MJPEG" : "YUYV";
        }
    }
}
