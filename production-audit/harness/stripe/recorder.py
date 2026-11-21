from __future__ import annotations

import json
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

REDACTED_HEADERS = ("authorization", "cookie", "set-cookie", "x-api-key", "proxy-authorization")
MAX_BODY_BYTES = 2_000_000
DROPPED_HEADERS = (
    "host",
    "content-length",
    "connection",
    "transfer-encoding",
    "accept-encoding",
    "expect",
)


def redact(headers: dict[str, str]) -> dict[str, str]:
    return {
        name: ("[redacted]" if name.lower() in REDACTED_HEADERS else value)
        for name, value in headers.items()
    }


class RecorderState:

    def __init__(self, upstream: str) -> None:
        self.upstream = upstream.rstrip("/")
        self.records: list[dict[str, Any]] = []
        self.lock = threading.Lock()

    def append(self, entry: dict[str, Any]) -> None:
        with self.lock:
            self.records.append(entry)

    def since(self, marker: int) -> list[dict[str, Any]]:
        with self.lock:
            return list(self.records[marker:])

    def marker(self) -> int:
        with self.lock:
            return len(self.records)


def _read_body(handler: BaseHTTPRequestHandler) -> bytes:
    length = int(handler.headers.get("Content-Length") or 0)
    if length <= 0:
        return b""
    return handler.rfile.read(min(length, MAX_BODY_BYTES))


def _forward(state: RecorderState, method: str, path: str, headers: dict[str, str], body: bytes) -> tuple[int, dict[str, str], bytes]:
    url = state.upstream + path
    request = urllib.request.Request(url, data=body if body else None, method=method)
    for name, value in headers.items():
        if name.lower() in DROPPED_HEADERS:
            continue
        request.add_header(name, value)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read()
            return response.status, {name.lower(): value for name, value in response.headers.items()}, raw
    except urllib.error.HTTPError as error:
        raw = error.read()
        return error.code, {name.lower(): value for name, value in error.headers.items()}, raw


def build_handler(state: RecorderState):

    class Handler(BaseHTTPRequestHandler):

        protocol_version = "HTTP/1.1"
        server_version = "stripe-wire-recorder"

        def log_message(self, format: str, *args: Any) -> None:
            return

        def _handle(self, method: str) -> None:
            body = _read_body(self)
            request_headers = {name: value for name, value in self.headers.items()}
            status, response_headers, raw = _forward(state, method, self.path, request_headers, body)
            state.append(
                {
                    "method": method,
                    "path": self.path,
                    "request_headers": redact(request_headers),
                    "request_body": body.decode("utf-8", errors="replace"),
                    "status": status,
                    "response_headers": redact(response_headers),
                    "response_body": raw.decode("utf-8", errors="replace"),
                }
            )
            self.send_response(status)
            for name, value in response_headers.items():
                if name in ("content-length", "transfer-encoding", "connection"):
                    continue
                self.send_header(name, value)
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            if method != "HEAD":
                self.wfile.write(raw)

        def do_GET(self) -> None:
            self._handle("GET")

        def do_POST(self) -> None:
            self._handle("POST")

        def do_DELETE(self) -> None:
            self._handle("DELETE")

    return Handler


class Recorder:

    def __init__(self, upstream: str, port: int, host: str = "0.0.0.0") -> None:
        self.state = RecorderState(upstream)
        self.server = ThreadingHTTPServer((host, port), build_handler(self.state))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def start(self) -> None:
        self.thread.start()

    def stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()

    def marker(self) -> int:
        return self.state.marker()

    def since(self, marker: int) -> list[dict[str, Any]]:
        return self.state.since(marker)

    def __enter__(self) -> "Recorder":
        self.start()
        return self

    def __exit__(self, *args: Any) -> None:
        self.stop()
