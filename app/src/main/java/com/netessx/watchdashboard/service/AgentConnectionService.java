package com.netessx.watchdashboard.service;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import com.netessx.watchdashboard.R;
import com.netessx.watchdashboard.data.AgentRequest;
import com.netessx.watchdashboard.data.AgentStore;
import com.netessx.watchdashboard.data.Settings;
import com.netessx.watchdashboard.data.WireEvent;
import com.netessx.watchdashboard.net.Protocol;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Owns the protocol v1 WebSocket for the life of the process. Runs as a
 * dataSync foreground service so approvals can arrive while the app is closed.
 */
public class AgentConnectionService extends Service {

    private static final String TAG = "AgentConnectionService";
    private static final long RECONNECT_MAX_MS = 30_000L;
    private static final long HANDSHAKE_TIMEOUT_MS = 12_000L;

    public static final String ACTION_CONNECT =
            "com.netessx.watchdashboard.action.CONNECT";
    public static final String ACTION_RECONNECT_CONFIG =
            "com.netessx.watchdashboard.action.RECONNECT_CONFIG";
    public static final String ACTION_STOP =
            "com.netessx.watchdashboard.action.STOP";
    public static final String ACTION_SEND_RESPONSE =
            "com.netessx.watchdashboard.action.SEND_RESPONSE";
    public static final String EXTRA_REQUEST_ID = "request_id";
    public static final String EXTRA_OPTION_ID = "option_id";
    public static final String EXTRA_FROM_NOTIFICATION = "from_notification";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private AgentStore store;
    private OkHttpClient httpClient;
    private WebSocket socket;
    private ConnectivityManager.NetworkCallback networkCallback;

