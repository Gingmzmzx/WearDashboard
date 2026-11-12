#!/usr/bin/env node
'use strict';

/**
 * Dependency-free development gateway for the Watch Agent protocol v1.
 *
 *   TOKEN=dev-token PORT=8787 node server.js
 *
 * Open the watch settings and point it at ws://<computer-lan-ip>:8787/agent
 * with the same token. Type commands in this terminal:
 *   p  high-risk permission request     l  low-risk permission request
 *   q  question with choices            t  successful task completion
 *   f  failed task completion           s  toggle agent status
 *   e  expire the oldest pending item   h  help
 *
 * HTTP controls (useful for scripted tests):
 *   GET  /health
 *   POST /demo/permission | /demo/permission-low | /demo/question
 *   POST /demo/task | /demo/task-failed | /demo/status | /demo/expire
 */

const http = require('http');
const crypto = require('crypto');
const readline = require('readline');

const PORT = Number(process.env.PORT || 8787);
const TOKEN = process.env.TOKEN || 'dev-token';
const HOST = process.env.HOST || '0.0.0.0';
const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

let sequence = 0;
let requestCounter = 0;
let taskCounter = 0;
let statusIndex = 0;

const clients = new Set();
const pending = new Map();
const resolved = new Map();
let recent = [];

const agents = new Map([
    ['claude-code', {
        id: 'claude-code',
        name: 'Claude Code',
        status: 'working',
        detail: 'Watching the repository',
        task: 'Refactor the auth middleware',
        updatedAt: Date.now()
    }],
    ['reviewer', {
        id: 'reviewer',
        name: 'Review Agent',
        status: 'idle',
        detail: 'Waiting for a diff',
        task: '',
        updatedAt: Date.now()
    }]
]);

function now() {
    return Date.now();
}

function newEnvelope(type, payload) {
    sequence += 1;
    return {
        v: 1,
        id: `evt_${sequence}`,
        seq: sequence,
        type,
        ts: now(),
        payload
    };
}

function send(ws, object) {
    if (ws && ws.open) {
        ws.send(JSON.stringify(object));
    }
}

function broadcast(type, payload) {
    const message = newEnvelope(type, payload);
    for (const ws of clients) {
        send(ws, message);
    }
    console.log(`[out] ${type}${payload && payload.requestId ? ' ' + payload.requestId : ''}`);
    return message;
}

function snapshotMessage() {
    return {
        v: 1,
        id: `snapshot_${now()}`,
        seq: sequence,
        type: 'snapshot',
        ts: now(),
        payload: {
            agents: Array.from(agents.values()),
            pending: Array.from(pending.values()),
            recent,
            serverTime: now()
        }
    };
}

function sendError(ws, code, message, requestId) {
    send(ws, {
        v: 1,
        id: `err_${now()}_${Math.floor(Math.random() * 1000)}`,
        type: 'error',
        ts: now(),
        payload: {
            code,
            message,
            ...(requestId ? { requestId } : {})
        }
    });
    console.log(`[err] ${code}${requestId ? ' ' + requestId : ''}`);
}

function newRequestId() {
    requestCounter += 1;
    return `req_${requestCounter}_${now().toString(36)}`;
}

function pushPermission(risk) {
    const requestId = newRequestId();
    const created = now();
    const request = {
        requestId,
        kind: 'permission',
        agentId: 'claude-code',
        agentName: 'Claude Code',
        title: risk === 'high' ? '运行构建清理命令？' : '读取项目配置文件？',
        detail: risk === 'high'
            ? 'rm -rf build && ./gradlew assembleDebug'
            : 'cat local.properties && cat .env.example',
        risk,
        createdAt: created,
        expiresAt: created + 120000,
        options: risk === 'high'
            ? [
                { id: 'allow_once', label: '允许一次', style: 'approve' },
                { id: 'deny', label: '拒绝', style: 'deny' }
            ]
            : [
                { id: 'allow_once', label: '允许一次', style: 'approve' },
                { id: 'allow_session', label: '本会话允许', style: 'approve' },
                { id: 'deny', label: '拒绝', style: 'deny' }
            ]
    };
    pending.set(requestId, request);
    broadcast('permission.request', request);
    return request;
}

