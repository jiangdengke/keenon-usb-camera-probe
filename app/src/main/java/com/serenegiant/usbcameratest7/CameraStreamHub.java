package com.serenegiant.usbcameratest7;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small in-process HTTP server that exposes opened UVC camera frames as MJPEG.
 */
final class CameraStreamHub {
    interface LogSink {
        void log(String message);
    }

    private static final String TAG = "KeenonStreamHub";
    private static final int JPEG_QUALITY = 60;
    private static final int MIN_JPEG_INTERVAL_MS = 200;
    private static final int CLIENT_IDLE_SLEEP_MS = 100;
    private static final long DIAGNOSTIC_LOG_INTERVAL_MS = 5000;
    private static final String BOUNDARY = "keenonframe";

    private final SlotState[] mSlots;
    private final int mPort;
    private final LogSink mLogSink;

    private ServerSocket mServerSocket;
    private ExecutorService mClientExecutor;
    private Thread mAcceptThread;
    private volatile boolean mRunning;
    private volatile int mActiveSlotCount;
    private volatile String mBaseUrl;

    CameraStreamHub(final int slotCount, final int port, final LogSink logSink) {
        mSlots = new SlotState[slotCount];
        for (int i = 0; i < slotCount; i++) {
            mSlots[i] = new SlotState(i);
        }
        mPort = port;
        mLogSink = logSink;
        mBaseUrl = "http://" + resolveLocalIpAddress() + ":" + port;
    }

