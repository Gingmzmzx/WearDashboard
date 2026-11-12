package com.netessx.watchdashboard;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/**
 * Process-wide bootstrap. Also tracks whether any activity is visible so the
 * background service can avoid posting duplicate alert notifications for
 * events the user is already looking at.
 */
public class App extends Application {

    public static final String CHANNEL_ACTIONS = "watch_agent_actions";
    public static final String CHANNEL_EVENTS = "watch_agent_events";
    public static final String CHANNEL_SERVICE = "watch_agent_service";

    private static int startedActivities;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
    }

    public static void onActivityStarted() {
        startedActivities++;
    }

    public static void onActivityStopped() {
        startedActivities = Math.max(0, startedActivities - 1);
    }

    public static boolean isUiVisible() {
        return startedActivities > 0;
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        NotificationChannel actions = new NotificationChannel(
                CHANNEL_ACTIONS,
                getString(R.string.notif_channel_actions),
                NotificationManager.IMPORTANCE_HIGH);
        actions.setDescription(getString(R.string.notif_channel_actions_desc));
        actions.enableVibration(true);
        actions.setVibrationPattern(new long[]{0, 120, 80, 120});
        actions.setShowBadge(true);
        manager.createNotificationChannel(actions);

        NotificationChannel events = new NotificationChannel(
                CHANNEL_EVENTS,
                getString(R.string.notif_channel_events),
                NotificationManager.IMPORTANCE_LOW);
        events.setDescription(getString(R.string.notif_channel_events_desc));
        events.setShowBadge(false);
        manager.createNotificationChannel(events);

        NotificationChannel service = new NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.notif_channel_service),
                NotificationManager.IMPORTANCE_LOW);
        service.setDescription(getString(R.string.notif_channel_service_desc));
        service.setShowBadge(false);
        manager.createNotificationChannel(service);
    }
}
