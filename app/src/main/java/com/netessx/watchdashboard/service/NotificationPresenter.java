package com.netessx.watchdashboard.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.netessx.watchdashboard.App;
import com.netessx.watchdashboard.R;
import com.netessx.watchdashboard.data.AgentOption;
import com.netessx.watchdashboard.data.AgentRequest;
import com.netessx.watchdashboard.data.AgentStore;
import com.netessx.watchdashboard.data.TaskResult;
import com.netessx.watchdashboard.ui.MainActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the persistent connection notification and the actionable alerts.
 * Request/task alert markers are persisted by AgentStore, so reconnecting does
 * not buzz the wrist again for the same item.
 */
public final class NotificationPresenter {

    public static final int FG_NOTIFICATION_ID = 42;

    private static final int REQUEST_BASE = 10_000;
    private static final int TASK_BASE = 20_000;
    private static final int MAX_BIG_TEXT = 220;

    private static final Set<String> postedRequests = new HashSet<>();
    private static final Set<String> postedTasks = new HashSet<>();

    private NotificationPresenter() {
    }

    public static Notification foreground(Context context, AgentStore.Snapshot snapshot) {
        String title;
        switch (snapshot.connection) {
            case ONLINE:
                title = context.getString(R.string.notif_service_online);
                break;
            case CONNECTING:
                title = context.getString(R.string.notif_service_connecting);
                break;
            case RECONNECTING:
                title = context.getString(R.string.notif_service_reconnecting);
                break;
            case AUTH_FAILED:
                title = context.getString(R.string.notif_service_auth);
                break;
            default:
                title = context.getString(R.string.notif_service_offline);
                break;
        }

        String text;
        if (snapshot.outboxCount > 0) {
            text = context.getString(R.string.outbox_pending, snapshot.outboxCount);
        } else if (!snapshot.pending.isEmpty()) {
            text = context.getString(R.string.notif_service_text_pending, snapshot.pending.size());
        } else {
            text = context.getString(R.string.notif_service_text_idle);
        }

        Intent openIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                context, 0, openIntent, flags(PendingIntent.FLAG_UPDATE_CURRENT));