function pushQuestion() {
    const requestId = newRequestId();
    const created = now();
    const request = {
        requestId,
        kind: 'question',
        agentId: 'claude-code',
        agentName: 'Claude Code',
        title: '构建缓存冲突，下一步怎么处理？',
        question: '构建缓存冲突，下一步怎么处理？',
        context: '两个 Gradle 任务同时写入了 build/cache。可以直接清理缓存后重试，或者保持现状等待人工检查。',
        createdAt: created,
        expiresAt: created + 300000,
        allowFreeText: false,
        options: [
            { id: 'retry_clean', label: '清理后重试', style: 'approve' },
            { id: 'inspect', label: '先人工检查', style: 'neutral' },
            { id: 'stop', label: '停止任务', style: 'deny' }
        ]
    };
    pending.set(requestId, request);
    broadcast('question.request', request);
    return request;
}

function pushTask(outcome) {
    taskCounter += 1;
    const task = {
        taskId: `task_${taskCounter}`,
        agentId: 'claude-code',
        agentName: 'Claude Code',
        title: outcome === 'success' ? '重构登录中间件' : '修复支付回调',
        summary: outcome === 'success'
            ? '已更新 4 个文件，全部测试通过'
            : '3 个测试失败：签名校验超时',
        outcome: outcome || 'success',
        completedAt: now(),
        durationMs: 184000
    };
    recent = [task, ...recent.filter((item) => item.taskId !== task.taskId)].slice(0, 10);
    broadcast('task.completed', task);
    return task;
}

function toggleAgentStatus() {
    const statuses = ['working', 'waiting', 'idle', 'error'];
    statusIndex = (statusIndex + 1) % statuses.length;
    const status = statuses[statusIndex];
    const agent = {
        ...agents.get('claude-code'),
        status,
        detail: status === 'waiting' ? 'Waiting for your decision' : 'Watching the repository',
        task: status === 'working' ? 'Refactor the auth middleware' : 'Repository scan',
        updatedAt: now()
    };
    agents.set(agent.id, agent);
    broadcast('agent.status', agent);
    return agent;
}

function expireOldest() {
    const first = pending.values().next();
    if (first.done) {
        console.log('[demo] no pending request to expire');
        return null;
    }
    const request = first.value;
    pending.delete(request.requestId);
    resolved.set(request.requestId, { optionId: null, resolution: 'expired' });
    const payload = {
        requestId: request.requestId,
        resolution: 'expired',
        by: 'server',
        resolvedAt: now()
    };
    broadcast('request.resolved', payload);
    return payload;
}

function handleResponse(ws, message) {
    const payload = message.payload || {};
    const requestId = payload.requestId;
    const optionId = payload.optionId;
    if (!requestId || !optionId) {
        sendError(ws, 'bad_request', 'requestId and optionId are required', requestId);
        return;
    }

    const previous = resolved.get(requestId);
    if (previous) {
        if (previous.optionId === optionId) {
            send(ws, newEnvelope('request.resolved', {
                requestId,
                optionId,
                resolution: previous.resolution,
                by: 'watch',
                resolvedAt: now()
            }));
        } else {
            sendError(ws, 'already_resolved', 'Request was already resolved', requestId);
        }
        return;
    }

    const request = pending.get(requestId);
    if (!request) {
        sendError(ws, 'request_not_found', 'Unknown or expired request', requestId);
        return;
    }
    if (request.expiresAt && now() >= request.expiresAt) {
        pending.delete(requestId);
        resolved.set(requestId, { optionId: null, resolution: 'expired' });
        broadcast('request.resolved', {
            requestId,
            resolution: 'expired',
            by: 'server',
            resolvedAt: now()
        });
        return;
    }
    if (!request.options.some((option) => option.id === optionId)) {
        sendError(ws, 'invalid_option', `Unknown option: ${optionId}`, requestId);
        return;
    }

    pending.delete(requestId);
    resolved.set(requestId, { optionId, resolution: 'answered' });
    console.log(`[decision] ${requestId} -> ${optionId}`);
    broadcast('request.resolved', {
        requestId,
        optionId,
        resolution: 'answered',
        by: 'watch',
        resolvedAt: now()
    });
}

function handleClientMessage(ws, text) {
    let message;
    try {
        message = JSON.parse(text);
    } catch (error) {
        sendError(ws, 'bad_json', 'Frame is not valid JSON');
        return;
    }
    if (!message || message.v !== 1) {
        sendError(ws, 'bad_version', 'Only protocol v1 is supported');
        return;
    }

    switch (message.type) {
        case 'hello': {
            const payload = message.payload || {};
            console.log(`[hello] device=${payload.deviceName || '?'} (${payload.deviceId || '?'}) cursor=${payload.cursor || 0}`);
            send(ws, newEnvelope('hello.ok', {
                protocol: 1,
                serverName: 'mock-agent-gateway',
                sessionId: `session_${now().toString(36)}`,
                heartbeatSec: 25
            }));
            send(ws, snapshotMessage());
            break;
        }
        case 'ack':
            console.log(`[ack] cursor=${(message.payload || {}).seq}`);
            break;
        case 'request.respond':
            handleResponse(ws, message);
            break;
        default:
            sendError(ws, 'unknown_type', `Unsupported client type: ${message.type}`);
            break;
    }
}

