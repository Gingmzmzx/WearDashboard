package com.netessx.watchdashboard.data;

import org.json.JSONObject;

/** Parsed server envelope, before its payload is interpreted by AgentStore. */
public final class WireEvent {

    public final String id;
    public final long seq;
    public final long ts;
    public final String type;
    public final JSONObject payload;

    public WireEvent(String id, long seq, long ts, String type, JSONObject payload) {
        this.id = id == null ? "" : id;
        this.seq = seq;
        this.ts = ts;
        this.type = type == null ? "" : type;
        this.payload = payload == null ? new JSONObject() : payload;
    }
}
