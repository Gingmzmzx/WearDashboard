package com.netessx.watchdashboard.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.netessx.watchdashboard.net.Protocol;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single source of truth for the watch UI. Network callbacks write here; the
 * activity and notification presenter observe it. All mutating methods are
 * synchronized, and listeners are always notified on the main thread.
 */
public final class AgentStore {

    private static final String TAG = "AgentStore";
    private static final String PREFS = "watch_agent_state";
    private static final String KEY_STATE = "state";
    private static final int MAX_RECENT = 10;
    private static final long OUTBOX_TTL_MS = 5 * 60 * 1000L;

    private static AgentStore instance;

    public enum Connection {
        UNCONFIGURED,
        CONNECTING,
        RECONNECTING,
        ONLINE,
        DISCONNECTED,
        AUTH_FAILED
    }

    public interface Listener {
        void onStoreChanged();
    }

    public static final class Snapshot {
        public final List<AgentInfo> agents;
        public final List<AgentRequest> pending;
        public final List<TaskResult> recent;
        public final Set<String> notifiedRequestIds;
        public final Set<String> notifiedTaskIds;
        public final Connection connection;
        public final String lastError;
        public final String serverName;
        public final int outboxCount;
        public final long lastSeq;
        public final boolean configured;

        Snapshot(List<AgentInfo> agents, List<AgentRequest> pending, List<TaskResult> recent,
                 Set<String> notifiedRequestIds, Set<String> notifiedTaskIds,
                 Connection connection, String lastError, String serverName,
                 int outboxCount, long lastSeq, boolean configured) {
            this.agents = agents;
            this.pending = pending;
            this.recent = recent;
            this.notifiedRequestIds = notifiedRequestIds;
            this.notifiedTaskIds = notifiedTaskIds;
            this.connection = connection;
            this.lastError = lastError;
            this.serverName = serverName;
            this.outboxCount = outboxCount;
            this.lastSeq = lastSeq;
            this.configured = configured;
        }
    }

    private static final class OutboxEntry {
        final JSONObject response;
        final long expiresAt;

        OutboxEntry(JSONObject response, long expiresAt) {
            this.response = response;
            this.expiresAt = expiresAt;
        }
    }

    private final Context appContext;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private final Map<String, AgentInfo> agents = new LinkedHashMap<>();
    private final Map<String, AgentRequest> pending = new LinkedHashMap<>();
    private final List<TaskResult> recent = new ArrayList<>();
    private final Map<String, OutboxEntry> outbox = new LinkedHashMap<>();
    private final Set<String> notifiedRequests = new LinkedHashSet<>();
    private final Set<String> notifiedTasks = new LinkedHashSet<>();

    private Connection connection;
    private String lastError = "";
    private String serverName = "";
    private String sessionId = "";
    private long lastSeq = 0L;
    private boolean configured;
    private boolean resyncNeeded;

    private final Runnable notifyRunnable = new Runnable() {
        @Override
        public void run() {
            for (Listener listener : listeners) {
                listener.onStoreChanged();
            }
        }
    };

    public static AgentStore get(Context context) {
        synchronized (AgentStore.class) {
            if (instance == null) {
                instance = new AgentStore(context.getApplicationContext());
            }
            return instance;
        }
    }