        Intent stopIntent = new Intent(context, AgentConnectionService.class)
                .setAction(AgentConnectionService.ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                context, 1, stopIntent, flags(PendingIntent.FLAG_UPDATE_CURRENT));

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context, App.CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_agent)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setColor(ContextCompat.getColor(context, R.color.accent))
                .addAction(0, context.getString(R.string.notif_action_stop), stopPending);
        if (!snapshot.serverName.isEmpty()) {
            builder.setSubText(snapshot.serverName);
        }
        return builder.build();
    }

    public static void sync(Context context, AgentStore store) {
        AgentStore.Snapshot snapshot = store.snapshot();
        long now = System.currentTimeMillis();
        boolean visible = App.isUiVisible();

        Set<String> currentRequestIds = new HashSet<>();
        for (AgentRequest request : snapshot.pending) {
            currentRequestIds.add(request.requestId);
            if (request.isExpired(now) || snapshot.notifiedRequestIds.contains(request.requestId)) {
                continue;
            }
            if (visible) {
                // The user is looking at the dashboard; do not buzz, but remember
                // it so leaving the app does not replay an old alert.
                store.markRequestNotified(request.requestId);
                continue;
            }
            if (snapshot.connection != AgentStore.Connection.ONLINE) {
                // Leave it unmarked so the alert fires after reconnection.
                continue;
            }
            postRequest(context, request);
            store.markRequestNotified(request.requestId);
            synchronized (NotificationPresenter.class) {
                postedRequests.add(request.requestId);
            }
        }
        synchronized (NotificationPresenter.class) {
            List<String> staleRequests = new ArrayList<>(postedRequests);
            for (String requestId : staleRequests) {
                if (!currentRequestIds.contains(requestId)) {
                    cancelRequest(context, requestId);
                    postedRequests.remove(requestId);
                }
            }
        }

        Set<String> currentTaskIds = new HashSet<>();
        for (TaskResult task : snapshot.recent) {
            currentTaskIds.add(task.taskId);
            if (snapshot.notifiedTaskIds.contains(task.taskId)) {
                continue;
            }
            boolean stale = task.completedAt > 0
                    && now - task.completedAt > 15 * 60 * 1000L;
            if (visible || stale) {
                store.markTaskNotified(task.taskId);
                continue;
            }
            postTask(context, task);
            store.markTaskNotified(task.taskId);
            synchronized (NotificationPresenter.class) {
                postedTasks.add(task.taskId);
            }
        }
        synchronized (NotificationPresenter.class) {
            List<String> staleTasks = new ArrayList<>(postedTasks);
            for (String taskId : staleTasks) {
                if (!currentTaskIds.contains(taskId)) {
                    cancelTask(context, taskId);
                    postedTasks.remove(taskId);
                }
            }
        }
    }

    /** Cancels action/event alerts, leaving the foreground service notification alone. */
    public static void cancelActionNotifications(Context context) {
        synchronized (NotificationPresenter.class) {
            for (String requestId : postedRequests) {
                cancelRequest(context, requestId);
            }
            for (String taskId : postedTasks) {
                cancelTask(context, taskId);
            }
            postedRequests.clear();
            postedTasks.clear();
        }
    }

    private static void postRequest(Context context, AgentRequest request) {
        String agent = request.agentName.isEmpty()
                ? context.getString(R.string.unknown_agent)
                : request.agentName;
        String typeTitle = request.isPermission()
                ? context.getString(R.string.notif_permission_title, agent)
                : context.getString(R.string.notif_question_title, agent);
        String text = request.displayTitle();
        if (text.isEmpty()) {
            text = request.isPermission()
                    ? context.getString(R.string.type_permission)
                    : context.getString(R.string.type_question);
        }

        Intent openIntent = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_REQUEST, request.requestId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                context,
                REQUEST_BASE + (request.requestId.hashCode() & 0x0FFFFFFF),
                openIntent,
                flags(PendingIntent.FLAG_UPDATE_CURRENT));

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context, App.CHANNEL_ACTIONS)
                .setSmallIcon(R.drawable.ic_stat_agent)
                .setContentTitle(typeTitle)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText(request)))
                .setContentIntent(openPending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setWhen(request.createdAt > 0 ? request.createdAt : System.currentTimeMillis())
                .setColor(ContextCompat.getColor(context,
                        request.isHighRisk() ? R.color.danger : R.color.warn));

        List<AgentOption> actions = new ArrayList<>();
        AgentOption approve = firstOption(request, true);
        AgentOption deny = firstOption(request, false);
        if (request.isPermission()) {
            if (request.isHighRisk()) {
                // High-risk approvals must use the hold-to-confirm screen.
                if (deny != null) {
                    actions.add(deny);
                }
            } else {
                if (approve != null) {
                    actions.add(approve);
                }
                if (deny != null) {
                    actions.add(deny);
                }
            }
        } else {
            int added = 0;
            for (AgentOption option : request.effectiveOptions()) {
                if (added >= 2) {
                    break;
                }
                actions.add(option);
                added++;
            }
        }
        for (AgentOption option : actions) {
            builder.addAction(0, option.label, responsePending(context, request, option));
        }
        builder.addAction(0, context.getString(R.string.action_open), openPending);

        notify(context, requestNotificationId(request.requestId), builder.build());
    }

    private static void postTask(Context context, TaskResult task) {
        int titleRes;
        if (task.isSuccess()) {
            titleRes = R.string.notif_task_title;
        } else if ("cancelled".equalsIgnoreCase(task.outcome)) {
            titleRes = R.string.notif_task_cancelled_title;
        } else {
            titleRes = R.string.notif_task_failed_title;
        }
        String title = context.getString(titleRes,
                task.title.isEmpty() ? context.getString(R.string.unknown_agent) : task.title);
        String agent = task.agentName.isEmpty()
                ? context.getString(R.string.unknown_agent)
                : task.agentName;
        String summary = task.summary.isEmpty()
                ? context.getString(R.string.task_summary_fallback)
                : task.summary;

        Intent openIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                context,
                TASK_BASE + (task.taskId.hashCode() & 0x0FFFFFFF),
                openIntent,
                flags(PendingIntent.FLAG_UPDATE_CURRENT));

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context, App.CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_stat_agent)
                .setContentTitle(title)
                .setContentText(agent + context.getString(R.string.separator_dot) + summary)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(agent + "\n" + summary))
                .setContentIntent(openPending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setWhen(task.completedAt > 0 ? task.completedAt : System.currentTimeMillis())
                .setColor(ContextCompat.getColor(context,
                        task.isSuccess() ? R.color.accent : R.color.warn));

        notify(context, taskNotificationId(task.taskId), builder.build());
    }

    private static PendingIntent responsePending(Context context, AgentRequest request,
                                                 AgentOption option) {
        Intent intent = new Intent(context, AgentConnectionService.class)
                .setAction(AgentConnectionService.ACTION_SEND_RESPONSE)
                .putExtra(AgentConnectionService.EXTRA_REQUEST_ID, request.requestId)
                .putExtra(AgentConnectionService.EXTRA_OPTION_ID, option.id)
                .putExtra(AgentConnectionService.EXTRA_FROM_NOTIFICATION, true);
        int requestCode = (request.requestId + "#" + option.id).hashCode() & 0x7FFFFFFF;
        int flags = flags(PendingIntent.FLAG_UPDATE_CURRENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(context, requestCode, intent, flags);
        }
        return PendingIntent.getService(context, requestCode, intent, flags);
    }

    private static AgentOption firstOption(AgentRequest request, boolean approve) {
        for (AgentOption option : request.effectiveOptions()) {
            if (approve ? option.isApprove() : option.isDeny()) {
                return option;
            }
        }
        return null;
    }

    private static String bigText(AgentRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append(request.displayTitle());
        String body = request.displayBody();
        if (!body.isEmpty()) {
            builder.append('\n').append(body);
        }
        if (builder.length() > MAX_BIG_TEXT) {
            return builder.substring(0, MAX_BIG_TEXT) + "…";
        }
        return builder.toString();
    }

    private static int flags(int base) {
        return base | PendingIntent.FLAG_IMMUTABLE;
    }

    private static int requestNotificationId(String requestId) {
        return REQUEST_BASE + (requestId.hashCode() & 0x0FFFFFFF);
    }

    private static int taskNotificationId(String taskId) {
        return TASK_BASE + (taskId.hashCode() & 0x0FFFFFFF);
    }

    private static void cancelRequest(Context context, String requestId) {
        NotificationManagerCompat.from(context).cancel(requestNotificationId(requestId));
    }

    private static void cancelTask(Context context, String taskId) {
        NotificationManagerCompat.from(context).cancel(taskNotificationId(taskId));
    }

    private static void notify(Context context, int id, Notification notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification);
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS may be denied; the service can still run.
        }
    }
}
