#!/usr/bin/env node
'use strict';

/**
 * End-to-end probe for the mock gateway. Requires Node 22+ (built-in WebSocket).
 *
 *   node server.js        # terminal 1
 *   node test-client.js   # terminal 2
 */

const PORT = Number(process.env.PORT || 8787);
const TOKEN = process.env.TOKEN || 'dev-token';
const BASE = `http://127.0.0.1:${PORT}`;

let approvedRequestId = null;
let finished = false;

function timeout(ms) {
    return new Promise((_, reject) => setTimeout(() => reject(new Error('timeout')), ms));
}

async function main() {
    const ws = new WebSocket(`ws://127.0.0.1:${PORT}/agent?token=${encodeURIComponent(TOKEN)}`);
    ws.addEventListener('open', () => {
        console.log('[test] connected');
        ws.send(JSON.stringify({
            v: 1,
            id: 'test_hello',
            type: 'hello',
            ts: Date.now(),
            payload: {
                protocol: 1,
                deviceId: 'test-device',
                deviceName: 'Protocol Probe',
                appVersion: '0.0.1',
                cursor: 0
            }
        }));
        setTimeout(async () => {
            console.log('[test] triggering permission');
            await fetch(`${BASE}/demo/permission`, { method: 'POST' });
        }, 300);
    });

    ws.addEventListener('message', (event) => {
        const message = JSON.parse(event.data);
        if (['hello.ok', 'snapshot', 'permission.request', 'request.resolved', 'error'].includes(message.type)) {
            const id = message.payload && message.payload.requestId;
            console.log(`[test] <- ${message.type}${id ? ' ' + id : ''}`);
        }
        if (message.type === 'hello.ok' && message.payload.protocol !== 1) {
            fail('wrong protocol version');
        }
        if (message.type === 'snapshot') {
            if (!Array.isArray(message.payload.agents)) {
                fail('snapshot missing agents');
            }
            console.log(`[test] snapshot: ${message.payload.agents.length} agents, ${message.payload.pending.length} pending`);
        }
        if (message.type === 'permission.request' && !approvedRequestId) {
            approvedRequestId = message.payload.requestId;
            console.log(`[test] approving ${approvedRequestId}`);
            ws.send(JSON.stringify({
                v: 1,
                id: 'test_response',
                type: 'request.respond',
                ts: Date.now(),
                payload: {
                    requestId: approvedRequestId,
                    optionId: 'allow_once'
                }
            }));
        }
        if (message.type === 'request.resolved' && message.payload.requestId === approvedRequestId) {
            console.log('[test] resolved by', message.payload.by, message.payload.resolution);
            finished = true;
            ws.close();
            setTimeout(() => process.exit(0), 50);
        }
        if (message.type === 'error') {
            fail(`server error: ${message.payload.code} ${message.payload.message || ''}`);
        }
    });

    ws.addEventListener('error', (event) => {
        fail(`websocket error: ${event.message || 'unknown'}`);
    });

    await Promise.race([
        new Promise((resolve) => {
            const timer = setInterval(() => {
                if (finished) {
                    clearInterval(timer);
                    resolve();
                }
            }, 100);
        }),
        timeout(10000)
    ]).catch((error) => fail(error.message));
}

function fail(message) {
    if (finished) {
        return;
    }
    finished = true;
    console.error('[test] FAILED:', message);
    process.exit(1);
}

main();
