#!/usr/bin/env python3
"""Tiny diagnostic log collector for shareit-bridge debug builds.

The debug APK POSTs its own captured logcat (own-pid only) here so we can see
what went wrong on the user's real phones. No auth, no secrets — purely the
app's own runtime logs. Run behind a cloudflared quick tunnel for a public URL.

  POST /log   body = raw log text   headers: X-Device, X-Session, X-Engine
              -> appended to logs/<device>__<engine>.log and echoed to stdout
  GET  /      -> plaintext status (live file sizes)
"""
import datetime
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "logs")
os.makedirs(LOG_DIR, exist_ok=True)
PORT = 8770

_safe = re.compile(r"[^A-Za-z0-9._-]+")


def _slug(s, default):
    s = (s or "").strip()
    if not s:
        return default
    return _safe.sub("_", s)[:60]


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body=b"ok"):
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except Exception:
            pass

    def do_GET(self):
        lines = ["shareit-bridge diag collector", ""]
        for f in sorted(os.listdir(LOG_DIR)):
            p = os.path.join(LOG_DIR, f)
            lines.append(f"{f}\t{os.path.getsize(p)} bytes")
        self._send(200, ("\n".join(lines) + "\n").encode())

    def do_POST(self):
        if self.path.rstrip("/") not in ("/log", ""):
            self._send(404, b"not found")
            return
        n = int(self.headers.get("Content-Length", "0") or "0")
        data = self.rfile.read(n) if n else b""
        device = _slug(self.headers.get("X-Device"), "unknown")
        engine = _slug(self.headers.get("X-Engine"), "engine")
        session = _slug(self.headers.get("X-Session"), "sess")
        fname = f"{device}__{engine}.log"
        ts = datetime.datetime.now().isoformat(timespec="seconds")
        text = data.decode("utf-8", "replace")
        with open(os.path.join(LOG_DIR, fname), "a", encoding="utf-8") as fh:
            fh.write(f"\n===== POST {ts} session={session} bytes={len(data)} =====\n")
            fh.write(text)
            if not text.endswith("\n"):
                fh.write("\n")
        print(f"[{ts}] {device}/{engine} session={session} +{len(data)}B", flush=True)
        self._send(200, b"ok")

    def log_message(self, *a):
        pass  # quiet default access logging; we print our own line


if __name__ == "__main__":
    print(f"collector listening on 127.0.0.1:{PORT}, logs -> {LOG_DIR}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
