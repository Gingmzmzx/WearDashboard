package com.netessx.watchdashboard.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.netessx.watchdashboard.App;
import com.netessx.watchdashboard.R;
import com.netessx.watchdashboard.data.AgentOption;
import com.netessx.watchdashboard.data.AgentRequest;
import com.netessx.watchdashboard.data.AgentStore;
import com.netessx.watchdashboard.data.Settings;
import com.netessx.watchdashboard.service.AgentConnectionService;
import com.netessx.watchdashboard.service.NotificationPresenter;

/** Single-activity, three-screen watch app (dashboard, request detail, settings). */
public class MainActivity extends AppCompatActivity
        implements AgentStore.Listener,
        DashboardScreen.Listener,
        PhoneDashboardScreen.Listener,
        RequestScreen.Listener,
        SettingsScreen.Listener {

    public static final String EXTRA_OPEN_REQUEST = "open_request";

    private static final String EXTRA_SERVER_URL = "server_url";
    private static final String EXTRA_TOKEN = "token";
    private static final String EXTRA_DEVICE_NAME = "device_name";
    private static final int REQUEST_NOTIFICATIONS = 17;

    private enum Mode {
        DASHBOARD,
        REQUEST,
        SETTINGS
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private AgentStore store;

    private Mode mode = Mode.DASHBOARD;
    private boolean phoneLayout;
    private String currentRequestId;
    private String pendingOpenRequestId;
    private DashboardScreen dashboardScreen;
    private PhoneDashboardScreen phoneDashboardScreen;
    private RequestScreen requestScreen;
    private SettingsScreen settingsScreen;
    private PhoneSettingsScreen phoneSettingsScreen;
    private int tickCount;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (dashboardScreen != null) {
                dashboardScreen.tick(now);
            }
            if (requestScreen != null) {
                requestScreen.tick(now);
            }
            tickCount++;
            if (tickCount % 30 == 0) {
                store.pruneExpired();
            }
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        root = new FrameLayout(this);
        root.setBackgroundColor(UiKit.BG);
        ViewCompat.setOnApplyWindowInsetsListener(root, new androidx.core.view.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(android.view.View v,
                                                          WindowInsetsCompat insets) {
                int types = WindowInsetsCompat.Type.systemBars()
                        | WindowInsetsCompat.Type.displayCutout();
                Insets bars = insets.getInsets(types);
                Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
                int bottom = ime.bottom > 0 ? ime.bottom : bars.bottom;
                v.setPadding(bars.left, bars.top, bars.right, bottom);
                return insets;
            }
        });
        setContentView(root);

        phoneLayout = detectPhoneLayout();
        store = AgentStore.get(this);
        handleProvisioning(getIntent());
        pendingOpenRequestId = getIntent().getStringExtra(EXTRA_OPEN_REQUEST);
        render();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleProvisioning(intent);
        String requestId = intent.getStringExtra(EXTRA_OPEN_REQUEST);
        if (requestId != null) {
            pendingOpenRequestId = requestId;
        }
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        App.onActivityStarted();
        store.addListener(this);
        if (Settings.load(this).isConfigured()) {
            AgentConnectionService.start(this);
        }
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(ticker);
        store.removeListener(this);
        App.onActivityStopped();
        super.onStop();
    }

    @Override
    public void onStoreChanged() {
        render();
    }

    // ---- Navigation and rendering -------------------------------------------

    private void render() {
        AgentStore.Snapshot snapshot = store.snapshot();

        if (pendingOpenRequestId != null) {
            currentRequestId = pendingOpenRequestId;
            pendingOpenRequestId = null;
            requestScreen = null;
            mode = Mode.REQUEST;
        }

        if (mode == Mode.SETTINGS) {
            if (settingsScreen != null) {
                settingsScreen.refresh(snapshot.connection, snapshot.lastError);
            }
            return;
        }

        if (mode == Mode.REQUEST) {
            AgentRequest request = currentRequestId == null
                    ? null
                    : store.getPending(currentRequestId);
            if (request != null) {
                if (requestScreen == null) {
                    requestScreen = new RequestScreen(this, request, snapshot.connection,
                            this, phoneLayout);
                    attach(requestScreen);
                } else {
                    requestScreen.refresh(snapshot.connection);
                }
                dashboardScreen = null;
                phoneDashboardScreen = null;
                return;
            }
            mode = Mode.DASHBOARD;
            currentRequestId = null;
            requestScreen = null;
        }

        showDashboard(snapshot);
    }

    private void showDashboard(AgentStore.Snapshot snapshot) {
        int previousScroll = 0;
        if (dashboardScreen != null) {
            previousScroll = dashboardScreen.getScrollY();
        } else if (phoneDashboardScreen != null) {
            previousScroll = phoneDashboardScreen.getScrollY();
        }

        mode = Mode.DASHBOARD;
        currentRequestId = null;
        requestScreen = null;
        settingsScreen = null;
        phoneSettingsScreen = null;
        final android.view.View next;
        if (phoneLayout) {
            dashboardScreen = null;
            phoneDashboardScreen = new PhoneDashboardScreen(this, snapshot, this);
            next = phoneDashboardScreen;
        } else {
            phoneDashboardScreen = null;
            dashboardScreen = new DashboardScreen(this, snapshot, this);
            next = dashboardScreen;
        }
        attach(next);
        if (previousScroll > 0) {
            final int scroll = previousScroll;
            next.post(new Runnable() {
                @Override
                public void run() {
                    next.scrollTo(0, scroll);
                }
            });
        }
    }

    private void showSettings() {
        mode = Mode.SETTINGS;
        dashboardScreen = null;
        phoneDashboardScreen = null;
        requestScreen = null;
        Settings settings = Settings.load(this);
        if (phoneLayout) {
            settingsScreen = null;
            phoneSettingsScreen = new PhoneSettingsScreen(this, settings, store.snapshot(),
                    notificationsGranted(), this);
            attach(phoneSettingsScreen);
        } else {
            phoneSettingsScreen = null;
            settingsScreen = new SettingsScreen(this, settings, store.snapshot(),
                    notificationsGranted(), this);
            attach(settingsScreen);
        }
    }

    private void attach(android.view.View view) {
        root.removeAllViews();
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ---- Dashboard actions ---------------------------------------------------

    @Override
    public void onOpenSettings() {
        showSettings();
    }

    @Override
    public void onOpenRequest(AgentRequest request) {
        currentRequestId = request.requestId;
        mode = Mode.REQUEST;
        render();
    }

    @Override
    public void onConfigure() {
        showSettings();
    }

    @Override
    public void onReconnect() {
        AgentConnectionService.start(this);
    }

    // ---- Request actions -----------------------------------------------------

    @Override
    public void onBack() {
        hideKeyboard();
        mode = Mode.DASHBOARD;
        currentRequestId = null;
        requestScreen = null;
        render();
    }

    @Override
    public void onRespond(AgentRequest request, AgentOption option) {
        submitResponse(request, option, true);
    }

    @Override
    public void onQuickRespond(AgentRequest request, AgentOption option) {
        submitResponse(request, option, false);
    }

    private void submitResponse(AgentRequest request, AgentOption option, boolean leaveScreen) {
        if (!store.isOnline()) {
            toast(getString(R.string.error_offline_action));
            return;
        }
        AgentRequest pending = store.getPending(request.requestId);
        if (pending == null) {
            toast(getString(R.string.toast_request_gone));
            if (leaveScreen) {
                onBack();
            } else {
                render();
            }
            return;
        }
        if (pending.isExpired(System.currentTimeMillis())) {
            store.pruneExpired();
            toast(getString(R.string.toast_request_gone));
            render();
            return;
        }
        AgentConnectionService.sendResponse(this, request.requestId, option.id);
        toast(getString(R.string.toast_sent));
        if (leaveScreen) {
            onBack();
        }
    }

    // ---- Settings actions ----------------------------------------------------

    @Override
    public void onSave(String serverUrl, String token, String deviceName) {
        Settings before = Settings.load(this);
        String error = Settings.save(this, serverUrl, token, deviceName);
        if (error != null) {
            toast(error);
            return;
        }
        Settings after = Settings.load(this);
        boolean endpointChanged = !before.serverUrl.equals(after.serverUrl)
                || !before.token.equals(after.token);
        if (endpointChanged) {
            store.resetForConfig();
            NotificationPresenter.cancelActionNotifications(this);
        }
        AgentConnectionService.reconnectWithConfig(this);
        ensureNotificationPermission();
        hideKeyboard();
        toast(getString(R.string.toast_saved));
        mode = Mode.DASHBOARD;
        settingsScreen = null;
        phoneSettingsScreen = null;
        render();
    }

    @Override
    public void onDisconnect() {
        AgentConnectionService.stop(this);
        hideKeyboard();
        toast(getString(R.string.toast_disconnected));
        mode = Mode.DASHBOARD;
        settingsScreen = null;
        phoneSettingsScreen = null;
        render();
    }

    // ---- Back / provisioning / permissions ----------------------------------

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (mode != Mode.DASHBOARD) {
            hideKeyboard();
            mode = Mode.DASHBOARD;
            currentRequestId = null;
            requestScreen = null;
            settingsScreen = null;
            render();
            return;
        }
        super.onBackPressed();
    }

    private void handleProvisioning(Intent intent) {
        if (intent == null) {
            return;
        }
        String url = intent.getStringExtra(EXTRA_SERVER_URL);
        String token = intent.getStringExtra(EXTRA_TOKEN);
        String name = intent.getStringExtra(EXTRA_DEVICE_NAME);
        if (url == null && token == null && name == null) {
            return;
        }
        intent.removeExtra(EXTRA_SERVER_URL);
        intent.removeExtra(EXTRA_TOKEN);
        intent.removeExtra(EXTRA_DEVICE_NAME);
        if (url == null || token == null) {
            toast(getString(R.string.error_credentials));
            return;
        }
        String error = Settings.save(this, url, token, name);
        if (error != null) {
            toast(getString(R.string.toast_provision_failed, error));
            return;
        }
        store.resetForConfig();
        AgentConnectionService.reconnectWithConfig(this);
        ensureNotificationPermission();
        toast(getString(R.string.toast_provision_saved));
    }

    private boolean detectPhoneLayout() {
        Configuration configuration = getResources().getConfiguration();
        boolean watch = getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
        return !watch && configuration.smallestScreenWidthDp >= 300;
    }

    private boolean notificationsGranted() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }

    private void hideKeyboard() {
        InputMethodManager manager =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null && root != null) {
            manager.hideSoftInputFromWindow(root.getWindowToken(), 0);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
