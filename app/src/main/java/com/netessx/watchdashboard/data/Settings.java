package com.netessx.watchdashboard.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.netessx.watchdashboard.R;

import java.net.URI;
import java.util.UUID;

/** Persisted endpoint configuration. The token is encrypted at rest. */
public final class Settings {

    private static final String PREFS = "watch_agent_settings";
    private static final String KEY_URL = "server_url";
    private static final String KEY_TOKEN_ENC = "token_enc";
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_DEVICE_ID = "device_id";

    public final String serverUrl;
    public final String token;
    public final String deviceName;
    public final String deviceId;

    private Settings(String serverUrl, String token, String deviceName, String deviceId) {
        this.serverUrl = serverUrl;
        this.token = token;
        this.deviceName = deviceName;
        this.deviceId = deviceId;
    }

    public boolean isConfigured() {
        return !serverUrl.isEmpty() && !token.isEmpty();
    }

    public static Settings load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String url = normalize(prefs.getString(KEY_URL, ""));
        String name = normalize(prefs.getString(KEY_DEVICE_NAME, ""));
        if (name.isEmpty()) {
            name = defaultDeviceName();
        }
        String deviceId = normalize(prefs.getString(KEY_DEVICE_ID, ""));
        if (deviceId.isEmpty()) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
        }
        String token = "";
        String encrypted = normalize(prefs.getString(KEY_TOKEN_ENC, ""));
        if (!encrypted.isEmpty()) {
            try {
                token = SecurePrefs.decrypt(encrypted);
            } catch (Exception e) {
                // Keystore key may have been invalidated (OS update / factory reset).
                // Force the user to re-enter the token rather than storing it in clear text.
                SecurePrefs.reset();
                token = "";
            }
        }
        return new Settings(url, token, name, deviceId);
    }

    /**
     * Persists the configuration.
     *
     * @return null on success, otherwise a user-facing error message.
     */
    public static String save(Context context, String rawUrl, String rawToken, String rawName) {
        String url = normalize(rawUrl);
        String token = normalize(rawToken);
        String name = normalize(rawName);
        if (url.isEmpty() || token.isEmpty()) {
            return context.getString(R.string.error_credentials);
        }
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            return context.getString(R.string.error_url);
        }
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null || uri.getHost().isEmpty()) {
                return context.getString(R.string.error_url);
            }
        } catch (Exception e) {
            return context.getString(R.string.error_url);
        }
        if (name.isEmpty()) {
            name = defaultDeviceName();
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String deviceId = normalize(prefs.getString(KEY_DEVICE_ID, ""));
        if (deviceId.isEmpty()) {
            deviceId = UUID.randomUUID().toString();
        }
        try {
            String encrypted = SecurePrefs.encrypt(token);
            prefs.edit()
                    .putString(KEY_URL, url)
                    .putString(KEY_TOKEN_ENC, encrypted)
                    .putString(KEY_DEVICE_NAME, name)
                    .putString(KEY_DEVICE_ID, deviceId)
                    .apply();
        } catch (Exception e) {
            return context.getString(R.string.error_secure_store);
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultDeviceName() {
        String model = Build.MODEL;
        return model == null || model.trim().isEmpty() ? "Watch" : model.trim();
    }
}
