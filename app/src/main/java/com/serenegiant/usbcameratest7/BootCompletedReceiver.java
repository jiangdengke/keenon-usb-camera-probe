package com.serenegiant.usbcameratest7;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

/**
 * Restarts camera streaming after the device finishes booting.
 */
public final class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "KeenonBootReceiver";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.i(TAG, "开机自启已跳过：系统版本不支持Camera2");
            return;
        }

        final int cameraPermission = context.getPackageManager().checkPermission(
            Manifest.permission.CAMERA, context.getPackageName());
        if (cameraPermission != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "开机自启已跳过：请先打开App并授予CAMERA权限");
            return;
        }

        final Intent serviceIntent = new Intent(context, CameraStreamingService.class);
        serviceIntent.setAction(CameraStreamingService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.i(TAG, "开机自启已触发：正在恢复Camera2、HTTP和WebSocket推流");
        } catch (final RuntimeException runtimeException) {
            Log.e(TAG, "开机自启失败：前台推流服务无法启动", runtimeException);
        }
    }
}
