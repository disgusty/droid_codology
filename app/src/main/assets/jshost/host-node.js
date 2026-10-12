/*
 * droid_codology extension host on Node.js (inside Alpine Linux): the same vscode API as the
 * WebView host (host.js, loaded here), with Node's own require, fs, child_process, net... so
 * extensions written for desktop VS Code can run.
 *
 * Channels (paths in the environment):
 *   DC_REQ_FIFO   JS -> Java, one JSON array per line: ["call", id, method, argsJson] (sync; the
 *                 answer comes on DC_REPLY_FIFO as [id, resultJson]) or ["async", reqId, method, argsJson]
 *   DC_REPLY_FIFO Java -> JS, answers to "call"
 *   stdin         Java -> JS, the host's message queue (one entry per line, as host.js's take())
 *   stdout/stderr extension output, shown in the extension log
 */
'use strict';
const fs = require('fs');
const path = require('path');
const Module = require('module');
const readline = require('readline');
const { StringDecoder } = require('string_decoder');

const reqFd = fs.openSync(process.env.DC_REQ_FIFO, 'w');
const replyFd = fs.openSync(process.env.DC_REPLY_FIFO, 'r');
const decoder = new StringDecoder('utf8');
let replyBuf = '';
let nextCall = 1;
const inbox = [];

function send(arr) {
    const line = Buffer.from(JSON.stringify(arr) + '\n', 'utf8');
    let off = 0;
    while (off < line.length) off += fs.writeSync(reqFd, line, off, line.length - off);
}

function readReplyLine() {
    const chunk = Buffer.alloc(65536);
    for (;;) {
        const nl = replyBuf.indexOf('\n');
        if (nl >= 0) {
            const line = replyBuf.slice(0, nl);
            replyBuf = replyBuf.slice(nl + 1);
            return line;
        }
        let n;
        try {
            n = fs.readSync(replyFd, chunk, 0, chunk.length, null);
        } catch (e) {
            if (e.code === 'EAGAIN' || e.code === 'EINTR') continue;
            throw e;
        }
        if (n <= 0) {
            // droid_codology went away
            process.exit(0);
        }
        replyBuf += decoder.write(chunk.subarray(0, n));
    }
}

// The bridge host.js expects (in the WebView it's Java's DC object).
global.DC = {
    call(method, argsJson) {
        const id = nextCall++;
        send(['call', id, method, argsJson]);
        for (;;) {
            const r = JSON.parse(readReplyLine());
            if (r[0] === id) return r[1];
        }
    },
    callAsync(reqId, method, argsJson) {
        send(['async', reqId, method, argsJson]);
    },
    take() {
        return inbox.length ? inbox.shift() : '';
    }
};

// host.js sends console output to the extension log and then calls the original method (the
// WebView's console); here the original would print it a second time through stdout.
for (const level of ['log', 'info', 'warn', 'error', 'debug', 'trace']) {
    console[level] = function () {};
}

/** Node's require for extension code; `vscode` is the extension's API object from host.js. */
const extensionsByPath = [];
const origLoad = Module._load;
Module._load = function (request, parent, isMain) {
    if (request === 'vscode') {
        const file = parent && parent.filename || '';
        let best = null;
        for (const e of extensionsByPath) {
            if (file === e.path || file.startsWith(e.path + '/')) {
                if (!best || e.path.length > best.path.length) best = e;
            }
        }
        const api = best && global.__dcExtApi ? global.__dcExtApi(best.id) : undefined;
        if (!api) throw new Error("Cannot find module 'vscode'");
        return api;
    }
    return origLoad.apply(this, arguments);
};

global.__dcNode = {
    /** host.js calls this instead of its own loader. */
    require(ext, fromDir, request) {
        if (!extensionsByPath.some((e) => e.id === ext.id)) {
            extensionsByPath.push({ id: ext.id, path: ext.path.replace(/\/+$/, '') });
        }
        const base = path.join(ext.path, fromDir || '', '__dc_require__.js');
        return Module.createRequire(base)(request);
    }
};

require(path.join(__dirname, 'host.js'));

const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
rl.on('line', (line) => {
    if (!line) return;
    inbox.push(line);
    if (global.__host && global.__host.pull) global.__host.pull();
});
rl.on('close', () => process.exit(0));

process.on('uncaughtException', (e) => {
    try {
        send(['call', nextCall++, 'log', JSON.stringify(['', 'error', 'uncaught: ' + (e && e.stack || e)])]);
        readReplyLine();
    } catch (ignored) {
        // the app is gone
    }
});