// ---- Minimal RFC6455 server -------------------------------------------------

function encodeFrame(opcode, payload) {
    const length = payload.length;
    let header;
    if (length < 126) {
        header = Buffer.alloc(2);
        header[0] = 0x80 | opcode;
        header[1] = length;
    } else if (length < 65536) {
        header = Buffer.alloc(4);
        header[0] = 0x80 | opcode;
        header[1] = 126;
        header.writeUInt16BE(length, 2);
    } else {
        header = Buffer.alloc(10);
        header[0] = 0x80 | opcode;
        header[1] = 127;
        header.writeBigUInt64BE(BigInt(length), 2);
    }
    return Buffer.concat([header, payload]);
}

function attachWebSocket(socket) {
    const state = {
        socket,
        buffer: Buffer.alloc(0),
        fragments: [],
        fragmentOpcode: 0,
        closed: false,
        lastSeen: Date.now()
    };

    const ws = {
        get open() {
            return !state.closed && !socket.destroyed;
        },
        send(text) {
            if (!ws.open) {
                return;
            }
            socket.write(encodeFrame(0x1, Buffer.from(text, 'utf8')));
        },
        close(code, reason) {
            if (!ws.open) {
                return;
            }
            const payload = Buffer.alloc(2 + Buffer.byteLength(reason || ''));
            payload.writeUInt16BE(code || 1000, 0);
            payload.write(reason || '', 2);
            socket.write(encodeFrame(0x8, payload));
            state.closed = true;
            socket.end();
        },
        onMessage: null,
        onClose: null
    };

    function closeWith(code) {
        if (state.closed) {
            return;
        }
        state.closed = true;
        socket.write(encodeFrame(0x8, Buffer.from([code >> 8, code & 0xff])));
        socket.end();
    }

    function processBuffer() {
        while (state.buffer.length >= 2) {
            const first = state.buffer[0];
            const second = state.buffer[1];
            const fin = (first & 0x80) !== 0;
            const opcode = first & 0x0f;
            const masked = (second & 0x80) !== 0;
            let length = second & 0x7f;
            let offset = 2;

            if (!masked) {
                closeWith(1002);
                return;
            }
            if (length === 126) {
                if (state.buffer.length < 4) {
                    return;
                }
                length = state.buffer.readUInt16BE(2);
                offset = 4;
            } else if (length === 127) {
                if (state.buffer.length < 10) {
                    return;
                }
                const big = state.buffer.readBigUInt64BE(2);
                if (big > 1048576n) {
                    closeWith(1009);
                    return;
                }
                length = Number(big);
                offset = 10;
            }
            if (state.buffer.length < offset + 4 + length) {
                return;
            }
            const mask = state.buffer.subarray(offset, offset + 4);
            offset += 4;
            const payload = Buffer.alloc(length);
            for (let i = 0; i < length; i += 1) {
                payload[i] = state.buffer[offset + i] ^ mask[i % 4];
            }
            state.buffer = state.buffer.subarray(offset + length);
            state.lastSeen = Date.now();

            if (opcode === 0x8) {
                closeWith(1000);
                return;
            }
            if (opcode === 0x9) {
                socket.write(encodeFrame(0xA, payload));
                continue;
            }
            if (opcode === 0xA) {
                continue;
            }
            if (opcode === 0x1) {
                if (!fin) {
                    state.fragmentOpcode = opcode;
                    state.fragments = [payload];
                } else if (ws.onMessage) {
                    ws.onMessage(payload.toString('utf8'));
                }
                continue;
            }
            if (opcode === 0x0 && state.fragmentOpcode === 0x1) {
                state.fragments.push(payload);
                if (fin) {
                    const full = Buffer.concat(state.fragments);
                    state.fragments = [];
                    state.fragmentOpcode = 0;
                    if (ws.onMessage) {
                        ws.onMessage(full.toString('utf8'));
                    }
                }
                continue;
            }
            closeWith(1003);
            return;
        }
    }

    socket.on('data', (chunk) => {
        state.buffer = Buffer.concat([state.buffer, chunk]);
        processBuffer();
    });
    socket.on('error', () => {
        // handled by close below
    });
    socket.on('close', () => {
        if (state.closed && !ws.open) {
            // already closed
        }
        state.closed = true;
        if (ws.onClose) {
            ws.onClose();
        }
    });

    return ws;
}

