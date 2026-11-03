#!/usr/bin/env python3
"""A one-file BitTorrent swarm for StallLabDeviceTest: a seeder and the tracker that points at it.

  torrent_seeder.py <file> [--rate BYTES_PER_S] [--start-after S] [--die-after S]

Prints the info hash, the tracker URL and the file name, the three things the test needs
(LAB_TORRENT). The emulator reaches this machine at 10.0.2.2, so that is the peer address
the tracker hands out unless --peer-ip says otherwise.

Just enough of the protocol for libtorrent to stream from it: the handshake, the extension
protocol with ut_metadata (BEP 9 and 10), so a magnet link can fetch the info dictionary,
a full bitfield, and piece requests answered as they come. No encryption: libtorrent tries
it first, gets disconnected, and retries in plain text.

  --rate N         every connection together sends at most N bytes per second: a thin swarm
  --start-after S  no answer at all for S seconds: a swarm nobody is in yet
  --die-after S    every connection dropped and nothing accepted after S seconds: the
                   swarm going away mid-film
"""
import argparse
import hashlib
import os
import socket
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PIECE_LENGTH = 2 * 1024 * 1024
METADATA_BLOCK = 16 * 1024
OUR_UT_METADATA = 1


def bencode(value):
    if isinstance(value, int):
        return b"i%de" % value
    if isinstance(value, str):
        value = value.encode()
    if isinstance(value, bytes):
        return b"%d:%s" % (len(value), value)
    if isinstance(value, list):
        return b"l" + b"".join(bencode(v) for v in value) + b"e"
    if isinstance(value, dict):
        items = sorted((k.encode() if isinstance(k, str) else k, v) for k, v in value.items())
        return b"d" + b"".join(bencode(k) + bencode(v) for k, v in items) + b"e"
    raise TypeError(type(value))


def bdecode(data, i=0):
    """Returns (value, next index). Enough for the dictionaries peers send."""
    c = data[i:i + 1]
    if c == b"i":
        end = data.index(b"e", i)
        return int(data[i + 1:end]), end + 1
    if c == b"l":
        i, out = i + 1, []
        while data[i:i + 1] != b"e":
            v, i = bdecode(data, i)
            out.append(v)
        return out, i + 1
    if c == b"d":
        i, out = i + 1, {}
        while data[i:i + 1] != b"e":
            k, i = bdecode(data, i)
            v, i = bdecode(data, i)
            out[k] = v
        return out, i + 1
    colon = data.index(b":", i)
    n = int(data[i:colon])
    return data[colon + 1:colon + 1 + n], colon + 1 + n


