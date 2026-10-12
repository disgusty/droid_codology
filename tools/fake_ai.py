"""A stand-in OpenAI-compatible model for manual testing: streams a canned answer.

    python tools/fake_ai.py 9090      -> http://127.0.0.1:9090/v1/chat/completions
Answers depend on the request: inline-edit requests (system prompt "code editing engine")
get bare code back, everything else a short Markdown answer with a code block.
"""
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)))
        msgs = body.get("messages", [])
        system = next((m["content"] for m in msgs if m["role"] == "system"), "")
        last = msgs[-1]["content"] if msgs else ""
        if "autonomous coding agent" in system:
            # A scripted agent session: read, edit, run a command, then summarize.
            results = sum(1 for m in msgs if m["role"] == "user" and m["content"].startswith("<tool_result"))
            if results == 0:
                answer = ("I'll look at the README first.\n<tool_call>\n"
                          '{"tool": "read_file", "args": {"path": "README.md"}}\n</tool_call>')
            elif results == 1:
                answer = ("Now I'll update the title.\n<tool_call>\n"
                          '{"tool": "edit_file", "args": {"path": "README.md", "old_text": "# Demo project", '
                          '"new_text": "# Demo project (edited by the agent)"}}\n</tool_call>')
            elif results == 2:
                answer = ('Let me check the files.\n<tool_call>\n{"tool": "run_command", "args": {"command": "ls -1"}}\n</tool_call>')
            else:
                answer = "Done: I renamed the README title and listed the project files."
        elif "code editing engine" in system:
            answer = "// edited by fake model\nint answer() {\n    return 42;\n}"
        else:
            q = last.split("```")[-1].strip()[:80]
            answer = ("Here is what I found about **%s**:\n\n- It works.\n- It could be simpler.\n\n"
                      "```java\nint answer() {\n    return 42;\n}\n```\n\nContext received: %d characters." % (q, len(last)))
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Connection", "close")
        self.end_headers()
        for i in range(0, len(answer), 6):
            chunk = {"choices": [{"delta": {"content": answer[i:i + 6]}}]}
            self.wfile.write(("data: " + json.dumps(chunk) + "\n\n").encode())
            self.wfile.flush()
            time.sleep(0.03)
        self.wfile.write(b'data: {"choices":[],"usage":{"total_tokens":42}}\n\ndata: [DONE]\n\n')
        self.close_connection = True


ThreadingHTTPServer(("0.0.0.0", int(sys.argv[1]) if len(sys.argv) > 1 else 9090), H).serve_forever()
