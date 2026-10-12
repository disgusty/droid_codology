"""A tiny language server for tests: answers initialize / completion / hover and reports a
diagnostic for every line containing "TODO". Speaks LSP (Content-Length framing) on stdio."""
import json
import sys

inp, out = sys.stdin.buffer, sys.stdout.buffer


def send(obj):
    data = json.dumps(obj).encode()
    out.write(b"Content-Length: %d\r\n\r\n" % len(data) + data)
    out.flush()


def diagnostics(uri, text):
    diags = []
    for i, line in enumerate(text.split("\n")):
        col = line.find("TODO")
        if col >= 0:
            diags.append({"range": {"start": {"line": i, "character": col}, "end": {"line": i, "character": col + 4}},
                          "severity": 2, "message": "Unfinished TODO", "source": "fake"})
    send({"jsonrpc": "2.0", "method": "textDocument/publishDiagnostics", "params": {"uri": uri, "diagnostics": diags}})


docs = {}
while True:
    length = None
    while True:
        line = inp.readline()
        if not line:
            sys.exit(0)
        line = line.strip()
        if not line:
            break
        if line.lower().startswith(b"content-length:"):
            length = int(line.split(b":")[1])
    msg = json.loads(inp.read(length))
    method, mid = msg.get("method"), msg.get("id")
    p = msg.get("params") or {}
    if method == "initialize":
        send({"jsonrpc": "2.0", "id": mid, "result": {"capabilities": {
            "textDocumentSync": 1, "completionProvider": {"triggerCharacters": ["."]}, "hoverProvider": True,
            "definitionProvider": True, "documentFormattingProvider": True}}})
    elif method == "textDocument/didOpen":
        d = p["textDocument"]
        docs[d["uri"]] = d["text"]
        diagnostics(d["uri"], d["text"])
    elif method == "textDocument/didChange":
        uri = p["textDocument"]["uri"]
        docs[uri] = p["contentChanges"][-1]["text"]
        diagnostics(uri, docs[uri])
    elif method == "textDocument/completion":
        send({"jsonrpc": "2.0", "id": mid, "result": {"isIncomplete": False, "items": [
            {"label": "fake_function", "kind": 3, "detail": "def fake_function()", "insertText": "fake_function()"},
            {"label": "FAKE_CONSTANT", "kind": 21}]}})
    elif method == "textDocument/hover":
        send({"jsonrpc": "2.0", "id": mid, "result": {"contents": {"kind": "markdown", "value": "**fake** hover"}}})
    elif method == "textDocument/definition":
        send({"jsonrpc": "2.0", "id": mid, "result": {"uri": p["textDocument"]["uri"],
                                                         "range": {"start": {"line": 0, "character": 0}, "end": {"line": 0, "character": 3}}}})
    elif method == "textDocument/formatting":
        uri = p["textDocument"]["uri"]
        text = docs.get(uri, "")
        lines = text.split("\n")
        send({"jsonrpc": "2.0", "id": mid, "result": [{"range": {"start": {"line": 0, "character": 0},
                                                                   "end": {"line": len(lines), "character": 0}},
                                                         "newText": "\n".join(l.rstrip() for l in lines)}]})
    elif method == "shutdown":
        send({"jsonrpc": "2.0", "id": mid, "result": None})
    elif method == "exit":
        sys.exit(0)
    elif mid is not None:
        send({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "not supported"}})
