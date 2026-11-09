package com.serenegiant.usbcameratest7;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * WebSocket push settings that survive reboots.
 *
 * ADB launch extras and the in-app dialog save here. The boot start, the launcher icon and the
 * notification read from here, so a configured receiver stays in use until it is changed or reset.
 */
final class PushSettings {
    static final boolean DEFAULT_ENABLED = true;
    static final String DEFAULT_TARGET_URL = "ws://192.168.112.194:9090/";
    static final int DEFAULT_SLOT_COUNT = 4;
    static final int DEFAULT_INTERVAL_MS = 500;
    static final int MIN_INTERVAL_MS = 200;

    private static final String PREFERENCES_NAME = "camera_push_settings";
    private static final String KEY_ENABLED = "push_enabled";
    private static final String KEY_TARGET_URL = "push_target_url";
    private static final String KEY_SLOT_COUNT = "push_slot_count";
    private static final String KEY_INTERVAL_MS = "push_interval_ms";

    final boolean enabled;
    final String targetUrl;
    final int slotCount;
    final int intervalMs;

    PushSettings(final boolean enabled, final String targetUrl, final int slotCount,
        final int intervalMs) {
        this.enabled = enabled;
        this.targetUrl = targetUrl;
        this.slotCount = slotCount;
        this.intervalMs = intervalMs;
    }

    static PushSettings load(final Context context) {
        final SharedPreferences preferences = preferences(context);
        final String savedTargetUrl = normalizeTargetUrl(
            preferences.getString(KEY_TARGET_URL, DEFAULT_TARGET_URL));
        return new PushSettings(
            preferences.getBoolean(KEY_ENABLED, DEFAULT_ENABLED),
            savedTargetUrl != null ? savedTargetUrl : DEFAULT_TARGET_URL,
            preferences.getInt(KEY_SLOT_COUNT, DEFAULT_SLOT_COUNT),
            preferences.getInt(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS));
    }

    void save(final Context context) {
        preferences(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_TARGET_URL, targetUrl)
            .putInt(KEY_SLOT_COUNT, slotCount)
            .putInt(KEY_INTERVAL_MS, intervalMs)
            .apply();
    }

    static void reset(final Context context) {
        preferences(context).edit().clear().apply();
    }

    /**
     * Returns the ws:// address to push to, or null when the input cannot be used.
     * Accepts ws://, http:// (rewritten to ws://) and a bare host[:port][/path].
     */
    static String normalizeTargetUrl(final String rawUrl) {
        if (rawUrl == null) return null;
        String url = rawUrl.trim();
        final String lowerUrl = url.toLowerCase(Locale.US);
        if (lowerUrl.startsWith("ws://")) {
            url = "ws://" + url.substring("ws://".length());
        } else if (lowerUrl.startsWith("http://")) {
            url = "ws://" + url.substring("http://".length());
        } else if (url.length() == 0 || lowerUrl.contains("://")) {
            return null;
        } else {
            url = "ws://" + url;
        }
        try {
            final URI uri = new URI(url);
            final String host = uri.getHost();
            if (host == null || host.length() == 0) return null;
            final String path = uri.getRawPath();
            return path != null && path.length() > 0 ? url : url + "/";
        } catch (final URISyntaxException invalidUrl) {
            return null;
        }
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }
}
