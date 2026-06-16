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
 * The app opens up to four UVC cameras at the same time, shows a preview for every
 * opened device, and registers a frame callback for every camera. If several tiles
 * show non-zero FPS at the same time, the robot can provide multiple video streams
 * concurrently and we can add RTSP/WebRTC/MJPEG output on top of this proof.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "KeenonUvcProbe";

    private static final int MAX_CAMERAS = 4;
    private static final int TARGET_WIDTH = 640;
    private static final int TARGET_HEIGHT = 480;
    private static final int USB_CLASS_VIDEO = 14;
    private static final int STREAM_PORT = 8080;
    private static final int MAX_LOG_LINES = 160;
    private static final float BANDWIDTH_FACTOR = 0.5f;

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final List<UsbDevice> mPermissionQueue = new ArrayList<UsbDevice>();
    private final Set<String> mQueuedDeviceNames = new HashSet<String>();
    private final Map<String, CameraSlot> mOpenedByDeviceName = new HashMap<String, CameraSlot>();

    private USBMonitor mUSBMonitor;
    private CameraStreamHub mStreamHub;
    private CameraSlot[] mSlots;
    private TextView mStatusText;
    private TextView mServerText;
    private TextView mLogText;
    private ScrollView mLogScroll;
    private final List<String> mLogLines = new ArrayList<String>();
    private boolean mWaitingForPermission;
    private String mPermissionDeviceName;
    private int mLastUsbCount;
    private int mLastUvcCount;
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
        addLog("App created. HTTP port=" + STREAM_PORT);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mUSBMonitor.register();
        if (mStreamHub != null) {
            mStreamHub.start();
        }
        updateServerInfo();
        addLog("USB monitor registered");
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

    private View createContentView() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        final LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(6), dp(8), dp(6));

        final Button scanButton = new Button(this);
        scanButton.setText("Scan/Open UVC");
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
        closeButton.setText("Close all");
        closeButton.setAllCaps(false);
        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                closeAllCameras();
                updateStatus("Closed all cameras");
            }
        });
        topBar.addView(closeButton, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mStatusText = new TextView(this);
        mStatusText.setTextColor(Color.WHITE);
        mStatusText.setTextSize(12);
        mStatusText.setPadding(dp(8), 0, 0, 0);
        mStatusText.setText("Waiting for USB scan...");
        topBar.addView(mStatusText, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(topBar, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mServerText = new TextView(this);
        mServerText.setTextColor(Color.rgb(220, 240, 255));
        mServerText.setTextSize(12);
        mServerText.setPadding(dp(8), dp(4), dp(8), dp(4));
        mServerText.setBackgroundColor(Color.rgb(20, 20, 32));
        mServerText.setText("LAN access will appear after HTTP server starts");
        root.addView(mServerText, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(4), 0, dp(4), dp(4));
        root.addView(grid, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        mSlots = new CameraSlot[MAX_CAMERAS];
        for (int row = 0; row < 2; row++) {
            final LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(rowLayout, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            for (int col = 0; col < 2; col++) {
                final int index = row * 2 + col;
                final CameraSlot slot = new CameraSlot(index);
                mSlots[index] = slot;
                rowLayout.addView(slot.container, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
            }
        }

        mLogScroll = new ScrollView(this);
        mLogScroll.setBackgroundColor(Color.rgb(8, 8, 8));
        mLogText = new TextView(this);
        mLogText.setTextColor(Color.rgb(180, 255, 180));
        mLogText.setTextSize(10);
        mLogText.setPadding(dp(8), dp(4), dp(8), dp(4));
        mLogText.setText("Logs will appear here...");
        mLogScroll.addView(mLogText, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(mLogScroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(120)));
        return root;
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
            for (final CameraSlot slot : mSlots) {
                slot.refreshFps();
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

        Log.i(TAG, "USB devices=" + allDevices.size() + ", UVC candidates=" + uvcDevices.size());
        addLog("Scan: USB=" + allDevices.size() + " UVC=" + uvcDevices.size());
        for (final UsbDevice device : allDevices) {
            Log.i(TAG, describeDevice(device));
            addLog("USB: " + describeDevice(device));
        }

        for (final UsbDevice device : uvcDevices) {
            if (!hasFreeReadySlot()) break;
            final String deviceName = device.getDeviceName();
            if (mOpenedByDeviceName.containsKey(deviceName)
                || mQueuedDeviceNames.contains(deviceName)
                || deviceName.equals(mPermissionDeviceName)) {
                continue;
            }
            mPermissionQueue.add(device);
            mQueuedDeviceNames.add(deviceName);
            addLog("Queue permission: " + shortDeviceName(device));
        }

        requestNextPermissionIfNeeded();
        updateStatus("Scan complete");
    }

    private void requestNextPermissionIfNeeded() {
        if (mWaitingForPermission || mPermissionQueue.isEmpty() || !hasFreeReadySlot()) {
            return;
        }

        final UsbDevice device = mPermissionQueue.remove(0);
        mQueuedDeviceNames.remove(device.getDeviceName());
        mWaitingForPermission = true;
        mPermissionDeviceName = device.getDeviceName();

        updateStatus("Requesting USB permission for " + shortDeviceName(device));
        addLog("Request permission: " + shortDeviceName(device));
        final boolean failed = mUSBMonitor.requestPermission(device);
        if (failed) {
            Log.w(TAG, "Failed to request USB permission: " + describeDevice(device));
            addLog("Request permission failed: " + shortDeviceName(device));
            mWaitingForPermission = false;
            mPermissionDeviceName = null;
            requestNextPermissionIfNeeded();
        }
    }

    private final OnDeviceConnectListener mOnDeviceConnectListener = new OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            Log.i(TAG, "onAttach: " + describeDevice(device));
            addLog("USB attached: " + shortDeviceName(device));
            mUiHandler.postDelayed(mScanRunnable, 300);
        }

        @Override
        public void onConnect(final UsbDevice device, final UsbControlBlock ctrlBlock, final boolean createNew) {
            Log.i(TAG, "onConnect: " + describeDevice(device));
            addLog("USB connected: " + shortDeviceName(device));
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
            addLog("USB disconnected: " + shortDeviceName(device));
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    final CameraSlot slot = mOpenedByDeviceName.remove(device.getDeviceName());
                    if (slot != null) {
                        slot.closeCamera();
                    }
                    updateStatus("USB camera disconnected");
                }
            });
        }

        @Override
        public void onDettach(final UsbDevice device) {
            Log.i(TAG, "onDettach: " + describeDevice(device));
            addLog("USB detached: " + shortDeviceName(device));
        }

        @Override
        public void onCancel(final UsbDevice device) {
            Log.w(TAG, "USB permission canceled: " + (device != null ? describeDevice(device) : "null"));
            addLog("USB permission canceled: " + (device != null ? shortDeviceName(device) : "null"));
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    mWaitingForPermission = false;
                    mPermissionDeviceName = null;
                    updateStatus("USB permission canceled");
                    requestNextPermissionIfNeeded();
                }
            });
        }
    };

    private void openConnectedDevice(final UsbDevice device, final UsbControlBlock ctrlBlock) {
        if (mOpenedByDeviceName.containsKey(device.getDeviceName())) {
            updateStatus("Already opened " + shortDeviceName(device));
            return;
        }

        final CameraSlot slot = findFreeReadySlot();
        if (slot == null) {
            updateStatus("No free preview tile. Close a camera and scan again.");
            return;
        }

        UVCCamera camera = null;
        try {
            camera = new UVCCamera();
            camera.open(ctrlBlock);
            final String supportedSize = camera.getSupportedSize();
            final PreviewChoice preview = choosePreviewSize(supportedSize);
            setPreviewSize(camera, preview);

            if (slot.texture.getSurfaceTexture() != null) {
                slot.texture.getSurfaceTexture().setDefaultBufferSize(preview.width, preview.height);
            }
        camera.setPreviewDisplay(slot.surface);
        camera.setFrameCallback(slot.frameCallback, UVCCamera.PIXEL_FORMAT_NV21);
        camera.startPreview();

            slot.device = device;
            slot.camera = camera;
            slot.preview = preview;
            slot.supportedSizeJson = supportedSize;
            slot.status = "OPEN";
            slot.frameCount.set(0);
            slot.lastFrameCount = 0;
            slot.lastFpsTimestampMs = System.currentTimeMillis();
        slot.refreshLabel();
        mOpenedByDeviceName.put(device.getDeviceName(), slot);
        if (mStreamHub != null) {
            mStreamHub.onSlotOpened(slot.index, shortDeviceName(device), preview.width,
                preview.height, preview.formatName());
        }

        Log.i(TAG, "Opened slot " + (slot.index + 1) + ": " + describeDevice(device));
        Log.i(TAG, "Supported sizes slot " + (slot.index + 1) + ": " + supportedSize);
        addLog("Opened slot " + (slot.index + 1) + ": " + shortDeviceName(device)
            + " " + preview.width + "x" + preview.height + " " + preview.formatName());
        updateStatus("Opened " + shortDeviceName(device));
    } catch (final Exception e) {
        Log.e(TAG, "Failed to open camera: " + describeDevice(device), e);
        addLog("Open failed: " + shortDeviceName(device) + " err=" + e.getMessage());
        if (camera != null) {
            try {
                camera.destroy();
                } catch (final Exception ignored) {
                }
        }
        slot.status = "OPEN FAILED: " + e.getMessage();
        if (mStreamHub != null) {
            mStreamHub.onSlotClosed(slot.index, slot.status);
        }
        slot.refreshLabel();
        updateStatus("Open failed. See logcat tag " + TAG);
    }
}

    private void setPreviewSize(final UVCCamera camera, final PreviewChoice preview) {
        try {
            camera.setPreviewSize(preview.width, preview.height, 1, 31, preview.format, BANDWIDTH_FACTOR);
        } catch (final IllegalArgumentException e) {
            if (preview.format == UVCCamera.FRAME_FORMAT_MJPEG) {
                camera.setPreviewSize(preview.width, preview.height, 1, 31,
                    UVCCamera.FRAME_FORMAT_YUYV, BANDWIDTH_FACTOR);
                preview.format = UVCCamera.FRAME_FORMAT_YUYV;
            } else {
                throw e;
            }
        }
    }

    private PreviewChoice choosePreviewSize(final String supportedSizeJson) {
        final List<Size> mjpegSizes = UVCCamera.getSupportedSize(6, supportedSizeJson);
        final Size mjpeg = chooseSize(mjpegSizes);
        if (mjpeg != null) {
            return new PreviewChoice(mjpeg.width, mjpeg.height, UVCCamera.FRAME_FORMAT_MJPEG);
        }

        final List<Size> yuyvSizes = UVCCamera.getSupportedSize(4, supportedSizeJson);
        final Size yuyv = chooseSize(yuyvSizes);
        if (yuyv != null) {
            return new PreviewChoice(yuyv.width, yuyv.height, UVCCamera.FRAME_FORMAT_YUYV);
        }

        return new PreviewChoice(TARGET_WIDTH, TARGET_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG);
    }

    private Size chooseSize(final List<Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;

        Size bestUnderTarget = null;
        Size smallest = null;
        for (final Size size : sizes) {
            if (size.width == TARGET_WIDTH && size.height == TARGET_HEIGHT) {
                return size;
            }
            if (smallest == null || area(size) < area(smallest)) {
                smallest = size;
            }
            if (size.width <= TARGET_WIDTH && size.height <= TARGET_HEIGHT) {
                if (bestUnderTarget == null || area(size) > area(bestUnderTarget)) {
                    bestUnderTarget = size;
                }
            }
        }
        return bestUnderTarget != null ? bestUnderTarget : smallest;
    }

    private int area(final Size size) {
        return size.width * size.height;
    }

    private boolean hasFreeReadySlot() {
        return findFreeReadySlot() != null;
    }

    private CameraSlot findFreeReadySlot() {
        for (final CameraSlot slot : mSlots) {
            if (slot.isFree() && slot.surface != null) {
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
            .append("/").append(MAX_CAMERAS)
            .append(" pending=").append(mPermissionQueue.size());
        if (mWaitingForPermission) {
            sb.append(" waitingPermission");
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
            mServerText.setText("LAN access: " + baseUrl
                + "\nCameras: " + baseUrl + "/cameras"
                + " | Stream0: " + baseUrl + "/stream/0.mjpeg"
                + " | Stream1: " + baseUrl + "/stream/1.mjpeg");
        } else {
            mServerText.setText("HTTP server stopped. Keep app open to stream cameras.");
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
                    frameCount.incrementAndGet();
                    final PreviewChoice currentPreview = preview;
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
            refreshLabel();
        }

        void refreshLabel() {
            final StringBuilder sb = new StringBuilder();
            sb.append("Slot ").append(index + 1).append(" ").append(status);
            if (preview != null) {
                sb.append("\n")
                    .append(preview.width).append("x").append(preview.height)
                    .append(" ").append(preview.formatName());
            }
            if (device != null) {
                sb.append("\n").append(shortDeviceName(device));
            }
            sb.append("\nframes=").append(frameCount.get())
                .append(" fps=").append(String.format(Locale.US, "%.1f", fps));
            if (mStreamHub != null) {
                sb.append(mStreamHub.getSlotLabelExtra(index));
            }
            label.setText(sb.toString());
        }

        void closeCamera() {
            final UVCCamera cameraToClose = camera;
            camera = null;
            if (device != null) {
                mOpenedByDeviceName.remove(device.getDeviceName());
            }
            device = null;
            preview = null;
            supportedSizeJson = null;
            fps = 0f;
            frameCount.set(0);
            lastFrameCount = 0;
            status = surface != null ? "READY" : "EMPTY";
            if (mStreamHub != null) {
                mStreamHub.onSlotClosed(index, status);
            }

            if (cameraToClose != null) {
                addLog("Closing slot " + (index + 1));
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