const server = http.createServer((req, res) => {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    if (req.method === 'GET' && url.pathname === '/health') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            ok: true,
            clients: clients.size,
            pending: pending.size,
            sequence
        }));
        return;
    }
    if (req.method === 'POST' && url.pathname.startsWith('/demo/')) {
        const action = url.pathname.slice('/demo/'.length);
        let result;
        switch (action) {
            case 'permission':
                result = pushPermission('high');
                break;
            case 'permission-low':
                result = pushPermission('low');
                break;
            case 'question':
                result = pushQuestion();
                break;
            case 'task':
                result = pushTask('success');
                break;
            case 'task-failed':
                result = pushTask('failed');
                break;
            case 'status':
                result = toggleAgentStatus();
                break;
            case 'expire':
                result = expireOldest();
                break;
            default:
                res.writeHead(404, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: 'unknown demo action' }));
                return;
        }
        res.writeHead(202, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, result }));
        return;
    }
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end([
        'Watch Agent mock gateway',
        `WebSocket: ws://<lan-ip>:${PORT}/agent`,
        `Token: Bearer ${TOKEN}`,
        '',
        'POST /demo/permission | /demo/permission-low | /demo/question',
        'POST /demo/task | /demo/task-failed | /demo/status | /demo/expire',
        ''
    ].join('\n'));
});

server.on('upgrade', (req, socket) => {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    if (url.pathname !== '/agent') {
        socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
        socket.destroy();
        return;
    }

    const authorization = req.headers.authorization || '';
    const queryToken = url.searchParams.get('token');
    // The query fallback exists only so browsers/Node's built-in WebSocket can
    // be used for local testing. Real clients should send the header.
    if (authorization !== `Bearer ${TOKEN}` && queryToken !== TOKEN) {
        socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
        socket.destroy();
        return;
    }
    if ((req.headers.upgrade || '').toLowerCase() !== 'websocket') {
        socket.write('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
        socket.destroy();
        return;
    }

    const accept = crypto.createHash('sha1')
        .update(req.headers['sec-websocket-key'] + WS_GUID)
        .digest('base64');
    socket.write([
        'HTTP/1.1 101 Switching Protocols',
        'Upgrade: websocket',
        'Connection: Upgrade',
        `Sec-WebSocket-Accept: ${accept}`,
        '',
        ''
    ].join('\r\n'));

    const ws = attachWebSocket(socket);
    ws.onMessage = (text) => handleClientMessage(ws, text);
    ws.onClose = () => {
        clients.delete(ws);
        console.log(`[ws] client disconnected (${clients.size} online)`);
    };
    clients.add(ws);
    console.log(`[ws] client connected (${clients.size} online)`);
});

setInterval(() => {
    const nowMs = Date.now();
    for (const [requestId, request] of pending) {
        if (request.expiresAt && nowMs >= request.expiresAt) {
            pending.delete(requestId);
            resolved.set(requestId, { optionId: null, resolution: 'expired' });
            broadcast('request.resolved', {
                requestId,
                resolution: 'expired',
                by: 'server',
                resolvedAt: nowMs
            });
        }
    }
    for (const [requestId, item] of resolved) {
        if (item.expiresAt && nowMs > item.expiresAt) {
            resolved.delete(requestId);
        } else if (!item.expiresAt) {
            item.expiresAt = nowMs + 10 * 60 * 1000;
        }
    }
}, 5000).unref();

setInterval(() => {
    for (const ws of clients) {
        ws.ping();
    }
}, 30000).unref();

server.listen(PORT, HOST, () => {
    console.log('Watch Agent mock gateway');
    console.log(`  ws://<lan-ip>:${PORT}/agent`);
    console.log(`  token: ${TOKEN}`);
    console.log('Commands: p high-risk permission | l low-risk permission | q question');
    console.log('          t success | f failure | s toggle status | e expire | h help');
});

const rl = readline.createInterface({ input: process.stdin, terminal: false });
rl.on('line', (line) => {
    const command = line.trim();
    switch (command) {
        case 'p':
            pushPermission('high');
            break;
        case 'l':
            pushPermission('low');
            break;
        case 'q':
            pushQuestion();
            break;
        case 't':
            pushTask('success');
            break;
        case 'f':
            pushTask('failed');
            break;
        case 's':
            toggleAgentStatus();
            break;
        case 'e':
            expireOldest();
            break;
        case 'h':
        case '':
            console.log('p high risk | l low risk | q question | t task | f failed | s status | e expire');
            break;
        default:
            console.log(`Unknown command: ${command} (h for help)`);
            break;
    }
});
