package com.serenegiant.usbcameratest7;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Pushes the latest per-slot JPEG frames to a WebSocket receiver.
 *
 * This intentionally avoids third-party dependencies because the probe is a small field
 * diagnostic APK. It implements the client-side WebSocket handshake plus masked binary frames,
 * which is enough for continuously pushing JPEG payloads to a controlled receiver.
 */
final class CameraPushClient {
    interface FrameSource {
        JpegFrame getLatestJpegFrame(int slotIndex);
    }

    interface LogSink {
        void log(String message);
    }

    static final class JpegFrame {
        final int slotIndex;
        final byte[] jpegData;
        final long timestampMs;
        final int width;
        final int height;

        JpegFrame(final int slotIndex, final byte[] jpegData, final long timestampMs,
            final int width, final int height) {
            this.slotIndex = slotIndex;
            this.jpegData = jpegData;
            this.timestampMs = timestampMs;
            this.width = width;
            this.height = height;
        }
    }

    private static final int MAGIC_KJPG = 0x4b4a5047;
    private static final short PROTOCOL_VERSION = 1;
    private static final short HEADER_BYTES = 40;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int HANDSHAKE_READ_TIMEOUT_MS = 5000;
    private static final int RECONNECT_DELAY_MS = 2000;
    private static final int LOOP_IDLE_SLEEP_MS = 40;
    private static final int MIN_PUSH_INTERVAL_MS = 200;
    private static final int MAX_HANDSHAKE_BYTES = 8192;
    private static final long STATUS_LOG_INTERVAL_MS = 5000;
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final String mTargetUrl;
    private final URI mTargetUri;
    private final int mSlotCount;
    private final int mPushIntervalMs;
    private final FrameSource mFrameSource;
    private final LogSink mLogSink;
    private final SecureRandom mSecureRandom = new SecureRandom();
    private final long[] mLastSentFrameTimestampMs;
    private final long[] mLastSentAtMs;
    private final long[] mSlotSequenceNumbers;
    private final long[] mSlotSentCounts;

    private volatile boolean mRunning;
    private volatile boolean mConnected;
    private Thread mPushThread;
    private Socket mSocket;
    private OutputStream mSocketOutput;
    private long mTotalSentCount;
    private long mLastStatusLogMs;
    private long mLastFailureLogMs;

    CameraPushClient(final String targetUrl, final int slotCount, final long pushIntervalMs,
        final FrameSource frameSource, final LogSink logSink) {
        mTargetUrl = normalizeTargetUrl(targetUrl);
        mTargetUri = URI.create(mTargetUrl);
        mSlotCount = Math.max(1, slotCount);
        mPushIntervalMs = Math.max(MIN_PUSH_INTERVAL_MS, (int)pushIntervalMs);
        mFrameSource = frameSource;
        mLogSink = logSink;
        mLastSentFrameTimestampMs = new long[mSlotCount];
        mLastSentAtMs = new long[mSlotCount];
        mSlotSequenceNumbers = new long[mSlotCount];
        mSlotSentCounts = new long[mSlotCount];
    }

