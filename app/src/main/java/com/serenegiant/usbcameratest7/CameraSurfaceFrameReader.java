package com.serenegiant.usbcameratest7;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Drains service-owned SurfaceTexture frames and reads them back as JPEG without adding a
 * second Camera2 output target.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
final class CameraSurfaceFrameReader {
    interface FrameListener {
        int getCaptureIntervalMs();

        void onFrameAvailable(long timestampMs);

        void onJpegFrame(byte[] jpegData, int width, int height, long timestampMs);

        void onReadbackError(String message, Throwable throwable);
    }

    static final class FrameTarget {
        private final CameraSurfaceFrameReader owner;
        private final int slotIndex;
        private final int textureId;
        private final SurfaceTexture surfaceTexture;
        private final Surface surface;
        private final FrameListener frameListener;
        private long lastCaptureTimestampMs;
        private boolean released;

        private FrameTarget(final CameraSurfaceFrameReader targetOwner, final int targetSlotIndex,
            final int targetTextureId, final SurfaceTexture targetSurfaceTexture,
            final Surface targetSurface, final FrameListener targetFrameListener) {
            owner = targetOwner;
            slotIndex = targetSlotIndex;
            textureId = targetTextureId;
            surfaceTexture = targetSurfaceTexture;
            surface = targetSurface;
            frameListener = targetFrameListener;
        }

        Surface getSurface() {
            return surface;
        }

        void release() {
            owner.releaseTarget(this);
        }
    }