    private AgentStore(Context context) {
        this.appContext = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.configured = Settings.load(context).isConfigured();
        this.connection = configured ? Connection.DISCONNECTED : Connection.UNCONFIGURED;
        loadState();
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyChanged() {
        mainHandler.removeCallbacks(notifyRunnable);
        mainHandler.post(notifyRunnable);
    }

    public synchronized Snapshot snapshot() {
        List<AgentRequest> pendingCopy = new ArrayList<>(pending.values());
        Collections.sort(pendingCopy, new Comparator<AgentRequest>() {
            @Override
            public int compare(AgentRequest a, AgentRequest b) {
                long aExpiry = a.expiresAt > 0 ? a.expiresAt : Long.MAX_VALUE;
                long bExpiry = b.expiresAt > 0 ? b.expiresAt : Long.MAX_VALUE;
                if (aExpiry != bExpiry) {
                    return aExpiry < bExpiry ? -1 : 1;
                }
                return Long.compare(a.createdAt, b.createdAt);
            }
        });
        return new Snapshot(
                new ArrayList<>(agents.values()),
                pendingCopy,
                new ArrayList<>(recent),
                new LinkedHashSet<>(notifiedRequests),
                new LinkedHashSet<>(notifiedTasks),
                connection,
                lastError,
                serverName,
                outbox.size(),
                lastSeq,
                configured);
    }

    public synchronized AgentRequest getPending(String requestId) {
        return requestId == null ? null : pending.get(requestId);
    }

    public synchronized boolean isOnline() {
        return connection == Connection.ONLINE;
    }

    public synchronized long getLastSeq() {
        return lastSeq;
    }

    /** Consumes the "sequence gap" flag. The service reconnects for a snapshot. */
    public synchronized boolean consumeResyncNeeded() {
        boolean needed = resyncNeeded;
        resyncNeeded = false;
        return needed;
    }

    public void setConnection(Connection newConnection, String error) {
        String safeError = error == null ? "" : error;
        synchronized (this) {
            if (connection == newConnection && lastError.equals(safeError)) {
                return;
            }
            connection = newConnection;
            lastError = safeError;
        }
        notifyChanged();
    }

    public void applyHello(JSONObject payload) {
        if (payload == null) {
            return;
        }
        synchronized (this) {
            serverName = payload.optString("serverName", serverName);
            sessionId = payload.optString("sessionId", sessionId);
            connection = Connection.ONLINE;
            lastError = "";
            persist();
        }
        notifyChanged();
    }

    public void applySnapshot(JSONObject payload, long seq) {
        if (payload == null) {
            return;
        }
        List<AgentInfo> incomingAgents = Protocol.parseAgents(payload.optJSONArray("agents"));
        List<AgentRequest> incomingPending = Protocol.parseRequests(payload.optJSONArray("pending"));
        List<TaskResult> incomingRecent = Protocol.parseTasks(payload.optJSONArray("recent"));
        long now = System.currentTimeMillis();

        synchronized (this) {
            agents.clear();
            for (AgentInfo agent : incomingAgents) {
                agents.put(agent.id, agent);
            }

            Set<String> serverPendingIds = new HashSet<>();
            for (AgentRequest request : incomingPending) {
                if (!request.isExpired(now)) {
                    serverPendingIds.add(request.requestId);
                }
            }

            // Decisions that the server already resolved are dropped. Decisions
            // that are still pending are replayed after the snapshot.
            Iterator<Map.Entry<String, OutboxEntry>> outboxIterator =
                    outbox.entrySet().iterator();
            while (outboxIterator.hasNext()) {
                Map.Entry<String, OutboxEntry> entry = outboxIterator.next();
                boolean expired = entry.getValue().expiresAt > 0
                        && entry.getValue().expiresAt <= now;
                if (expired || !serverPendingIds.contains(entry.getKey())) {
                    outboxIterator.remove();
                }
            }

            pending.clear();
            for (AgentRequest request : incomingPending) {
                if (request.isExpired(now) || outbox.containsKey(request.requestId)) {
                    continue;
                }
                pending.put(request.requestId, request);
            }

            recent.clear();
            Set<String> recentTaskIds = new HashSet<>();
            for (TaskResult task : incomingRecent) {
                if (task.taskId.isEmpty() || !recentTaskIds.add(task.taskId)) {
                    continue;
                }
                recent.add(task);
                if (recent.size() >= MAX_RECENT) {
                    break;
                }
            }

            notifiedRequests.retainAll(pending.keySet());
            notifiedTasks.retainAll(recentTaskIds);

            // A snapshot is authoritative, including after a server restart
            // where the sequence counter may have reset to zero.
            lastSeq = seq;
            resyncNeeded = false;
            connection = Connection.ONLINE;
            lastError = "";
            configured = true;
            persist();
        }
        notifyChanged();
    }

    public void applyEvent(WireEvent event) {
        if (event == null) {
            return;
        }
        boolean changed = false;
        synchronized (this) {
            if (event.seq > 0) {
                if (event.seq <= lastSeq) {
                    return;
                }
                if (event.seq > lastSeq + 1) {
                    // A missing sequence means a broadcast was dropped or arrived
                    // out of order. Keep the event, then reconnect for a snapshot.
                    resyncNeeded = true;
                }
                lastSeq = event.seq;
            }

            long now = System.currentTimeMillis();
            if (Protocol.PERMISSION_REQUEST.equals(event.type)) {
                AgentRequest request = Protocol.parseRequest(event.payload, "permission");
                if (request != null && !request.isExpired(now)
                        && !outbox.containsKey(request.requestId)) {
                    pending.put(request.requestId, request);
                    changed = true;
                }
            } else if (Protocol.QUESTION_REQUEST.equals(event.type)) {
                AgentRequest request = Protocol.parseRequest(event.payload, "question");
                if (request != null && !request.isExpired(now)
                        && !outbox.containsKey(request.requestId)) {
                    pending.put(request.requestId, request);
                    changed = true;
                }
            } else if (Protocol.AGENT_STATUS.equals(event.type)) {
                AgentInfo agent = Protocol.parseAgent(event.payload);
                if (agent != null) {
                    agents.put(agent.id, agent);
                    changed = true;
                }
            } else if (Protocol.REQUEST_RESOLVED.equals(event.type)) {
                String requestId = event.payload.optString("requestId", "");
                if (!requestId.isEmpty()) {
                    pending.remove(requestId);
                    outbox.remove(requestId);
                    notifiedRequests.remove(requestId);
                    changed = true;
                }
            } else if (Protocol.TASK_COMPLETED.equals(event.type)) {
                TaskResult task = Protocol.parseTask(event.payload, event.ts);
                if (task != null) {
                    Iterator<TaskResult> iterator = recent.iterator();
                    while (iterator.hasNext()) {
                        if (iterator.next().taskId.equals(task.taskId)) {
                            iterator.remove();
                        }
                    }
                    recent.add(0, task);
                    while (recent.size() > MAX_RECENT) {
                        recent.remove(recent.size() - 1);
                    }
                    Set<String> taskIds = new HashSet<>();
                    for (TaskResult item : recent) {
                        taskIds.add(item.taskId);
                    }
                    notifiedTasks.retainAll(taskIds);
                    changed = true;
                }
            } else if (Protocol.ERROR.equals(event.type)) {
                String requestId = event.payload.optString("requestId", "");
                if (!requestId.isEmpty()) {
                    pending.remove(requestId);
                    outbox.remove(requestId);
                    notifiedRequests.remove(requestId);
                }
                String message = event.payload.optString("message", "");
                String code = event.payload.optString("code", "");
                lastError = !message.isEmpty() ? message : code;
                changed = true;
            }

            if (changed) {
                persist();
            }
        }
        if (changed) {
            notifyChanged();
        }
    }

    /**
     * Queues a decision and removes the request from the visible pending list.
     * The decision remains in the outbox until the server resolves the request,
     * so a dropped socket cannot silently lose an approval.
     */
    public boolean enqueueResponse(String requestId, JSONObject response) {
        if (requestId == null || requestId.isEmpty() || response == null) {
            return false;
        }
        synchronized (this) {
            AgentRequest request = pending.remove(requestId);
            if (request == null) {
                return false;
            }
            notifiedRequests.remove(requestId);
            long expiresAt = request.expiresAt > 0
                    ? request.expiresAt
                    : System.currentTimeMillis() + OUTBOX_TTL_MS;
            outbox.put(requestId, new OutboxEntry(response, expiresAt));
            persist();
        }
        notifyChanged();
        return true;
    }

    /** Responses that still need to reach the server, oldest first. */
    public synchronized List<String> outboxMessages() {
        List<String> messages = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (OutboxEntry entry : outbox.values()) {
            if (entry.expiresAt > 0 && entry.expiresAt <= now) {
                continue;
            }
            messages.add(entry.response.toString());
        }
        return messages;
    }

    public synchronized int outboxCount() {
        return outbox.size();
    }

    /** @return true if this call persisted the first notification for the request. */
    public synchronized boolean markRequestNotified(String requestId) {
        if (requestId == null || !pending.containsKey(requestId)) {
            return false;
        }
        boolean added = notifiedRequests.add(requestId);
        if (added) {
            persist();
        }
        return added;
    }

    public synchronized boolean markTaskNotified(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return false;
        }
        boolean exists = false;
        for (TaskResult task : recent) {
            if (task.taskId.equals(taskId)) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            return false;
        }
        boolean added = notifiedTasks.add(taskId);
        if (added) {
            persist();
        }
        return added;
    }

