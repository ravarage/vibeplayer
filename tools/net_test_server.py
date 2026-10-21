#!/usr/bin/env python3
"""Serves a folder over HTTP with Range support (seeking), folder listings and optional Basic auth, for the
P13 network device checks. python3 tools/net_test_server.py DIR [PORT] [--auth user:pass]"""
import base64
import http.server
import os
import re
import sys


def make_handler(root, auth):
    class Handler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *a, **kw):
            super().__init__(*a, directory=root, **kw)

        def log_message(self, fmt, *args):
            sys.stderr.write("net-test %s\n" % (fmt % args))

        def _authorized(self):
            if not auth:
                return True
            want = "Basic " + base64.b64encode(auth.encode()).decode()
            if self.headers.get("Authorization") == want:
                return True
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="vibeplayer"')
            self.send_header("Content-Length", "0")
            self.end_headers()
            return False

        def do_HEAD(self):
            if self._authorized():
                super().do_HEAD()

        def do_GET(self):
            if not self._authorized():
                return
            path = self.translate_path(self.path)
            rng = self.headers.get("Range")
            if not rng or not os.path.isfile(path):
                return super().do_GET()
            m = re.match(r"bytes=(\d*)-(\d*)", rng)
            size = os.path.getsize(path)
            if not m:
                return super().do_GET()
            start = int(m.group(1)) if m.group(1) else max(0, size - int(m.group(2) or 0))
            end = int(m.group(2)) if m.group(1) and m.group(2) else size - 1
            if start >= size:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%d" % size)
                self.end_headers()
                return
            end = min(end, size - 1)
            self.send_response(206)
            self.send_header("Content-Type", self.guess_type(path))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
            self.send_header("Content-Length", str(end - start + 1))
            self.end_headers()
            with open(path, "rb") as f:
                f.seek(start)
                left = end - start + 1
                while left > 0:
                    chunk = f.read(min(256 * 1024, left))
                    if not chunk:
                        break
                    try:
                        self.wfile.write(chunk)
                    except (BrokenPipeError, ConnectionResetError):
                        return
                    left -= len(chunk)

        def end_headers(self):
            if not self.headers.get("Range"):
                self.send_header("Accept-Ranges", "bytes")
            super().end_headers()

    return Handler


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    auth = None
    if "--auth" in sys.argv:
        auth = sys.argv[sys.argv.index("--auth") + 1]
        args = [a for a in args if a != auth]
    root = args[0] if args else "."
    port = int(args[1]) if len(args) > 1 else 8765
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", port), make_handler(os.path.abspath(root), auth))
    print("serving %s on http://127.0.0.1:%d" % (root, port), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