    private boolean intentionalStop;
    private boolean connecting;
    private boolean authFailed;
    private boolean foregroundStarted;
    private boolean reconnectScheduled;
    private boolean awaitingHandshake;
    private boolean ackScheduled;
    private int reconnectAttempt;
    private int peerCloseCode;
    private String peerCloseReason = "";

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            reconnectScheduled = false;
            connect(false);
        }
    };

    private final Runnable ackRunnable = new Runnable() {
        @Override
        public void run() {
            ackScheduled = false;
            WebSocket current = socket;
            if (current == null) {
                return;
            }
            try {
                current.send(Protocol.ack(store.getLastSeq()).toString());
            } catch (JSONException e) {
                Log.w(TAG, "ack encode failed", e);
            }
        }
    };

    private final Runnable handshakeTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!awaitingHandshake) {
                return;
            }
            awaitingHandshake = false;
            closeSocket();
            scheduleReconnect(getString(R.string.error_connect_hint));
        }
    };

    private final Runnable housekeepingRunnable = new Runnable() {
        @Override
        public void run() {
            store.pruneExpired();
            NotificationPresenter.sync(AgentConnectionService.this, store);
            updateForeground();
            handler.postDelayed(this, 30_000L);
        }
    };

    // ---- Public entry points -------------------------------------------------

    public static void start(Context context) {
        startWithAction(context, ACTION_CONNECT, true);
    }

    public static void reconnectWithConfig(Context context) {
        startWithAction(context, ACTION_RECONNECT_CONFIG, true);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, AgentConnectionService.class)
                .setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "stop failed", e);
        }
    }

    public static void sendResponse(Context context, String requestId, String optionId) {
        Intent intent = new Intent(context, AgentConnectionService.class)
                .setAction(ACTION_SEND_RESPONSE)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_OPTION_ID, optionId);
        try {
            context.startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "sendResponse failed", e);
        }
    }

    private static void startWithAction(Context context, String action, boolean foreground) {
        Intent intent = new Intent(context, AgentConnectionService.class).setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && foreground) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "service start failed", e);
        }
    }

    // ---- Lifecycle -----------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        store = AgentStore.get(this);
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .pingInterval(25, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        registerNetworkCallback();
        handler.postDelayed(housekeepingRunnable, 30_000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        store = AgentStore.get(this);
        String action = intent == null ? ACTION_CONNECT : intent.getAction();
        if (action == null) {
            action = ACTION_CONNECT;
        }

        if (ACTION_STOP.equals(action)) {
            intentionalStop = true;
            cancelCallbacks();
            closeSocket();
            store.setConnection(AgentStore.Connection.DISCONNECTED, "");
            NotificationPresenter.cancelActionNotifications(this);
            if (foregroundStarted) {
                stopForeground(true);
                foregroundStarted = false;
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        ensureForeground();

        switch (action) {
            case ACTION_RECONNECT_CONFIG:
                reconnectAttempt = 0;
                authFailed = false;
                intentionalStop = false;
                cancelCallbacks();
                closeSocket();
                NotificationPresenter.cancelActionNotifications(this);
                connect(true);
                break;
            case ACTION_SEND_RESPONSE:
                handleResponse(
                        intent.getStringExtra(EXTRA_REQUEST_ID),
                        intent.getStringExtra(EXTRA_OPTION_ID),
                        intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false));
                maybeConnect();
                break;
            case ACTION_CONNECT:
            default:
                intentionalStop = false;
                if (authFailed || reconnectScheduled) {
                    authFailed = false;
                    reconnectAttempt = 0;
                    cancelCallbacks();
                    connect(true);
                } else if (socket == null && !connecting) {
                    connect(true);
                }
                break;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        intentionalStop = true;
        cancelCallbacks();
        handler.removeCallbacks(housekeepingRunnable);
        closeSocket();
        unregisterNetworkCallback();
        if (foregroundStarted) {
            stopForeground(true);
            foregroundStarted = false;
        }
        store.setConnection(AgentStore.Connection.DISCONNECTED, "");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "foreground service timeout, type=" + fgsType);
        intentionalStop = true;
        cancelCallbacks();
        handler.removeCallbacks(housekeepingRunnable);
        closeSocket();
        store.setConnection(AgentStore.Connection.DISCONNECTED,
                getString(R.string.error_fgs_timeout));
        if (foregroundStarted) {
            stopForeground(true);
            foregroundStarted = false;
        }
        stopSelf();
    }

    // ---- Connection ----------------------------------------------------------

    private void connect(boolean resetAttempts) {
        Settings settings = Settings.load(this);
        if (!settings.isConfigured()) {
            store.setConnection(AgentStore.Connection.UNCONFIGURED, "");
            stopSelf();
            return;
        }
        if (resetAttempts) {
            reconnectAttempt = 0;
            authFailed = false;
            intentionalStop = false;
        }
        handler.removeCallbacks(reconnectRunnable);
        reconnectScheduled = false;
        closeSocket();
        connecting = true;
        store.setConnection(
                reconnectAttempt > 0
                        ? AgentStore.Connection.RECONNECTING
                        : AgentStore.Connection.CONNECTING,
                resetAttempts ? "" : store.snapshot().lastError);
        updateForeground();

        Request request;
        try {
            String displayUrl = settings.serverUrl;
            int query = displayUrl.indexOf('?');
            if (query > 0) {
                displayUrl = displayUrl.substring(0, query);
            }
            Log.i(TAG, "connecting websocket to " + displayUrl
                    + " device=" + settings.deviceId);
            request = new Request.Builder()
                    .url(settings.serverUrl)
                    .header("Authorization", "Bearer " + settings.token)
                    .header("User-Agent", "WatchDashboard/1.0")
                    .build();
        } catch (IllegalArgumentException e) {
            connecting = false;
            authFailed = true;
            store.setConnection(AgentStore.Connection.AUTH_FAILED,
                    getString(R.string.error_url));
            updateForeground();
            return;
        }

        WebSocket newSocket;
        try {
            newSocket = httpClient.newWebSocket(request, new SocketListener());
        } catch (Exception e) {
            connecting = false;
            scheduleReconnect(getString(R.string.error_connect_hint));
            return;
        }
        socket = newSocket;
    }

    private void closeSocket() {
        WebSocket current = socket;
        socket = null;
        connecting = false;
        awaitingHandshake = false;
        if (current != null) {
            current.cancel();
        }
    }

    private void scheduleReconnect(String error) {
        if (intentionalStop || authFailed) {
            return;
        }
        awaitingHandshake = false;
        closeSocket();
        reconnectAttempt = Math.min(reconnectAttempt + 1, 6);
        long delay = Math.min(
                RECONNECT_MAX_MS,
                1000L << Math.min(reconnectAttempt - 1, 5));
        delay += (long) (Math.random() * 400);
        store.setConnection(AgentStore.Connection.RECONNECTING,
                error == null || error.isEmpty() ? getString(R.string.error_connect_hint) : error);
        updateForeground();
        handler.removeCallbacks(reconnectRunnable);
        reconnectScheduled = true;
        handler.postDelayed(reconnectRunnable, delay);
    }

    private void sendHello(WebSocket webSocket) {
        try {
            Settings settings = Settings.load(this);
            JSONObject hello = Protocol.hello(
                    settings.deviceId,
                    settings.deviceName,
                    appVersion(),
                    store.getLastSeq());
            webSocket.send(hello.toString());
        } catch (JSONException e) {
            Log.w(TAG, "hello encode failed", e);
            scheduleReconnect(getString(R.string.error_connect_hint));
        }
    }

    private void flushOutbox(WebSocket webSocket) {
        if (webSocket == null) {
            return;
        }
        List<String> messages = store.outboxMessages();
        for (String message : messages) {
            if (!webSocket.send(message)) {
                break;
            }
        }
    }

    private void scheduleAck(long seq) {
        if (seq <= 0 || ackScheduled) {
            return;
        }
        ackScheduled = true;
        handler.removeCallbacks(ackRunnable);
        handler.postDelayed(ackRunnable, 800L);
    }

    private void handleResponse(String requestId, String optionId, boolean fromNotification) {
        if (requestId == null || requestId.isEmpty() || optionId == null || optionId.isEmpty()) {
            return;
        }
        AgentRequest request = store.getPending(requestId);
        if (request == null) {
            if (fromNotification) {
                NotificationPresenter.sync(this, store);
            }
            return;
        }
        if (request.isExpired(System.currentTimeMillis())) {
            store.pruneExpired();
            NotificationPresenter.sync(this, store);
            return;
        }
        JSONObject response;
        try {
            response = Protocol.response(requestId, optionId);
        } catch (JSONException e) {
            Log.w(TAG, "response encode failed", e);
            return;
        }
        if (!store.enqueueResponse(requestId, response)) {
            return;
        }
        WebSocket current = socket;
        if (current != null && store.isOnline()) {
            current.send(response.toString());
        }
        NotificationPresenter.sync(this, store);
        updateForeground();
    }

    private void maybeConnect() {
        if (intentionalStop || authFailed || socket != null || connecting || reconnectScheduled) {
            return;
        }
        if (Settings.load(this).isConfigured()) {
            connect(false);
        }
    }

    private void updateForeground() {
        if (!foregroundStarted) {
            return;
        }
        try {
            Notification notification = NotificationPresenter.foreground(this, store.snapshot());
            NotificationManagerCompat.from(this)
                    .notify(NotificationPresenter.FG_NOTIFICATION_ID, notification);
        } catch (SecurityException e) {
            Log.w(TAG, "foreground notification update denied", e);
        }
    }

    private void ensureForeground() {
        Notification notification = NotificationPresenter.foreground(this, store.snapshot());
        if (!foregroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NotificationPresenter.FG_NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NotificationPresenter.FG_NOTIFICATION_ID, notification);
            }
            foregroundStarted = true;
        } else {
            updateForeground();
        }
    }

    private void cancelCallbacks() {
        handler.removeCallbacks(reconnectRunnable);
        handler.removeCallbacks(ackRunnable);
        handler.removeCallbacks(handshakeTimeoutRunnable);
        reconnectScheduled = false;
        ackScheduled = false;
        awaitingHandshake = false;
    }

    private String appVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return;
            }
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (intentionalStop || authFailed) {
                                return;
                            }
                            if (socket == null && !connecting) {
                                handler.removeCallbacks(reconnectRunnable);
                                reconnectScheduled = false;
                                connect(false);
                            }
                        }
                    });
                }
            };
            manager.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception e) {
            Log.w(TAG, "network callback unavailable", e);
        }
    }

    private void unregisterNetworkCallback() {
        if (networkCallback == null) {
            return;
        }
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager != null) {
                manager.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception ignored) {
            // Best effort.
        }
        networkCallback = null;
    }

    // ---- Socket callbacks ----------------------------------------------------

    private final class SocketListener extends WebSocketListener {
        @Override
        public void onOpen(final WebSocket webSocket, Response response) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (webSocket != socket) {
                        return;
                    }
                    connecting = false;
                    awaitingHandshake = true;
                    handler.removeCallbacks(handshakeTimeoutRunnable);
                    handler.postDelayed(handshakeTimeoutRunnable, HANDSHAKE_TIMEOUT_MS);
                    store.setConnection(AgentStore.Connection.CONNECTING, "");
                    updateForeground();
                    sendHello(webSocket);
                }
            });
        }

        @Override
        public void onMessage(final WebSocket webSocket, final String text) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    handleMessage(webSocket, text);
                }
            });
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            // Preserve the peer's code. Echoing 1000 here can hide a 4401/4003
            // authentication close and send the app into a reconnect loop.
            peerCloseCode = code;
            peerCloseReason = reason == null ? "" : reason;
            Log.w(TAG, "websocket closing code=" + code + " reason=" + peerCloseReason);
            webSocket.close(code, null);
        }

        @Override
        public void onClosed(final WebSocket webSocket, final int code, final String reason) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (webSocket != socket) {
                        return;
                    }
                    socket = null;
                    connecting = false;
                    awaitingHandshake = false;
                    handler.removeCallbacks(handshakeTimeoutRunnable);
                    if (intentionalStop) {
                        store.setConnection(AgentStore.Connection.DISCONNECTED, "");
                        updateForeground();
                        return;
                    }
                    if (isAuthCloseCode(code)) {
                        authFailed = true;
                        store.setConnection(AgentStore.Connection.AUTH_FAILED,
                                reason == null || reason.isEmpty()
                                        ? getString(R.string.status_auth_failed)
                                        : reason);
                        updateForeground();
                        return;
                    }
                    scheduleReconnect(reason == null || reason.isEmpty()
                            ? getString(R.string.error_connect_hint)
                            : reason);
                }
            });
        }

        @Override
        public void onFailure(final WebSocket webSocket, final Throwable t,
                              final Response response) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (webSocket != socket) {
                        return;
                    }
                    int responseCode = response == null ? 0 : response.code();
                    socket = null;
                    connecting = false;
                    awaitingHandshake = false;
                    handler.removeCallbacks(handshakeTimeoutRunnable);
                    if (intentionalStop) {
                        return;
                    }
                    if (responseCode == 401 || responseCode == 403) {
                        authFailed = true;
                        Log.e(TAG, "websocket handshake rejected http=" + responseCode);
                        store.setConnection(AgentStore.Connection.AUTH_FAILED,
                                getString(R.string.status_auth_failed));
                        updateForeground();
                        return;
                    }
                    String message = t == null ? null : t.getMessage();
                    Log.w(TAG, "websocket failure http=" + responseCode
                            + " message=" + message);
                    scheduleReconnect(message == null || message.isEmpty()
                            ? getString(R.string.error_connect_hint)
                            : message);
                }
            });
        }
    }

    private static boolean isAuthCloseCode(int code) {
        return code == 4001 || code == 4003 || code == 4401 || code == 4403
                || code == 1008;
    }

    private void handleMessage(WebSocket webSocket, String text) {
        if (webSocket != socket) {
            return;
        }
        JSONObject raw;
        try {
            raw = new JSONObject(text);
        } catch (JSONException e) {
            Log.w(TAG, "non-JSON frame ignored");
            return;
        }
        WireEvent event = Protocol.parseEvent(raw);
        if (event == null) {
            Log.w(TAG, "unknown or unsupported protocol frame ignored");
            return;
        }

        if (Protocol.HELLO_OK.equals(event.type)) {
            // Keep the handshake timeout armed until the authoritative snapshot
            // arrives. A server that never sends one leaves the watch stale.
            Log.i(TAG, "protocol hello.ok received");
            store.applyHello(event.payload);
            NotificationPresenter.sync(this, store);
            updateForeground();
            scheduleAck(event.seq);
            return;
        }
        if (Protocol.SNAPSHOT.equals(event.type)) {
            awaitingHandshake = false;
            reconnectAttempt = 0;
            handler.removeCallbacks(handshakeTimeoutRunnable);
            Log.i(TAG, "protocol snapshot received seq=" + event.seq
                    + " pending=" + (event.payload == null
                    ? -1 : event.payload.optJSONArray("pending") == null
                    ? 0 : event.payload.optJSONArray("pending").length()));
            store.applySnapshot(event.payload, event.seq);
            flushOutbox(webSocket);
            NotificationPresenter.sync(this, store);
            updateForeground();
            scheduleAck(event.seq);
            return;
        }

        Log.d(TAG, "protocol event " + event.type + " seq=" + event.seq);
        store.applyEvent(event);
        if (store.consumeResyncNeeded()) {
            Log.w(TAG, "event sequence gap detected, reconnecting for a snapshot");
            scheduleReconnect(getString(R.string.error_resync));
            return;
        }
        scheduleAck(event.seq);
        NotificationPresenter.sync(this, store);
        updateForeground();
    }
}