    synchronized void start() {
        if (mRunning) return;
        mRunning = true;
        mPushThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runPushLoop();
            }
        }, "KeenonWsPush");
        mPushThread.start();
        log("WebSocket推流已启动：目标=" + mTargetUrl
            + "，路数=" + mSlotCount + "，间隔=" + mPushIntervalMs + "ms");
    }

    synchronized void stop() {
        if (!mRunning) return;
        mRunning = false;
        closeCurrentSocket();
        mPushThread = null;
        log("WebSocket推流已停止：目标=" + mTargetUrl);
    }

    boolean isRunning() {
        return mRunning;
    }

    boolean isConnected() {
        return mConnected;
    }

    String getTargetUrl() {
        return mTargetUrl;
    }

    int getPushIntervalMs() {
        return mPushIntervalMs;
    }

    private void runPushLoop() {
        while (mRunning) {
            try {
                connectWebSocket();
                log("WebSocket推流已连接：" + mTargetUrl);
                while (mRunning && mConnected) {
                    pushAvailableFramesOnce();
                    logStatusIfNeeded(System.currentTimeMillis());
                    sleepQuietly(LOOP_IDLE_SLEEP_MS);
                }
            } catch (final Exception e) {
                logFailureThrottled("WebSocket推流连接/发送失败：" + e.getMessage()
                    + "，" + RECONNECT_DELAY_MS + "ms后重试");
            } finally {
                closeCurrentSocket();
            }
            if (mRunning) {
                sleepQuietly(RECONNECT_DELAY_MS);
            }
        }
    }

    private void pushAvailableFramesOnce() throws IOException {
        final long now = System.currentTimeMillis();
        for (int slotIndex = 0; slotIndex < mSlotCount; slotIndex++) {
            final JpegFrame frame = mFrameSource != null
                ? mFrameSource.getLatestJpegFrame(slotIndex) : null;
            if (frame == null || frame.jpegData == null || frame.jpegData.length == 0) continue;
            if (frame.timestampMs <= mLastSentFrameTimestampMs[slotIndex]) continue;
            if (now - mLastSentAtMs[slotIndex] < mPushIntervalMs) continue;

            final long sequenceNumber = ++mSlotSequenceNumbers[slotIndex];
            final byte[] message = buildPushMessage(slotIndex, frame, sequenceNumber);
            sendBinaryMessage(message);
            mLastSentFrameTimestampMs[slotIndex] = frame.timestampMs;
            mLastSentAtMs[slotIndex] = now;
            mSlotSentCounts[slotIndex]++;
            mTotalSentCount++;
        }
    }

    private byte[] buildPushMessage(final int slotIndex, final JpegFrame frame,
        final long sequenceNumber) {
        final byte[] jpegData = frame.jpegData;
        final ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + jpegData.length);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC_KJPG);
        buffer.putShort(PROTOCOL_VERSION);
        buffer.putShort(HEADER_BYTES);
        buffer.putInt(slotIndex);
        buffer.putLong(frame.timestampMs);
        buffer.putLong(sequenceNumber);
        buffer.putInt(frame.width);
        buffer.putInt(frame.height);
        buffer.putInt(jpegData.length);
        buffer.put(jpegData);
        return buffer.array();
    }

    private void connectWebSocket() throws Exception {
        final String scheme = mTargetUri.getScheme();
        if (!"ws".equalsIgnoreCase(scheme)) {
            throw new IOException("当前只支持ws://，目标=" + mTargetUrl);
        }
        final String host = mTargetUri.getHost();
        if (host == null || host.length() == 0) {
            throw new IOException("推流目标host为空：" + mTargetUrl);
        }
        final int port = mTargetUri.getPort() > 0 ? mTargetUri.getPort() : 80;
        final String path = buildRequestPath(mTargetUri);
        final Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(HANDSHAKE_READ_TIMEOUT_MS);

        final String secWebSocketKey = createWebSocketKey();
        final String hostHeader = port == 80 ? host : host + ":" + port;
        final String request = "GET " + path + " HTTP/1.1\r\n"
            + "Host: " + hostHeader + "\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: " + secWebSocketKey + "\r\n"
            + "Sec-WebSocket-Version: 13\r\n"
            + "\r\n";
        final OutputStream output = socket.getOutputStream();
        output.write(request.getBytes("US-ASCII"));
        output.flush();

        final String response = readHandshakeResponse(socket.getInputStream());
        validateHandshakeResponse(response, secWebSocketKey);
        socket.setSoTimeout(0);
        mSocket = socket;
        mSocketOutput = output;
        mConnected = true;
    }

    private String readHandshakeResponse(final InputStream input) throws IOException {
        final ByteArrayOutputStream responseBytes = new ByteArrayOutputStream(1024);
        int previousByte3 = -1;
        int previousByte2 = -1;
        int previousByte1 = -1;
        int currentByte;
        while ((currentByte = input.read()) >= 0) {
            responseBytes.write(currentByte);
            if (previousByte3 == '\r' && previousByte2 == '\n'
                && previousByte1 == '\r' && currentByte == '\n') {
                break;
            }
            previousByte3 = previousByte2;
            previousByte2 = previousByte1;
            previousByte1 = currentByte;
            if (responseBytes.size() > MAX_HANDSHAKE_BYTES) {
                throw new IOException("WebSocket握手响应过大");
            }
        }
        return responseBytes.toString("US-ASCII");
    }

    private void validateHandshakeResponse(final String response, final String secWebSocketKey)
        throws Exception {
        if (response == null || !response.startsWith("HTTP/1.1 101")) {
            throw new IOException("WebSocket握手失败：" + firstResponseLine(response));
        }
        final String expectedAccept = createExpectedAccept(secWebSocketKey);
        final String lowerResponse = response.toLowerCase(Locale.US);
        if (!lowerResponse.contains("upgrade: websocket")) {
            throw new IOException("WebSocket握手响应缺少Upgrade头");
        }
        if (!lowerResponse.contains("sec-websocket-accept: "
            + expectedAccept.toLowerCase(Locale.US))) {
            throw new IOException("WebSocket握手Sec-WebSocket-Accept校验失败");
        }
    }

    private String firstResponseLine(final String response) {
        if (response == null) return "无响应";
        final int newlineIndex = response.indexOf('\n');
        return newlineIndex >= 0 ? response.substring(0, newlineIndex).trim() : response.trim();
    }

    private void sendBinaryMessage(final byte[] payload) throws IOException {
        final OutputStream output = mSocketOutput;
        if (output == null || payload == null) {
            throw new IOException("WebSocket输出流为空");
        }
        final int payloadLength = payload.length;
        final ByteArrayOutputStream frame = new ByteArrayOutputStream(payloadLength + 16);
        frame.write(0x82);
        if (payloadLength <= 125) {
            frame.write(0x80 | payloadLength);
        } else if (payloadLength <= 65535) {
            frame.write(0x80 | 126);
            frame.write((payloadLength >> 8) & 0xff);
            frame.write(payloadLength & 0xff);
        } else {
            frame.write(0x80 | 127);
            final long longPayloadLength = payloadLength & 0xffffffffL;
            for (int shift = 56; shift >= 0; shift -= 8) {
                frame.write((int)((longPayloadLength >> shift) & 0xff));
            }
        }
        final byte[] mask = new byte[4];
        mSecureRandom.nextBytes(mask);
        frame.write(mask);
        for (int i = 0; i < payloadLength; i++) {
            frame.write(payload[i] ^ mask[i & 3]);
        }
        output.write(frame.toByteArray());
        output.flush();
    }

    private String createWebSocketKey() {
        final byte[] nonce = new byte[16];
        mSecureRandom.nextBytes(nonce);
        return Base64.encodeToString(nonce, Base64.NO_WRAP);
    }

    private String createExpectedAccept(final String secWebSocketKey) throws Exception {
        final MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        final byte[] digest = sha1.digest((secWebSocketKey + WEBSOCKET_GUID).getBytes("US-ASCII"));
        return Base64.encodeToString(digest, Base64.NO_WRAP);
    }

    private void logStatusIfNeeded(final long now) {
        if (now - mLastStatusLogMs < STATUS_LOG_INTERVAL_MS) return;
        mLastStatusLogMs = now;
        final StringBuilder status = new StringBuilder();
        status.append("WebSocket推流状态：目标=").append(mTargetUrl)
            .append("，累计=").append(mTotalSentCount);
        for (int slotIndex = 0; slotIndex < mSlotCount; slotIndex++) {
            status.append(" S").append(slotIndex + 1).append('=').append(mSlotSentCounts[slotIndex]);
        }
        log(status.toString());
    }

    private void logFailureThrottled(final String message) {
        final long now = System.currentTimeMillis();
        if (now - mLastFailureLogMs < STATUS_LOG_INTERVAL_MS) return;
        mLastFailureLogMs = now;
        log(message);
    }

    private void closeCurrentSocket() {
        mConnected = false;
        mSocketOutput = null;
        if (mSocket != null) {
            try {
                mSocket.close();
            } catch (final IOException ignored) {
            }
            mSocket = null;
        }
    }

    private void sleepQuietly(final long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void log(final String message) {
        if (mLogSink != null) {
            mLogSink.log(message);
        }
    }

    private static String normalizeTargetUrl(final String targetUrl) {
        final String trimmed = targetUrl == null || targetUrl.trim().length() == 0
            ? "ws://192.168.112.194:9090/" : targetUrl.trim();
        if (trimmed.startsWith("ws://")) {
            return ensurePath(trimmed);
        }
        if (trimmed.startsWith("http://")) {
            return ensurePath("ws://" + trimmed.substring("http://".length()));
        }
        return ensurePath("ws://" + trimmed);
    }

    private static String ensurePath(final String url) {
        final URI uri = URI.create(url);
        final String path = uri.getRawPath();
        if (path != null && path.length() > 0) return url;
        return url.endsWith("/") ? url : url + "/";
    }

    private static String buildRequestPath(final URI uri) {
        final String rawPath = uri.getRawPath();
        final String path = rawPath == null || rawPath.length() == 0 ? "/" : rawPath;
        final String rawQuery = uri.getRawQuery();
        return rawQuery == null || rawQuery.length() == 0 ? path : path + "?" + rawQuery;
    }
}