    private static final float[] FULL_FRAME_VERTICES = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    };
    private static final float[] FULL_FRAME_TEXTURE_COORDINATES = {
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
    };
    private static final String VERTEX_SHADER =
        "attribute vec4 aPosition;\n"
            + "attribute vec4 aTextureCoordinate;\n"
            + "uniform mat4 uTextureMatrix;\n"
            + "varying vec2 vTextureCoordinate;\n"
            + "void main() {\n"
            + "  gl_Position = aPosition;\n"
            + "  vTextureCoordinate = (uTextureMatrix * aTextureCoordinate).xy;\n"
            + "}\n";
    private static final String FRAGMENT_SHADER =
        "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "uniform samplerExternalOES uTexture;\n"
            + "varying vec2 vTextureCoordinate;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(uTexture, vTextureCoordinate);\n"
            + "}\n";

    private final int width;
    private final int height;
    private final int jpegQuality;
    private final Handler callbackHandler;
    private final FloatBuffer vertexBuffer;
    private final FloatBuffer textureCoordinateBuffer;
    private final ByteBuffer pixelBuffer;
    private final int[] argbPixels;
    private final float[] textureMatrix = new float[16];
    private final List<FrameTarget> frameTargets = new ArrayList<FrameTarget>();
    private final Bitmap outputBitmap;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int shaderProgram;
    private int positionLocation;
    private int textureCoordinateLocation;
    private int textureMatrixLocation;
    private boolean released;

    CameraSurfaceFrameReader(final int frameWidth, final int frameHeight,
        final int frameJpegQuality, final Handler frameCallbackHandler) {
        width = frameWidth;
        height = frameHeight;
        jpegQuality = frameJpegQuality;
        callbackHandler = frameCallbackHandler;
        vertexBuffer = createFloatBuffer(FULL_FRAME_VERTICES);
        textureCoordinateBuffer = createFloatBuffer(FULL_FRAME_TEXTURE_COORDINATES);
        pixelBuffer = ByteBuffer.allocateDirect(width * height * 4)
            .order(ByteOrder.nativeOrder());
        argbPixels = new int[width * height];
        outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        initializeEgl();
    }

    FrameTarget createTarget(final int slotIndex, final FrameListener frameListener) {
        if (released) {
            throw new IllegalStateException("SurfaceTexture读回器已释放");
        }
        makeCurrent();
        final int[] textureIds = new int[1];
        GLES20.glGenTextures(1, textureIds, 0);
        final int textureId = textureIds[0];
        if (textureId == 0) {
            throw new IllegalStateException("无法创建SurfaceTexture外部纹理");
        }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

        final SurfaceTexture surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setDefaultBufferSize(width, height);
        final Surface surface = new Surface(surfaceTexture);
        final FrameTarget frameTarget = new FrameTarget(this, slotIndex, textureId,
            surfaceTexture, surface, frameListener);
        surfaceTexture.setOnFrameAvailableListener(
            new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(final SurfaceTexture callbackSurfaceTexture) {
                    drainFrame(frameTarget);
                }
            }, callbackHandler);
        frameTargets.add(frameTarget);
        return frameTarget;
    }

    void release() {
        if (released) return;
        for (int targetIndex = frameTargets.size() - 1; targetIndex >= 0; targetIndex--) {
            releaseTarget(frameTargets.get(targetIndex));
        }
        makeCurrent();
        if (shaderProgram != 0) {
            GLES20.glDeleteProgram(shaderProgram);
            shaderProgram = 0;
        }
        outputBitmap.recycle();
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT);
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            eglSurface = EGL14.EGL_NO_SURFACE;
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            eglContext = EGL14.EGL_NO_CONTEXT;
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }
        released = true;
    }

    private void drainFrame(final FrameTarget frameTarget) {
        if (released || frameTarget == null || frameTarget.released) return;
        final FrameListener frameListener = frameTarget.frameListener;
        try {
            makeCurrent();
            frameTarget.surfaceTexture.updateTexImage();
            final long timestampMs = System.currentTimeMillis();
            if (frameListener != null) {
                frameListener.onFrameAvailable(timestampMs);
            }
            final int captureIntervalMs = frameListener != null
                ? Math.max(1, frameListener.getCaptureIntervalMs()) : 1;
            if (timestampMs - frameTarget.lastCaptureTimestampMs < captureIntervalMs) return;
            frameTarget.lastCaptureTimestampMs = timestampMs;
            final byte[] jpegData = readCurrentFrameAsJpeg(frameTarget);
            if (frameListener != null && jpegData != null && jpegData.length > 0) {
                frameListener.onJpegFrame(jpegData, width, height, timestampMs);
            }
        } catch (final RuntimeException runtimeException) {
            if (frameListener != null) {
                frameListener.onReadbackError("第" + (frameTarget.slotIndex + 1)
                    + "路SurfaceTexture读回失败", runtimeException);
            }
        }
    }

    private byte[] readCurrentFrameAsJpeg(final FrameTarget frameTarget) {
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(shaderProgram);

        vertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(positionLocation);
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0,
            vertexBuffer);
        textureCoordinateBuffer.position(0);
        GLES20.glEnableVertexAttribArray(textureCoordinateLocation);
        GLES20.glVertexAttribPointer(textureCoordinateLocation, 2, GLES20.GL_FLOAT, false, 0,
            textureCoordinateBuffer);
        frameTarget.surfaceTexture.getTransformMatrix(textureMatrix);
        GLES20.glUniformMatrix4fv(textureMatrixLocation, 1, false, textureMatrix, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, frameTarget.textureId);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glFinish();

        pixelBuffer.position(0);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE, pixelBuffer);
        throwIfGlFailed("读取SurfaceTexture像素");
        convertRgbaToTopDownArgb();
        outputBitmap.setPixels(argbPixels, 0, width, 0, 0, width, height);

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        GLES20.glDisableVertexAttribArray(positionLocation);
        GLES20.glDisableVertexAttribArray(textureCoordinateLocation);
        final ByteArrayOutputStream jpegOutput = new ByteArrayOutputStream(width * height / 4);
        if (!outputBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpegOutput)) {
            throw new IllegalStateException("SurfaceTexture像素压缩JPEG失败");
        }
        return jpegOutput.toByteArray();
    }

    private void convertRgbaToTopDownArgb() {
        for (int outputRow = 0; outputRow < height; outputRow++) {
            final int sourceRow = height - outputRow - 1;
            for (int column = 0; column < width; column++) {
                final int sourceOffset = (sourceRow * width + column) * 4;
                final int red = pixelBuffer.get(sourceOffset) & 0xff;
                final int green = pixelBuffer.get(sourceOffset + 1) & 0xff;
                final int blue = pixelBuffer.get(sourceOffset + 2) & 0xff;
                final int alpha = pixelBuffer.get(sourceOffset + 3) & 0xff;
                argbPixels[outputRow * width + column] = alpha << 24
                    | red << 16 | green << 8 | blue;
            }
        }
    }

    private void releaseTarget(final FrameTarget frameTarget) {
        if (frameTarget == null || frameTarget.released) return;
        makeCurrent();
        frameTarget.released = true;
        frameTarget.surfaceTexture.setOnFrameAvailableListener(null);
        frameTarget.surface.release();
        frameTarget.surfaceTexture.release();
        final int[] textureIds = { frameTarget.textureId };
        GLES20.glDeleteTextures(1, textureIds, 0);
        frameTargets.remove(frameTarget);
    }

    private void initializeEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("无法获取EGL display");
        }
        final int[] eglVersions = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, eglVersions, 0, eglVersions, 1)) {
            throw new IllegalStateException("无法初始化EGL display");
        }
        final int[] configAttributes = {
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        };
        final EGLConfig[] eglConfigs = new EGLConfig[1];
        final int[] configCount = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttributes, 0, eglConfigs, 0,
            eglConfigs.length, configCount, 0) || configCount[0] == 0) {
            throw new IllegalStateException("无法选择EGL config");
        }
        final int[] contextAttributes = {
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfigs[0], EGL14.EGL_NO_CONTEXT,
            contextAttributes, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("无法创建EGL context");
        }
        final int[] surfaceAttributes = {
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        };
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfigs[0],
            surfaceAttributes, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("无法创建EGL离屏Surface");
        }
        makeCurrent();
        shaderProgram = createShaderProgram();
        positionLocation = GLES20.glGetAttribLocation(shaderProgram, "aPosition");
        textureCoordinateLocation = GLES20.glGetAttribLocation(shaderProgram,
            "aTextureCoordinate");
        textureMatrixLocation = GLES20.glGetUniformLocation(shaderProgram, "uTextureMatrix");
        if (positionLocation < 0 || textureCoordinateLocation < 0 || textureMatrixLocation < 0) {
            throw new IllegalStateException("无法获取SurfaceTexture shader变量");
        }
    }

    private int createShaderProgram() {
        final int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        final int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        final int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        final int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        if (linkStatus[0] == 0) {
            final String programLog = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException("SurfaceTexture shader链接失败：" + programLog);
        }
        return program;
    }

    private int compileShader(final int shaderType, final String shaderSource) {
        final int shader = GLES20.glCreateShader(shaderType);
        GLES20.glShaderSource(shader, shaderSource);
        GLES20.glCompileShader(shader);
        final int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] == 0) {
            final String shaderLog = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("SurfaceTexture shader编译失败：" + shaderLog);
        }
        return shader;
    }

    private void makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new IllegalStateException("无法激活SurfaceTexture EGL context，error=0x"
                + Integer.toHexString(EGL14.eglGetError()));
        }
    }

    private void throwIfGlFailed(final String operationName) {
        final int glError = GLES20.glGetError();
        if (glError != GLES20.GL_NO_ERROR) {
            throw new IllegalStateException(operationName + "失败，GL error=0x"
                + Integer.toHexString(glError));
        }
    }

    private static FloatBuffer createFloatBuffer(final float[] values) {
        final ByteBuffer byteBuffer = ByteBuffer.allocateDirect(values.length * 4)
            .order(ByteOrder.nativeOrder());
        final FloatBuffer floatBuffer = byteBuffer.asFloatBuffer();
        floatBuffer.put(values);
        floatBuffer.position(0);
        return floatBuffer;
    }
}
