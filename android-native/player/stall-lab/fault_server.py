#!/usr/bin/env python3
"""A media host that misbehaves on request, for StallLabDeviceTest.

Serves the files of one directory with HTTP/1.1 range support, by name. What goes
wrong is chosen per
request through the query string, so the device test states the failure in the
URL it plays and the server holds no scenario state beyond "did it fire yet":

  blackhole_at=N     the connection crossing byte N stops sending, socket open
  trickle_at=N       the connection crossing byte N drops to trickle_bps
  trickle_bps=N      rate for trickle_at, bytes per second
  global_bps=N       every connection is limited to this, always
  rst_at=N           the connection crossing byte N is reset
  corrupt_at=N,M     bytes [N, N+corrupt_len) are served as garbage, every time
  corrupt_len=N
  corrupt_once=1     only for the connection that first crosses the mark: a host's one
                     bad answer, not a bad file
  flaky_every=N      every connection, not just one, goes bad after N bytes:
  flaky_mode=X       "silent" stops sending, "slow" falls to trickle_bps, "reset" cuts
  max_conns=N        beyond N concurrent connections, answer 503
  deny_at=N          the connection crossing byte N is cut, and every request
                     from then on is answered 403, as an expired link is
  deny_mode=X        answers those requests with 503, or with "html": a 200 carrying a
                     short error page, as some hosts answer for a link they dropped
  truncate_at=N      the file ends at byte N, and asking past that is answered 416
  no_range=1         ignores Range, and always sends the file from its first byte
  cold_s=S           no answer at all until S seconds after the first request, as a
                     debrid link preparing its file upstream behaves
  outage_after=S     S seconds after the first request, the whole host goes
  outage_secs=S      quiet for this long: no bytes on any connection, old or new
  run=ID             keys the one-shot faults; a new ID arms them again

Faults that fire "once" model a single bad connection: the next request gets a
healthy one, which is what a viewer's skip button reaches on a real CDN.
"""
import argparse
import os
import random
import socket
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

CHUNK = 64 * 1024

first_seen = {}
denied = set()
fired = set()
fired_lock = threading.Lock()
active = {}
active_lock = threading.Lock()


def outage_remaining(run, after, secs):
    """Seconds left of a host-wide outage, zero when none is under way."""
    if after is None or secs is None:
        return 0
    began = first_seen.setdefault(run, time.monotonic())
    elapsed = time.monotonic() - began
    return max(0, after + secs - elapsed) if elapsed >= after else 0


