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
 *
 * On Android 9 and lower the Activity is launched first, so the cameras open while the app is in
 * the foreground, the same as a manual open. The Activity returns to the background once frames
 * arrive. Android 10 and higher block activity starts from a boot receiver, so only the service
 * starts there.
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

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            launchActivityForBoot(context);
        } else {
            Log.i(TAG, "开机拉起界面已跳过：Android 10及以上禁止后台启动界面，只启动前台推流服务");
        }

        // The service start also covers a boot UI launch that the firmware blocks. When the
        // Activity starts, its own start request reopens any camera that failed in the meantime.
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

    private void launchActivityForBoot(final Context context) {
        // Match the launcher intent: a later launcher tap resumes this task, while an ADB
        // "am start -n" with push extras still opens a new instance that applies them.
        final Intent activityIntent = new Intent(Intent.ACTION_MAIN);
        activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        activityIntent.setClass(context, MainActivity.class);
        activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activityIntent.putExtra(MainActivity.EXTRA_LAUNCHED_FROM_BOOT, true);
        try {
            context.startActivity(activityIntent);
            Log.i(TAG, "开机拉起界面已触发：摄像头出图后界面自动退回后台");
        } catch (final RuntimeException runtimeException) {
            Log.e(TAG, "开机拉起界面失败：只依赖前台推流服务", runtimeException);
        }
    }
}
