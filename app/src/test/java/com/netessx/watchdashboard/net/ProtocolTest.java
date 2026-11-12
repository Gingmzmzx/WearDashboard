package com.netessx.watchdashboard.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.netessx.watchdashboard.data.AgentInfo;
import com.netessx.watchdashboard.data.AgentRequest;
import com.netessx.watchdashboard.data.TaskResult;
import com.netessx.watchdashboard.data.WireEvent;

import org.json.JSONObject;
import org.junit.Test;

public class ProtocolTest {

    @Test
    public void parsesPermissionRequestAndOptions() {
        String json = "{"
                + "\"v\":1,\"id\":\"evt_125\",\"seq\":125,\"type\":\"permission.request\","
                + "\"ts\":1760000000000,\"payload\":{"
                + "\"requestId\":\"req_001\",\"kind\":\"permission\",\"agentId\":\"claude-code\","
                + "\"agentName\":\"Claude Code\",\"title\":\"Run build command?\","
                + "\"detail\":\"rm -rf build && ./gradlew assembleDebug\",\"risk\":\"high\","
                + "\"createdAt\":1760000000000,\"expiresAt\":1760000120000,"
                + "\"options\":[{\"id\":\"allow_once\",\"label\":\"Allow once\",\"style\":\"approve\"},"
                + "{\"id\":\"deny\",\"label\":\"Deny\",\"style\":\"deny\"}]}}";
        try {
            WireEvent event = Protocol.parseEvent(new JSONObject(json));
            assertNotNull(event);
            assertEquals(125, event.seq);
            assertEquals(Protocol.PERMISSION_REQUEST, event.type);

            AgentRequest request = Protocol.parseRequest(event.payload, "permission");
            assertNotNull(request);
            assertEquals("req_001", request.requestId);
            assertTrue(request.isPermission());
            assertTrue(request.isHighRisk());
            assertEquals(2, request.options.size());
            assertTrue(request.options.get(0).isApprove());
            assertTrue(request.options.get(1).isDeny());
            assertEquals("rm -rf build && ./gradlew assembleDebug", request.detail);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void parsesQuestionAndFallbackKind() {
        try {
            JSONObject payload = new JSONObject("{"
                    + "\"requestId\":\"req_002\",\"kind\":\"question\","
                    + "\"question\":\"Which path?\",\"context\":\"Two files conflict.\","
                    + "\"options\":[{\"id\":\"a\",\"label\":\"A\"}]}");
            AgentRequest question = Protocol.parseRequest(payload, null);
            assertNotNull(question);
            assertFalse(question.isPermission());
            assertEquals("Which path?", question.displayTitle());
            assertEquals("Two files conflict.", question.displayBody());

            AgentRequest fallback = Protocol.parseRequest(
                    new JSONObject("{\"requestId\":\"req_003\"}"), "permission");
            assertNotNull(fallback);
            assertTrue(fallback.isPermission());
            assertEquals(2, fallback.effectiveOptions().size());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void parsesSnapshotArrays() {
        try {
            JSONObject snapshot = new JSONObject("{"
                    + "\"agents\":[{\"id\":\"a1\",\"name\":\"Agent One\",\"status\":\"working\"}],"
                    + "\"pending\":[{\"requestId\":\"req_001\",\"kind\":\"permission\",\"risk\":\"high\"}],"
                    + "\"recent\":[{\"taskId\":\"task_1\",\"title\":\"Build\",\"outcome\":\"success\","
                    + "\"completedAt\":1760000000000}]}");
            assertEquals(1, Protocol.parseAgents(snapshot.optJSONArray("agents")).size());
            assertEquals(1, Protocol.parseRequests(snapshot.optJSONArray("pending")).size());
            assertEquals(1, Protocol.parseTasks(snapshot.optJSONArray("recent")).size());

            AgentInfo agent = Protocol.parseAgent(
                    snapshot.optJSONArray("agents").optJSONObject(0));
            assertNotNull(agent);
            assertEquals("a1", agent.id);
            assertEquals("Agent One", agent.name);

            TaskResult task = Protocol.parseTask(
                    snapshot.optJSONArray("recent").optJSONObject(0), 0L);
            assertNotNull(task);
            assertTrue(task.isSuccess());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void buildsClientEnvelopesAndRejectsWrongVersion() {
        try {
            JSONObject hello = Protocol.hello("dev", "Watch", "1.0", 125);
            assertEquals(1, hello.getInt("v"));
            assertEquals("hello", hello.getString("type"));
            assertEquals(125, hello.getJSONObject("payload").getLong("cursor"));

            JSONObject response = Protocol.response("req_001", "allow_once");
            assertEquals("request.respond", response.getString("type"));
            assertEquals("req_001",
                    response.getJSONObject("payload").getString("requestId"));

            assertNull(Protocol.parseEvent(new JSONObject("{\"v\":2,\"type\":\"snapshot\"}")));
            assertNotNull(Protocol.parseEvent(new JSONObject("{\"v\":1,\"type\":\"snapshot\"}")));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