def info_dict(path):
    """Hashing 2GB takes a few seconds, so the result is kept beside the file."""
    size = os.path.getsize(path)
    cache = f"{path}.pieces"
    if os.path.exists(cache) and os.path.getsize(cache) == 20 * ((size + PIECE_LENGTH - 1) // PIECE_LENGTH):
        pieces = open(cache, "rb").read()
    else:
        digests = []
        with open(path, "rb") as f:
            while chunk := f.read(PIECE_LENGTH):
                digests.append(hashlib.sha1(chunk).digest())
        pieces = b"".join(digests)
        open(cache, "wb").write(pieces)
    return {"name": os.path.basename(path), "length": size, "piece length": PIECE_LENGTH, "pieces": pieces}


class Bucket:
    """One token bucket for the whole swarm, since a thin swarm is thin for everyone."""

    def __init__(self, rate):
        self.rate, self.tokens, self.then = rate, float(rate or 0), time.monotonic()
        self.lock = threading.Lock()

    def take(self, n):
        if not self.rate:
            return
        while True:
            with self.lock:
                now = time.monotonic()
                self.tokens = min(float(self.rate), self.tokens + (now - self.then) * self.rate)
                self.then = now
                if self.tokens >= n:
                    self.tokens -= n
                    return
                wait = (n - self.tokens) / self.rate
            time.sleep(min(wait, 0.1))


class Swarm:
    def __init__(self, args):
        self.args = args
        self.path = args.file
        self.info = info_dict(self.path)
        self.metadata = bencode(self.info)
        self.info_hash = hashlib.sha1(self.metadata).digest()
        self.num_pieces = len(self.info["pieces"]) // 20
        self.bucket = Bucket(args.rate)
        self.began = time.monotonic()
        self.peer_id = b"-LB0001-" + os.urandom(12)
        self.conns = set()
        if args.die_after is not None:
            threading.Thread(target=self.bury, daemon=True).start()

    def bury(self):
        """Ends the swarm at --die-after: every connection cut, as if every peer left at once."""
        time.sleep(self.args.die_after)
        self.say(f"the swarm goes away, {len(self.conns)} connections cut")
        for conn in list(self.conns):
            try:
                conn.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    def say(self, message):
        print(f"{time.strftime('%H:%M:%S')} {message}", flush=True)

    def alive(self):
        return self.args.die_after is None or time.monotonic() - self.began < self.args.die_after

    def ready(self):
        return time.monotonic() - self.began >= self.args.start_after

    def serve_peers(self):
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("0.0.0.0", self.args.port))
        listener.listen(16)
        while True:
            conn, addr = listener.accept()
            threading.Thread(target=self.peer, args=(conn, addr), daemon=True).start()

    def peer(self, conn, addr):
        conn.settimeout(5)
        try:
            if not self.ready() or not self.alive():
                return
            head = recv_exact(conn, 68)
            if head[:20] != b"\x13BitTorrent protocol" or head[28:48] != self.info_hash:
                return  # an encrypted attempt, or another torrent
            reserved = bytearray(8)
            reserved[5] |= 0x10  # extension protocol
            conn.sendall(b"\x13BitTorrent protocol" + bytes(reserved) + self.info_hash + self.peer_id)
            # The bitfield has to be the first message after the handshake.
            bits = bytearray((self.num_pieces + 7) // 8)
            for i in range(self.num_pieces):
                bits[i // 8] |= 0x80 >> (i % 8)
            send(conn, 5, bytes(bits))
            send(conn, 20, bytes([0]) + bencode({"m": {"ut_metadata": OUR_UT_METADATA},
                                                  "metadata_size": len(self.metadata)}))
            send(conn, 1, b"")  # unchoke
            self.say(f"peer {addr[0]}:{addr[1]} connected")
            their_ut_metadata = None
            sent = 0
            # Blocking, with a long timeout for reads and writes alike: a client that is busy
            # writing to its disk stops reading for a while, and that is not a dead peer.
            # --die-after closes the socket from outside instead, see `bury`.
            conn.settimeout(120)
            self.conns.add(conn)
            with open(self.path, "rb") as f:
                while self.alive():
                    length = struct.unpack(">I", recv_exact(conn, 4))[0]
                    if length == 0:
                        continue
                    body = recv_exact(conn, length)
                    kind, payload = body[0], body[1:]
                    if kind == 6:  # request
                        index, begin, size = struct.unpack(">III", payload)
                        f.seek(index * PIECE_LENGTH + begin)
                        block = f.read(size)
                        self.bucket.take(len(block))
                        send(conn, 7, struct.pack(">II", index, begin) + block)
                        sent += len(block)
                    elif kind == 20:
                        ext, data = payload[0], payload[1:]
                        message, end = bdecode(data)
                        if ext == 0:
                            their_ut_metadata = message.get(b"m", {}).get(b"ut_metadata")
                        elif ext == OUR_UT_METADATA and message.get(b"msg_type") == 0 and their_ut_metadata:
                            piece = message[b"piece"]
                            chunk = self.metadata[piece * METADATA_BLOCK:(piece + 1) * METADATA_BLOCK]
                            reply = bencode({"msg_type": 1, "piece": piece, "total_size": len(self.metadata)})
                            send(conn, 20, bytes([their_ut_metadata]) + reply + chunk)
            self.say(f"peer {addr[0]}:{addr[1]} dropped, the swarm is gone (sent {sent})")
        except (ConnectionError, OSError, ValueError, KeyError, IndexError) as e:
            self.say(f"peer {addr[0]}:{addr[1]} gone: {type(e).__name__} {e}")
        finally:
            self.conns.discard(conn)
            conn.close()

    def serve_tracker(self):
        swarm = self

        class Tracker(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                q = parse_qs(urlparse(self.path).query)
                peers = b""
                if swarm.ready() and swarm.alive():
                    peers = socket.inet_aton(swarm.args.peer_ip) + struct.pack(">H", swarm.args.port)
                # A "min interval" a forced announce may come back after. Without one,
                # libtorrent assumes about 90s, and public trackers ask for far longer.
                body = bencode({"interval": 30, "min interval": 10, "peers": peers})
                swarm.say(f"announce from port {q.get('port', ['?'])[0]}, {len(peers) // 6} peers given")
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        ThreadingHTTPServer(("0.0.0.0", self.args.tracker_port), Tracker).serve_forever()


def recv_exact(conn, n):
    data = b""
    while len(data) < n:
        chunk = conn.recv(n - len(data))
        if not chunk:
            raise ConnectionError("closed")
        data += chunk
    return data


def send(conn, kind, payload):
    conn.sendall(struct.pack(">IB", len(payload) + 1, kind) + payload)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("file")
    parser.add_argument("--port", type=int, default=6881)
    parser.add_argument("--tracker-port", type=int, default=8767)
    parser.add_argument("--peer-ip", default="10.0.2.2")
    parser.add_argument("--rate", type=int, default=0)
    parser.add_argument("--start-after", type=float, default=0)
    parser.add_argument("--die-after", type=float, default=None)
    args = parser.parse_args()
    swarm = Swarm(args)
    threading.Thread(target=swarm.serve_tracker, daemon=True).start()
    tracker = f"http://{args.peer_ip}:{args.tracker_port}/announce"
    print(f"LAB_TORRENT={swarm.info_hash.hex()},{tracker},{swarm.info['name']}", flush=True)
    swarm.serve_peers()


if __name__ == "__main__":
    sys.exit(main())