    /** Drops expired requests and stale outbox entries. */
    public void pruneExpired() {
        boolean changed = false;
        synchronized (this) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, AgentRequest>> pendingIterator =
                    pending.entrySet().iterator();
            while (pendingIterator.hasNext()) {
                if (pendingIterator.next().getValue().isExpired(now)) {
                    pendingIterator.remove();
                    changed = true;
                }
            }
            Iterator<Map.Entry<String, OutboxEntry>> outboxIterator =
                    outbox.entrySet().iterator();
            while (outboxIterator.hasNext()) {
                OutboxEntry entry = outboxIterator.next().getValue();
                if (entry.expiresAt > 0 && entry.expiresAt <= now) {
                    outboxIterator.remove();
                    changed = true;
                }
            }
            if (changed) {
                notifiedRequests.retainAll(pending.keySet());
                persist();
            }
        }
        if (changed) {
            notifyChanged();
        }
    }

    /** Clears cached state after the endpoint or token changes. */
    public void resetForConfig() {
        synchronized (this) {
            agents.clear();
            pending.clear();
            recent.clear();
            outbox.clear();
            notifiedRequests.clear();
            notifiedTasks.clear();
            lastSeq = 0L;
            resyncNeeded = false;
            lastError = "";
            serverName = "";
            sessionId = "";
            configured = Settings.load(appContext).isConfigured();
            connection = configured ? Connection.DISCONNECTED : Connection.UNCONFIGURED;
            persist();
        }
        notifyChanged();
    }

    private void persist() {
        persist(false);
    }

    private void persist(boolean synchronous) {
        try {
            JSONObject state = new JSONObject();
            state.put("lastSeq", lastSeq);
            state.put("serverName", serverName);

            JSONArray agentArray = new JSONArray();
            for (AgentInfo agent : agents.values()) {
                agentArray.put(agentToJson(agent));
            }
            state.put("agents", agentArray);

            JSONArray pendingArray = new JSONArray();
            for (AgentRequest request : pending.values()) {
                pendingArray.put(requestToJson(request));
            }
            state.put("pending", pendingArray);

            JSONArray recentArray = new JSONArray();
            for (TaskResult task : recent) {
                recentArray.put(taskToJson(task));
            }
            state.put("recent", recentArray);

            JSONObject outboxObject = new JSONObject();
            for (Map.Entry<String, OutboxEntry> entry : outbox.entrySet()) {
                JSONObject item = new JSONObject();
                item.put("expiresAt", entry.getValue().expiresAt);
                item.put("response", entry.getValue().response);
                outboxObject.put(entry.getKey(), item);
            }
            state.put("outbox", outboxObject);

            state.put("notifiedRequests", new JSONArray(notifiedRequests));
            state.put("notifiedTasks", new JSONArray(notifiedTasks));

            if (synchronous) {
                prefs.edit().putString(KEY_STATE, state.toString()).commit();
            } else {
                prefs.edit().putString(KEY_STATE, state.toString()).apply();
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to persist agent state", e);
        }
    }

    private void loadState() {
        String raw = prefs.getString(KEY_STATE, null);
        if (raw == null || raw.isEmpty()) {
            return;
        }
        try {
            JSONObject state = new JSONObject(raw);
            lastSeq = state.optLong("lastSeq", 0L);
            serverName = state.optString("serverName", "");

            for (AgentInfo agent : Protocol.parseAgents(state.optJSONArray("agents"))) {
                agents.put(agent.id, agent);
            }

            long now = System.currentTimeMillis();
            for (AgentRequest request : Protocol.parseRequests(state.optJSONArray("pending"))) {
                if (!request.isExpired(now)) {
                    pending.put(request.requestId, request);
                }
            }

            Set<String> taskIds = new HashSet<>();
            for (TaskResult task : Protocol.parseTasks(state.optJSONArray("recent"))) {
                if (recent.size() >= MAX_RECENT || !taskIds.add(task.taskId)) {
                    continue;
                }
                recent.add(task);
            }

            JSONObject outboxObject = state.optJSONObject("outbox");
            if (outboxObject != null) {
                Iterator<String> keys = outboxObject.keys();
                while (keys.hasNext()) {
                    String requestId = keys.next();
                    JSONObject item = outboxObject.optJSONObject(requestId);
                    if (item == null) {
                        continue;
                    }
                    JSONObject response = item.optJSONObject("response");
                    if (response != null) {
                        outbox.put(requestId,
                                new OutboxEntry(response, item.optLong("expiresAt", 0L)));
                    }
                }
            }

            JSONArray requestNotifications = state.optJSONArray("notifiedRequests");
            if (requestNotifications != null) {
                for (int i = 0; i < requestNotifications.length(); i++) {
                    notifiedRequests.add(requestNotifications.optString(i, ""));
                }
            }
            JSONArray taskNotifications = state.optJSONArray("notifiedTasks");
            if (taskNotifications != null) {
                for (int i = 0; i < taskNotifications.length(); i++) {
                    notifiedTasks.add(taskNotifications.optString(i, ""));
                }
            }

            notifiedRequests.retainAll(pending.keySet());
            notifiedTasks.retainAll(taskIds);
        } catch (JSONException e) {
            Log.w(TAG, "Failed to restore agent state", e);
        }
    }

    private static JSONObject agentToJson(AgentInfo agent) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", agent.id);
        object.put("name", agent.name);
        object.put("status", agent.status);
        object.put("detail", agent.detail);
        object.put("task", agent.task);
        object.put("updatedAt", agent.updatedAt);
        return object;
    }

    private static JSONObject requestToJson(AgentRequest request) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("requestId", request.requestId);
        object.put("kind", request.kind);
        object.put("agentId", request.agentId);
        object.put("agentName", request.agentName);
        object.put("title", request.title);
        object.put("detail", request.detail);
        object.put("risk", request.risk);
        object.put("question", request.question);
        object.put("context", request.context);
        object.put("createdAt", request.createdAt);
        object.put("expiresAt", request.expiresAt);
        object.put("allowFreeText", request.allowFreeText);
        JSONArray options = new JSONArray();
        for (AgentOption option : request.options) {
            options.put(new JSONObject()
                    .put("id", option.id)
                    .put("label", option.label)
                    .put("style", option.style));
        }
        object.put("options", options);
        return object;
    }

    private static JSONObject taskToJson(TaskResult task) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("taskId", task.taskId);
        object.put("agentId", task.agentId);
        object.put("agentName", task.agentName);
        object.put("title", task.title);
        object.put("summary", task.summary);
        object.put("outcome", task.outcome);
        object.put("completedAt", task.completedAt);
        object.put("durationMs", task.durationMs);
        return object;
    }
}
