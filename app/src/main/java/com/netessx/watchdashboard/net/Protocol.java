package com.netessx.watchdashboard.net;

import com.netessx.watchdashboard.data.AgentInfo;
import com.netessx.watchdashboard.data.AgentOption;
import com.netessx.watchdashboard.data.AgentRequest;
import com.netessx.watchdashboard.data.TaskResult;
import com.netessx.watchdashboard.data.WireEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Codec for the watch-agent protocol v1. See docs/PROTOCOL.md. */
public final class Protocol {

    public static final int VERSION = 1;

    public static final String HELLO = "hello";
    public static final String HELLO_OK = "hello.ok";
    public static final String SNAPSHOT = "snapshot";
    public static final String ACK = "ack";
    public static final String REQUEST_RESPOND = "request.respond";
    public static final String AGENT_STATUS = "agent.status";
    public static final String PERMISSION_REQUEST = "permission.request";
    public static final String QUESTION_REQUEST = "question.request";
    public static final String REQUEST_RESOLVED = "request.resolved";
    public static final String TASK_COMPLETED = "task.completed";
    public static final String ERROR = "error";

    private Protocol() {
    }

    public static JSONObject envelope(String type, JSONObject payload) throws JSONException {
        JSONObject envelope = new JSONObject();
        envelope.put("v", VERSION);
        envelope.put("id", "cli_" + UUID.randomUUID());
        envelope.put("type", type);
        envelope.put("ts", System.currentTimeMillis());
        if (payload != null) {
            envelope.put("payload", payload);
        }
        return envelope;
    }

    public static JSONObject hello(String deviceId, String deviceName, String appVersion,
                                   long cursor) throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("protocol", VERSION);
        payload.put("deviceId", deviceId == null ? "" : deviceId);
        payload.put("deviceName", deviceName == null ? "" : deviceName);
        payload.put("appVersion", appVersion == null ? "" : appVersion);
        payload.put("cursor", cursor);
        return envelope(HELLO, payload);
    }

    public static JSONObject ack(long seq) throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("seq", seq);
        return envelope(ACK, payload);
    }

    public static JSONObject response(String requestId, String optionId) throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("requestId", requestId);
        payload.put("optionId", optionId);
        return envelope(REQUEST_RESPOND, payload);
    }

    public static WireEvent parseEvent(JSONObject raw) {
        if (raw == null) {
            return null;
        }
        if (raw.optInt("v", 0) != VERSION) {
            return null;
        }
        String type = raw.optString("type", "");
        if (type.isEmpty()) {
            return null;
        }
        JSONObject payload = raw.optJSONObject("payload");
        return new WireEvent(
                raw.optString("id", ""),
                raw.optLong("seq", 0L),
                raw.optLong("ts", System.currentTimeMillis()),
                type,
                payload);
    }

    public static AgentInfo parseAgent(JSONObject object) {
        if (object == null) {
            return null;
        }
        String id = object.optString("id", "");
        if (id.isEmpty()) {
            return null;
        }
        String name = object.optString("name", id);
        return new AgentInfo(
                id,
                name.isEmpty() ? id : name,
                object.optString("status", "idle"),
                object.optString("detail", ""),
                object.optString("task", ""),
                object.optLong("updatedAt", System.currentTimeMillis()));
    }

    public static AgentRequest parseRequest(JSONObject object, String fallbackKind) {
        if (object == null) {
            return null;
        }
        String requestId = object.optString("requestId", "");
        if (requestId.isEmpty()) {
            return null;
        }
        String kind = object.optString("kind", fallbackKind);
        if (kind == null || kind.isEmpty()) {
            kind = object.has("question") ? "question" : "permission";
        }
        String agentId = object.optString("agentId", "");
        String agentName = object.optString("agentName", agentId);
        String risk = object.optString("risk", "permission".equals(kind) ? "low" : "");

        List<AgentOption> options = new ArrayList<>();
        JSONArray optionArray = object.optJSONArray("options");
        if (optionArray != null) {
            for (int i = 0; i < optionArray.length(); i++) {
                JSONObject option = optionArray.optJSONObject(i);
                if (option == null) {
                    continue;
                }
                String optionId = option.optString("id", "");
                if (optionId.isEmpty()) {
                    continue;
                }
                options.add(new AgentOption(
                        optionId,
                        option.optString("label", optionId),
                        option.optString("style", "neutral")));
            }
        }

        return new AgentRequest(
                requestId,
                kind,
                agentId,
                agentName,
                object.optString("title", ""),
                object.optString("detail", ""),
                risk,
                object.optString("question", ""),
                object.optString("context", ""),
                object.optLong("createdAt", 0L),
                object.optLong("expiresAt", 0L),
                object.optBoolean("allowFreeText", false),
                options);
    }

    public static TaskResult parseTask(JSONObject object, long fallbackTime) {
        if (object == null) {
            return null;
        }
        String taskId = object.optString("taskId", object.optString("id", ""));
        if (taskId.isEmpty()) {
            return null;
        }
        return new TaskResult(
                taskId,
                object.optString("agentId", ""),
                object.optString("agentName", ""),
                object.optString("title", ""),
                object.optString("summary", ""),
                object.optString("outcome", "success"),
                object.optLong("completedAt", fallbackTime),
                object.optLong("durationMs", 0L));
    }

    public static List<AgentInfo> parseAgents(JSONArray array) {
        List<AgentInfo> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (int i = 0; i < array.length(); i++) {
            AgentInfo agent = parseAgent(array.optJSONObject(i));
            if (agent != null) {
                result.add(agent);
            }
        }
        return result;
    }

    public static List<AgentRequest> parseRequests(JSONArray array) {
        List<AgentRequest> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (int i = 0; i < array.length(); i++) {
            AgentRequest request = parseRequest(array.optJSONObject(i), null);
            if (request != null) {
                result.add(request);
            }
        }
        return result;
    }

    public static List<TaskResult> parseTasks(JSONArray array) {
        List<TaskResult> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (int i = 0; i < array.length(); i++) {
            TaskResult task = parseTask(array.optJSONObject(i), System.currentTimeMillis());
            if (task != null) {
                result.add(task);
            }
        }
        return result;
    }
}
