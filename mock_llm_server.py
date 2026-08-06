#!/usr/bin/env python3
"""Mock LLM API 服务器：模拟 Anthropic /v1/messages 和 Gemini streamGenerateContent 的 SSE 响应，
用于验证 App 多协议解析。"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json, sys

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length else b""
        print(f"POST {self.path}", file=sys.stderr)
        print(f"  match: messages={self.path.startswith('/v1/messages')} gemini={'streamGenerateContent' in self.path}", file=sys.stderr)
        print(f"  body: {body[:400]}", file=sys.stderr)

        if self.path.startswith("/v1/messages"):
            # Anthropic SSE（中文分两段，验证逐字累积）
            resp = (
                'event: content_block_start\ndata: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}\n\n'
                'event: content_block_delta\ndata: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好，这是"}}\n\n'
                'event: content_block_delta\ndata: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Anthropic协议的翻译结果。"}}\n\n'
                'event: content_block_stop\ndata: {"type":"content_block_stop","index":0}\n\n'
                'event: message_stop\ndata: {"type":"message_stop"}\n\n'
            ).encode("utf-8")
        elif "streamGenerateContent" in self.path:
            print(f"  -> GEMINI BRANCH path={self.path!r}", file=sys.stderr)
            # Gemini SSE（日文分两段）
            resp = (
                'data: {"candidates":[{"content":{"role":"model","parts":[{"text":"これはGeminiプロトコル"}]}}]}\n\n'
                'data: {"candidates":[{"content":{"role":"model","parts":[{"text":"の翻訳結果です。"}]}}]}\n\n'
                'data: {"candidates":[]}\n\n'
            ).encode("utf-8")
        else:
            print("  -> NOT FOUND BRANCH", file=sys.stderr)
            resp = b'{"error":"not found"}'

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)
        self.wfile.flush()
        self.close_connection = True

    def log_message(self, *args):
        pass

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8898
    print(f"mock llm server on :{port}", file=sys.stderr)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