def fire_once(run, fault):
    with fired_lock:
        key = (run, fault)
        if key in fired:
            return False
        fired.add(key)
        return True


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    directory = ""

    def log_message(self, *args):
        pass

    def say(self, message):
        print(f"{time.strftime('%H:%M:%S')} #{self.conn_id} {message}", flush=True)

    def do_HEAD(self):
        self.serve(head=True)

    def do_GET(self):
        self.serve(head=False)

    def serve(self, head):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        num = lambda name: int(q[name]) if name in q else None
        run = q.get("run", "default")
        self.conn_id = f"{threading.get_ident() % 10000:04d}"

        self.path_to_file = os.path.join(self.directory, os.path.basename(url.path))
        if not os.path.isfile(self.path_to_file):
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        size = os.path.getsize(self.path_to_file)
        if "truncate_at" in q:
            size = min(size, num("truncate_at"))
        start, end, partial = 0, size - 1, False
        header = self.headers.get("Range")
        if header and header.startswith("bytes=") and "no_range" not in q:
            lo, _, hi = header[6:].partition("-")
            start = int(lo) if lo else 0
            end = min(int(hi), size - 1) if hi else size - 1
            partial = True
        if start >= size:
            self.say(f"416 range={start}- size={size}")
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        cold_s = num("cold_s")
        if cold_s is not None:
            began = first_seen.setdefault(run, time.monotonic())
            remaining = cold_s - (time.monotonic() - began)
            if remaining > 0:
                self.say(f"cold for {remaining:.0f}s more")
                time.sleep(remaining)

        if run in denied:
            mode = q.get("deny_mode", "403")
            self.say(f"denied ({mode}) range={start}-")
            body = b"<html><body>This link is no longer available</body></html>" if mode == "html" else b""
            self.send_response(200 if mode == "html" else int(mode))
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if not head:
                self.wfile.write(body)
            return

        limit = num("max_conns")
        with active_lock:
            count = active.get(run, 0)
            refused = limit is not None and count >= limit
            if not refused:
                active[run] = count + 1
        if refused:
            self.say(f"503 range={start}- concurrent={count}")
            self.send_response(503)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        try:
            self.send_response(206 if partial else 200)
            self.send_header("Content-Type", "video/x-matroska")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1))
            if partial:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            if head:
                return
            self.say(f"open range={start}-{end}")
            sent = self.pump(start, end, q, num, run)
            self.say(f"done range={start}- sent={sent}")
        except (BrokenPipeError, ConnectionResetError, socket.timeout) as e:
            self.say(f"client left range={start}- ({type(e).__name__})")
            self.close_connection = True
        finally:
            with active_lock:
                active[run] -= 1

    def pump(self, start, end, q, num, run):
        blackhole_at = num("blackhole_at")
        trickle_at = num("trickle_at")
        rst_at = num("rst_at")
        deny_at = num("deny_at")
        corrupt_marks = [int(mark) for mark in q["corrupt_at"].split(",")] if "corrupt_at" in q else []
        corrupt_len = num("corrupt_len") or 0
        corrupt_once = "corrupt_once" in q
        flaky_every = num("flaky_every")
        flaky_mode = q.get("flaky_mode", "silent")
        global_bps = num("global_bps")
        trickle_bps = num("trickle_bps") or 64 * 1024

        outage_after = num("outage_after")
        outage_secs = num("outage_secs")
        outage_remaining(run, outage_after, outage_secs)

        rate = global_bps
        pos, sent = start, 0

        # Only a connection that started before the mark and reached it. A read
        # of the index at the end of the file is past every mark, and firing
        # there would fault the startup instead of the middle of the film.
        def crossing(mark):
            return mark is not None and start < mark <= pos

        began = time.monotonic()
        window_sent = 0
        with open(self.path_to_file, "rb") as f:
            f.seek(start)
            while pos <= end:
                quiet = outage_remaining(run, outage_after, outage_secs)
                if quiet:
                    time.sleep(min(quiet, 0.25))
                    continue
                if crossing(blackhole_at) and fire_once(run, "blackhole"):
                    self.say(f"BLACKHOLE at {pos}")
                    # Holds the socket without a FIN, until the client leaves.
                    self.connection.settimeout(1)
                    while True:
                        try:
                            if not self.connection.recv(1, socket.MSG_PEEK):
                                return sent
                        except socket.timeout:
                            continue
                if crossing(trickle_at) and rate != trickle_bps and fire_once(run, "trickle"):
                    self.say(f"TRICKLE at {pos} -> {trickle_bps}B/s")
                    rate = trickle_bps
                    began, window_sent = time.monotonic(), 0
                if crossing(deny_at):
                    self.say(f"LINK EXPIRED at {pos}")
                    denied.add(run)
                    self.close_connection = True
                    return sent
                if crossing(rst_at) and fire_once(run, "rst"):
                    self.say(f"RST at {pos}")
                    self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
                    self.connection.close()
                    self.close_connection = True
                    return sent

                if flaky_every is not None and sent >= flaky_every:
                    if flaky_mode == "reset":
                        self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
                        self.connection.close()
                        self.close_connection = True
                        return sent
                    if flaky_mode == "silent":
                        self.connection.settimeout(1)
                        while True:
                            try:
                                if not self.connection.recv(1, socket.MSG_PEEK):
                                    return sent
                            except socket.timeout:
                                continue
                    if rate != trickle_bps:
                        rate = trickle_bps
                        began, window_sent = time.monotonic(), 0

                step = min(CHUNK, end - pos + 1)
                if rate:
                    step = min(step, max(4096, rate // 8))
                data = f.read(step)
                if not data:
                    return sent
                for corrupt_at in corrupt_marks:
                    if corrupt_once and start >= corrupt_at:
                        continue
                    if pos < corrupt_at + corrupt_len and pos + len(data) > corrupt_at:
                        # Deterministic, because a bad file is bad on every read.
                        noisy = bytearray(data)
                        rng = random.Random(pos)
                        lo = max(corrupt_at, pos) - pos
                        hi = min(corrupt_at + corrupt_len, pos + len(data)) - pos
                        noisy[lo:hi] = rng.randbytes(hi - lo)
                        data = bytes(noisy)
                self.wfile.write(data)
                pos += len(data)
                sent += len(data)
                if rate:
                    window_sent += len(data)
                    ahead = window_sent / rate - (time.monotonic() - began)
                    if ahead > 0:
                        time.sleep(ahead)
        return sent


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    Handler.directory = args.directory
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    server.daemon_threads = True
    print(f"serving {args.directory} on :{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    sys.exit(main())