    synchronized void start() {
        if (mRunning) return;
        try {
            mServerSocket = new ServerSocket(mPort);
            mServerSocket.setReuseAddress(true);
            mClientExecutor = Executors.newCachedThreadPool();
            mRunning = true;
            mBaseUrl = "http://" + resolveLocalIpAddress() + ":" + mPort;
            mAcceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            }, "KeenonMjpegAccept");
            mAcceptThread.start();
            log("HTTP拉流服务已启动：" + mBaseUrl);
        } catch (final IOException e) {
            mRunning = false;
            closeQuietly(mServerSocket);
            mServerSocket = null;
            log("HTTP拉流服务启动失败：" + e.getMessage());
        }
    }

    synchronized void stop() {
        if (!mRunning) return;
        mRunning = false;
        closeQuietly(mServerSocket);
        mServerSocket = null;
        if (mClientExecutor != null) {
            mClientExecutor.shutdownNow();
            mClientExecutor = null;
        }
        log("HTTP拉流服务已停止");
    }

    boolean isRunning() {
        return mRunning;
    }

    String getBaseUrl() {
        return mBaseUrl;
    }

    void setActiveSlotCount(final int activeSlotCount) {
        mActiveSlotCount = Math.max(0, Math.min(activeSlotCount, mSlots.length));
    }

    String getStreamUrl(final int slotIndex) {
        return mBaseUrl + "/stream/" + slotIndex + ".mjpeg";
    }

    String getSlotLabelExtra(final int slotIndex) {
        if (!isValidSlot(slotIndex)) return "";
        final SlotState slot = mSlots[slotIndex];
        final long now = System.currentTimeMillis();
        final long jpegAge = slot.latestJpegTimestampMs > 0
            ? Math.max(0, now - slot.latestJpegTimestampMs) : -1;
        final StringBuilder sb = new StringBuilder();
        sb.append("\n拉流=/stream/").append(slotIndex).append(".mjpeg");
        sb.append("\nJPEG帧=").append(slot.jpegCount.get());
        if (jpegAge >= 0) {
            sb.append(" 延迟=").append(jpegAge).append("ms");
        } else {
            sb.append(" 延迟=暂无");
        }
        sb.append("\n诊断=").append(slot.diagnosis(now));
        return sb.toString();
    }

    String buildHealthSummary() {
        final StringBuilder sb = new StringBuilder("健康状态：");
        for (int i = 0; i < mActiveSlotCount; i++) {
            final SlotState slot = mSlots[i];
            sb.append(' ').append(slot.shortSummary());
        }
        return sb.toString();
    }

    void onSlotReady(final int slotIndex) {
        if (!isValidSlot(slotIndex)) return;
        final SlotState slot = mSlots[slotIndex];
        slot.status = "READY";
    }

    void onSlotOpened(final int slotIndex, final String deviceLabel, final int width,
        final int height, final String formatName, final int openSequence,
        final int fpsMin, final int fpsMax, final float bandwidthFactor,
        final String selectionReason, final boolean lowBandwidthMode) {
        if (!isValidSlot(slotIndex)) return;
        final SlotState slot = mSlots[slotIndex];
        slot.status = "OPEN";
        slot.deviceLabel = deviceLabel;
        slot.width = width;
        slot.height = height;
        slot.formatName = formatName;
        slot.openSequence = openSequence;
        slot.fpsMin = fpsMin;
        slot.fpsMax = fpsMax;
        slot.bandwidthFactor = bandwidthFactor;
        slot.selectionReason = selectionReason;
        slot.lowBandwidthMode = lowBandwidthMode;
        slot.latestJpegData = null;
        slot.latestJpegTimestampMs = 0;
        slot.latestFrameCallbackMs = 0;
        slot.lastFrameBufferBytes = 0;
        slot.lastJpegSuccessLogMs = 0;
        slot.lastJpegWarnLogMs = 0;
        slot.jpegCount.set(0);
        log("第" + (slotIndex + 1) + "路拉流已就绪：" + getStreamUrl(slotIndex)
            + "，打开序号=#" + openSequence + "，fps=" + fpsMin + "-" + fpsMax
            + "，带宽系数=" + bandwidthFactor + "，策略=" + selectionReason);
    }

    void onSlotClosed(final int slotIndex, final String status) {
        if (!isValidSlot(slotIndex)) return;
        final SlotState slot = mSlots[slotIndex];
        slot.status = status;
        slot.deviceLabel = null;
        slot.width = 0;
        slot.height = 0;
        slot.formatName = null;
        slot.openSequence = 0;
        slot.fpsMin = 0;
        slot.fpsMax = 0;
        slot.bandwidthFactor = 0f;
        slot.selectionReason = null;
        slot.lowBandwidthMode = false;
        slot.fps = 0f;
        slot.frameCount = 0;
        slot.latestJpegData = null;
        slot.latestJpegTimestampMs = 0;
        slot.latestFrameCallbackMs = 0;
        slot.lastFrameBufferBytes = 0;
        slot.lastJpegSuccessLogMs = 0;
        slot.lastJpegWarnLogMs = 0;
        slot.jpegCount.set(0);
    }

    void onSlotFps(final int slotIndex, final String status, final long frameCount, final float fps) {
        if (!isValidSlot(slotIndex)) return;
        final SlotState slot = mSlots[slotIndex];
        slot.status = status;
        slot.frameCount = frameCount;
        slot.fps = fps;
    }

    void onFrame(final int slotIndex, final ByteBuffer frame, final int width, final int height) {
        if (!isValidSlot(slotIndex) || frame == null || width <= 0 || height <= 0) return;
        final SlotState slot = mSlots[slotIndex];
        final long now = System.currentTimeMillis();
        final int expectedSize = width * height * 3 / 2;
        final ByteBuffer source = frame.asReadOnlyBuffer();
        source.clear();
        slot.latestFrameCallbackMs = now;
        slot.lastFrameBufferBytes = source.remaining();
        if (now - slot.lastJpegEncodeMs < MIN_JPEG_INTERVAL_MS) return;
        slot.lastJpegEncodeMs = now;

        try {
            if (source.remaining() < expectedSize) {
                logJpegWarning(slot, now, "第" + (slotIndex + 1) + "路帧数据不足：buffer="
                    + source.remaining() + "，期望=" + expectedSize + "，可能是格式或回调数据异常");
                return;
            }

            final byte[] nv21 = new byte[expectedSize];
            source.get(nv21);

            final ByteArrayOutputStream jpegOut = new ByteArrayOutputStream(expectedSize / 4);
            final YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            if (yuvImage.compressToJpeg(new Rect(0, 0, width, height), JPEG_QUALITY, jpegOut)) {
                slot.latestJpegData = jpegOut.toByteArray();
                slot.latestJpegTimestampMs = now;
                final long jpegFrames = slot.jpegCount.incrementAndGet();
                if (jpegFrames == 1 || now - slot.lastJpegSuccessLogMs > DIAGNOSTIC_LOG_INTERVAL_MS) {
                    slot.lastJpegSuccessLogMs = now;
                    log("强诊断：第" + (slotIndex + 1) + "路JPEG已生成，JPEG帧="
                        + jpegFrames + "，大小=" + slot.latestJpegData.length + "字节");
                }
            } else {
                logJpegWarning(slot, now, "第" + (slotIndex + 1) + "路JPEG编码返回失败："
                    + width + "x" + height + " NV21");
            }
        } catch (final Exception e) {
            logJpegWarning(slot, now, "第" + (slotIndex + 1) + "路JPEG编码异常：" + e.getMessage());
        }
    }

    private void logJpegWarning(final SlotState slot, final long now, final String message) {
        if (now - slot.lastJpegWarnLogMs > DIAGNOSTIC_LOG_INTERVAL_MS) {
            slot.lastJpegWarnLogMs = now;
            log(message);
        }
    }

    private void acceptLoop() {
        while (mRunning) {
            try {
                final Socket socket = mServerSocket.accept();
                final ExecutorService executor = mClientExecutor;
                if (executor != null) {
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            handleClient(socket);
                        }
                    });
                } else {
                    closeQuietly(socket);
                }
            } catch (final IOException e) {
                if (mRunning) {
                    log("HTTP客户端接入失败：" + e.getMessage());
                }
            }
        }
    }

    private void handleClient(final Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            final BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), "US-ASCII"));
            final String requestLine = reader.readLine();
            if (requestLine == null) return;

            String headerLine;
            while ((headerLine = reader.readLine()) != null && headerLine.length() > 0) {
                // Consume headers; this lightweight server only supports GET.
            }

            final String path = parsePath(requestLine);
            final OutputStream out = socket.getOutputStream();
            if ("/".equals(path)) {
                sendText(out, "200 OK", "text/plain; charset=utf-8", buildIndexText());
            } else if ("/cameras".equals(path)) {
                sendText(out, "200 OK", "application/json; charset=utf-8", buildCamerasJson());
            } else if (path.startsWith("/snapshot/")) {
                handleSnapshot(path, out);
            } else if (path.startsWith("/stream/")) {
                handleStream(path, socket, out);
            } else {
                sendText(out, "404 Not Found", "text/plain; charset=utf-8", "Not found\n");
            }
        } catch (final Exception e) {
            Log.w(TAG, "HTTP client failed", e);
        } finally {
            closeQuietly(socket);
        }
    }

    private String parsePath(final String requestLine) {
        final String[] parts = requestLine.split(" ");
        if (parts.length < 2 || !"GET".equals(parts[0])) return "/unsupported";
        final int queryIndex = parts[1].indexOf('?');
        return queryIndex >= 0 ? parts[1].substring(0, queryIndex) : parts[1];
    }

    private void handleSnapshot(final String path, final OutputStream out) throws IOException {
        final int slotIndex = parseSlotIndex(path, "/snapshot/", ".jpg");
        if (!isValidSlot(slotIndex)) {
            sendText(out, "404 Not Found", "text/plain; charset=utf-8", "Invalid camera slot\n");
            return;
        }
        final byte[] jpeg = mSlots[slotIndex].latestJpegData;
        if (jpeg == null) {
            sendText(out, "503 Service Unavailable", "text/plain; charset=utf-8", "No JPEG frame yet\n");
            return;
        }
        writeHeaders(out, "200 OK", "image/jpeg", jpeg.length);
        out.write(jpeg);
        out.flush();
    }

    private void handleStream(final String path, final Socket socket, final OutputStream out)
        throws IOException, InterruptedException {
        final int slotIndex = parseSlotIndex(path, "/stream/", ".mjpeg");
        if (!isValidSlot(slotIndex)) {
            sendText(out, "404 Not Found", "text/plain; charset=utf-8", "Invalid camera slot\n");
            return;
        }
        final SlotState slot = mSlots[slotIndex];
        log("MJPEG客户端已连接：第" + (slotIndex + 1) + "路，来源="
            + socket.getInetAddress().getHostAddress());
        writeStreamHeaders(out);

        long lastSentFrameMs = 0;
        while (mRunning && !socket.isClosed()) {
            final byte[] jpeg = slot.latestJpegData;
            final long frameMs = slot.latestJpegTimestampMs;
            if (jpeg != null && frameMs != lastSentFrameMs) {
                writeMjpegFrame(out, jpeg);
                lastSentFrameMs = frameMs;
            } else {
                Thread.sleep(CLIENT_IDLE_SLEEP_MS);
            }
        }
    }

    private int parseSlotIndex(final String path, final String prefix, final String extension) {
        if (!path.startsWith(prefix)) return -1;
        String value = path.substring(prefix.length());
        if (value.endsWith(extension)) {
            value = value.substring(0, value.length() - extension.length());
        }
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    private String buildIndexText() {
        final StringBuilder sb = new StringBuilder();
        sb.append("Keenon UVC Multi Probe\n");
        sb.append("Base URL: ").append(mBaseUrl).append("\n");
        sb.append("GET ").append(mBaseUrl).append("/cameras\n");
        for (int i = 0; i < mActiveSlotCount; i++) {
            sb.append("GET ").append(mBaseUrl).append("/stream/").append(i).append(".mjpeg\n");
            sb.append("GET ").append(mBaseUrl).append("/snapshot/").append(i).append(".jpg\n");
        }
        return sb.toString();
    }

    private String buildCamerasJson() {
        final long now = System.currentTimeMillis();
        final StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"baseUrl\":\"").append(jsonEscape(mBaseUrl)).append("\",");
        sb.append("\"cameras\":[");
        for (int i = 0; i < mActiveSlotCount; i++) {
            if (i > 0) sb.append(',');
            final SlotState slot = mSlots[i];
            final long jpegAgeMs = slot.latestJpegTimestampMs > 0 ? now - slot.latestJpegTimestampMs : -1;
            sb.append('{')
                .append("\"slot\":").append(i).append(',')
                .append("\"status\":\"").append(jsonEscape(slot.status)).append("\",")
                .append("\"device\":\"").append(jsonEscape(slot.deviceLabel)).append("\",")
                .append("\"width\":").append(slot.width).append(',')
                .append("\"height\":").append(slot.height).append(',')
                .append("\"format\":\"").append(jsonEscape(slot.formatName)).append("\",")
                .append("\"openSequence\":").append(slot.openSequence).append(',')
                .append("\"fpsMin\":").append(slot.fpsMin).append(',')
                .append("\"fpsMax\":").append(slot.fpsMax).append(',')
                .append("\"bandwidthFactor\":")
                .append(String.format(Locale.US, "%.2f", slot.bandwidthFactor)).append(',')
                .append("\"lowBandwidthMode\":").append(slot.lowBandwidthMode).append(',')
                .append("\"selectionReason\":\"").append(jsonEscape(slot.selectionReason)).append("\",")
                .append("\"frames\":").append(slot.frameCount).append(',')
                .append("\"fps\":").append(String.format(Locale.US, "%.1f", slot.fps)).append(',')
                .append("\"lastFrameAgeMs\":").append(slot.latestFrameCallbackMs > 0
                    ? now - slot.latestFrameCallbackMs : -1).append(',')
                .append("\"lastFrameBytes\":").append(slot.lastFrameBufferBytes).append(',')
                .append("\"jpegFrames\":").append(slot.jpegCount.get()).append(',')
                .append("\"jpegAgeMs\":").append(jpegAgeMs).append(',')
                .append("\"diagnosis\":\"").append(jsonEscape(slot.diagnosis(now))).append("\",")
                .append("\"stream\":\"/stream/").append(i).append(".mjpeg\",")
                .append("\"snapshot\":\"/snapshot/").append(i).append(".jpg\",")
                .append("\"streamUrl\":\"").append(jsonEscape(mBaseUrl)).append("/stream/")
                .append(i).append(".mjpeg\",")
                .append("\"snapshotUrl\":\"").append(jsonEscape(mBaseUrl)).append("/snapshot/")
                .append(i).append(".jpg\"")
                .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private String jsonEscape(final String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void sendText(final OutputStream out, final String status, final String contentType,
        final String body) throws IOException {
        final byte[] bytes = body.getBytes("UTF-8");
        writeHeaders(out, status, contentType, bytes.length);
        out.write(bytes);
        out.flush();
    }

    private void writeHeaders(final OutputStream out, final String status, final String contentType,
        final int contentLength) throws IOException {
        final String headers = "HTTP/1.1 " + status + "\r\n"
            + "Content-Type: " + contentType + "\r\n"
            + "Content-Length: " + contentLength + "\r\n"
            + "Cache-Control: no-cache\r\n"
            + "Access-Control-Allow-Origin: *\r\n"
            + "Connection: close\r\n\r\n";
        out.write(headers.getBytes("US-ASCII"));
    }

    private void writeStreamHeaders(final OutputStream out) throws IOException {
        final String headers = "HTTP/1.1 200 OK\r\n"
            + "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n"
            + "Cache-Control: no-cache\r\n"
            + "Access-Control-Allow-Origin: *\r\n"
            + "Connection: close\r\n\r\n";
        out.write(headers.getBytes("US-ASCII"));
        out.flush();
    }

    private void writeMjpegFrame(final OutputStream out, final byte[] jpeg) throws IOException {
        final String headers = "--" + BOUNDARY + "\r\n"
            + "Content-Type: image/jpeg\r\n"
            + "Content-Length: " + jpeg.length + "\r\n\r\n";
        out.write(headers.getBytes("US-ASCII"));
        out.write(jpeg);
        out.write("\r\n".getBytes("US-ASCII"));
        out.flush();
    }

    private boolean isValidSlot(final int slotIndex) {
        return slotIndex >= 0 && slotIndex < mActiveSlotCount;
    }

    private String resolveLocalIpAddress() {
        try {
            final Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                final NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                final Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    final InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (final Exception e) {
            Log.w(TAG, "resolveLocalIpAddress failed", e);
        }
        return "127.0.0.1";
    }

    private void closeQuietly(final ServerSocket serverSocket) {
        if (serverSocket == null) return;
        try {
            serverSocket.close();
        } catch (final IOException ignored) {
        }
    }

    private void closeQuietly(final Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (final IOException ignored) {
        }
    }

    private void log(final String message) {
        Log.i(TAG, message);
        if (mLogSink != null) {
            mLogSink.log(message);
        }
    }

    private static final class SlotState {
        final int index;
        final AtomicLong jpegCount = new AtomicLong();

        volatile String status = "EMPTY";
        volatile String deviceLabel;
        volatile int width;
        volatile int height;
        volatile String formatName;
        volatile int openSequence;
        volatile int fpsMin;
        volatile int fpsMax;
        volatile float bandwidthFactor;
        volatile String selectionReason;
        volatile boolean lowBandwidthMode;
        volatile long frameCount;
        volatile float fps;
        volatile byte[] latestJpegData;
        volatile long latestJpegTimestampMs;
        volatile long latestFrameCallbackMs;
        volatile int lastFrameBufferBytes;
        volatile long lastJpegEncodeMs;
        volatile long lastJpegSuccessLogMs;
        volatile long lastJpegWarnLogMs;

        SlotState(final int slotIndex) {
            index = slotIndex;
        }

        String shortSummary() {
            if (!"OPEN".equals(status)) {
                return "第" + (index + 1) + "路=" + displayStatus(status);
            }
            final long age = latestJpegTimestampMs > 0
                ? Math.max(0, System.currentTimeMillis() - latestJpegTimestampMs) : -1;
            final String health = "正常".equals(diagnosis(System.currentTimeMillis())) ? "正常" : "需检查";
            return String.format(Locale.US, "第%d路=#%d %s fps=%.1f 帧=%d JPEG=%d 延迟=%s 诊断=%s",
                index + 1, openSequence, health, fps, frameCount, jpegCount.get(),
                age >= 0 ? Long.toString(age) + "ms" : "暂无", diagnosis(System.currentTimeMillis()));
        }

        private String diagnosis(final long now) {
            if (!"OPEN".equals(status)) {
                return displayStatus(status);
            }
            if (frameCount <= 0) {
                return "无帧回调：优先查USB带宽/供电/格式";
            }
            if (latestFrameCallbackMs <= 0) {
                return "已计帧但拉流未收到帧：检查预览参数绑定时序";
            }
            if (fps <= 0f) {
                return "帧回调停滞：检查该路是否被USB或驱动卡住";
            }
            if (latestJpegTimestampMs <= 0) {
                return "有帧但无JPEG：检查NV21数据或编码";
            }
            if (now - latestJpegTimestampMs >= 3000) {
                return "JPEG延迟异常：HTTP拉流可能卡住";
            }
            return "正常";
        }

        private String displayStatus(final String rawStatus) {
            if (rawStatus == null) return "未知";
            if ("EMPTY".equals(rawStatus)) return "未就绪";
            if ("READY".equals(rawStatus)) return "可打开";
            if ("OPEN".equals(rawStatus)) return "已打开";
            if ("SURFACE DESTROYED".equals(rawStatus)) return "预览窗口已销毁";
            if (rawStatus.startsWith("OPEN FAILED")) return "打开失败";
            return rawStatus;
        }
    }
}
