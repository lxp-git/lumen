"""Tests for lumen.py that need no device: parsing helpers and the WebSocket / CDP client
against an in-process fake server.

    python3 -m unittest discover -s plugins/lumen/tests
"""

import base64
import hashlib
import importlib.util
import json
import os
import socket
import struct
import threading
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SPEC = importlib.util.spec_from_file_location(
    "lumen", os.path.join(HERE, "..", "skills", "lumen", "scripts", "lumen.py"))
lumen = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(lumen)

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


class FakeCdpServer(threading.Thread):
    """Accepts one WebSocket client and answers each JSON-RPC call via `handler`."""

    def __init__(self, handler):
        super().__init__(daemon=True)
        self.handler = handler
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(1)
        self.port = self.listener.getsockname()[1]
        self.received = []

    def run(self):
        conn, _ = self.listener.accept()
        self.listener.close()
        head = b""
        while b"\r\n\r\n" not in head:
            head += conn.recv(4096)
        key = [l.split(b":", 1)[1].strip() for l in head.split(b"\r\n")
               if l.lower().startswith(b"sec-websocket-key")][0]
        accept = base64.b64encode(hashlib.sha1(key + GUID.encode()).digest())
        conn.sendall(b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                     b"Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + b"\r\n\r\n")
        buf = b""
        while True:
            frame, buf = self._read_frame(conn, buf)
            if frame is None:
                break
            opcode, payload = frame
            if opcode == 0x8:
                break
            if opcode == 0xA:  # pong to our ping
                self.received.append(("pong", payload))
                continue
            msg = json.loads(payload)
            self.received.append(msg)
            for out in self.handler(msg):
                conn.sendall(out)
        conn.close()

    @staticmethod
    def _read_frame(conn, buf):
        def need(n):
            nonlocal buf
            while len(buf) < n:
                chunk = conn.recv(65536)
                if not chunk:
                    raise EOFError
                buf += chunk
        try:
            need(2)
            opcode, n = buf[0] & 0x0F, buf[1] & 0x7F
            assert buf[1] & 0x80, "client frames must be masked"
            off = 2
            if n == 126:
                need(4)
                n, off = struct.unpack(">H", buf[2:4])[0], 4
            elif n == 127:
                need(10)
                n, off = struct.unpack(">Q", buf[2:10])[0], 10
            need(off + 4 + n)
            mask = buf[off:off + 4]
            payload = bytes(b ^ mask[i % 4] for i, b in enumerate(buf[off + 4:off + 4 + n]))
            return (opcode, payload), buf[off + 4 + n:]
        except EOFError:
            return None, buf


def server_frame(opcode, payload, fin=True):
    """Unmasked server-to-client frame, any length."""
    head = bytes([(0x80 if fin else 0) | opcode])
    n = len(payload)
    if n < 126:
        head += bytes([n])
    elif n < 65536:
        head += bytes([126]) + struct.pack(">H", n)
    else:
        head += bytes([127]) + struct.pack(">Q", n)
    return head + payload


def reply(msg, result):
    return server_frame(0x1, json.dumps({"id": msg["id"], "result": result}).encode())


class CdpClientTest(unittest.TestCase):
    def connect(self, handler):
        server = FakeCdpServer(handler)
        server.start()
        client = lumen.Cdp.__new__(lumen.Cdp)  # skip adb discovery
        client.ws = lumen.WebSocket("127.0.0.1", server.port, lumen.CDP_PATH, 5)
        client.timeout = 5
        client.next_id = 0
        client.events = []
        return server, client

    def test_call_returns_matching_result_and_keeps_events(self):
        def handler(msg):
            event = server_frame(0x1, json.dumps({"method": "Log.entryAdded", "params": {}}).encode())
            return [event, reply(msg, {"ok": msg["method"]})]

        server, client = self.connect(handler)
        self.assertEqual({"ok": "Lumen.getStatus"}, client.call("Lumen.getStatus"))
        self.assertEqual(["Log.entryAdded"], [e["method"] for e in client.events])
        client.ws.close()

    def test_large_and_fragmented_messages(self):
        big = "x" * 200_000  # 64-bit length on the wire

        def handler(msg):
            if msg["method"] == "Big.get":
                return [reply(msg, {"body": big})]
            body = json.dumps({"id": msg["id"], "result": {"parts": 3}}).encode()
            third = len(body) // 3
            return [server_frame(0x1, body[:third], fin=False),
                    server_frame(0x9, b"ping"),  # control frame between fragments
                    server_frame(0x0, body[third:2 * third], fin=False),
                    server_frame(0x0, body[2 * third:])]

        server, client = self.connect(handler)
        self.assertEqual(big, client.call("Big.get")["body"])
        self.assertEqual({"parts": 3}, client.call("Fragmented.get"))
        time.sleep(0.1)
        self.assertIn(("pong", b"ping"), server.received)
        client.ws.close()

    def test_large_client_payload_is_masked_with_extended_length(self):
        server, client = self.connect(lambda msg: [reply(msg, {"len": len(msg["params"]["q"])})])
        self.assertEqual({"len": 70_000}, client.call("Echo.len", {"q": "y" * 70_000}))
        client.ws.close()

    def test_error_reply_raises(self):
        def handler(msg):
            return [server_frame(0x1, json.dumps(
                {"id": msg["id"], "error": {"code": -32601, "message": "nope"}}).encode())]

        server, client = self.connect(handler)
        with self.assertRaisesRegex(lumen.LumenError, "nope"):
            client.call("Missing.method")
        client.ws.close()


class HelpersTest(unittest.TestCase):
    def test_socket_names(self):
        m = lumen.SOCKET_RE.search("0000: 00000002 ... @lumen_com.example.app_devtools_remote")
        self.assertEqual("com.example.app", m.group(2))
        m = lumen.SOCKET_RE.search("@lumen_com.example.app:remote_10_devtools_remote")
        self.assertEqual(("com.example.app:remote", "10"), (m.group(2), m.group(3)))
        self.assertIsNone(lumen.SOCKET_RE.search("@chrome_devtools_remote"))

    def test_parse_since(self):
        now = time.time() * 1000
        self.assertAlmostEqual(now - 600_000, lumen.parse_since("10m"), delta=2_000)
        self.assertAlmostEqual(now - 90_000, lumen.parse_since("90s"), delta=2_000)
        self.assertEqual(1234.0, lumen.parse_since("1234"))
        self.assertIsNone(lumen.parse_since(None))

    def test_parser_accepts_documented_commands(self):
        parser = lumen.build_parser()
        for argv in (["targets"], ["net", "--failed", "--url", "api"], ["req", "1.2"],
                     ["logs", "--level", "warning", "--exclude", "\tat "],
                     ["kv", "set", "SharedPreferences / a / b", "1"],
                     ["db", "sql", "app.db", "SELECT 1"], ["mock", "clear", "--source", "runtime"],
                     ["mock", "add", "--url-glob", "*x*", "--header", "A: b"], ["ui", "--depth", "3"],
                     ["har", "--no-bodies"], ["cdp", "Page.enable", "{}"]):
            parser.parse_args(argv)


if __name__ == "__main__":
    unittest.main()
