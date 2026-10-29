#!/usr/bin/env python3
"""Drive a Lumen-enabled Android debug build from the terminal.

Lumen serves the Chrome DevTools Protocol (CDP) inside the app. This tool reaches it
over adb, so there is no Chrome window involved: it reads captured network traffic and
logcat, edits SharedPreferences / DataStore / MMKV, queries SQLite, dumps the View tree,
and manages mock rules. Python 3.8+ standard library only.

    lumen.py targets                      # Lumen processes on the device
    lumen.py net --url api/ --failed      # captured HTTP / WebSocket exchanges
    lumen.py req <id>                     # one exchange with headers and body
    lumen.py sessions                     # earlier launches kept on disk (net/har --session)
    lumen.py logs --level warning --grep crash
    lumen.py kv list | kv set "SharedPreferences / prefs / onboarded" false
    lumen.py db list | db sql app.db "SELECT * FROM users LIMIT 5"
    lumen.py ui --grep Button             # View hierarchy (apps on Lumen <= 0.3.0)
    lumen.py mock add --url-contains /v1/me --status 500 --body '{"error":"boom"}'

Environment: ANDROID_SERIAL (device), LUMEN_PACKAGE (target app), ADB (adb binary).
Add --json to any command for machine-readable output.
"""

import argparse
import base64
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import time

# @lumen_<process>_devtools_remote, or @lumen_<process>_<user>_devtools_remote for user != 0.
SOCKET_RE = re.compile(r"@(lumen_(.+)_devtools_remote)$")
CDP_PATH = "/devtools/page/1"
LOCAL_STORAGE = {"storageKey": "lumen-default", "isLocalStorage": True}


class LumenError(Exception):
    pass


# ── adb ──────────────────────────────────────────────────────────────────────


def adb_binary():
    explicit = os.environ.get("ADB")
    if explicit:
        return explicit
    found = shutil.which("adb")
    if found:
        return found
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        home = os.environ.get(var)
        if home and os.path.exists(os.path.join(home, "platform-tools", "adb")):
            return os.path.join(home, "platform-tools", "adb")
    raise LumenError("adb not found: install platform-tools or set ADB / ANDROID_HOME")


class Adb:
    def __init__(self, serial=None):
        self.binary = adb_binary()
        self.serial = serial

    def run(self, *args, binary=False):
        cmd = [self.binary] + (["-s", self.serial] if self.serial else []) + list(args)
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if proc.returncode != 0:
            err = proc.stderr.decode(errors="replace").strip()
            raise LumenError("adb %s failed: %s" % (" ".join(args), err))
        return proc.stdout if binary else proc.stdout.decode(errors="replace")

    def devices(self):
        out = subprocess.run([self.binary, "devices"], stdout=subprocess.PIPE).stdout.decode()
        return [l.split()[0] for l in out.splitlines()[1:] if l.strip().endswith("device")]

    def lumen_sockets(self):
        """[(socket name, process name)] for every Lumen agent listening on the device."""
        found = []
        for line in self.run("shell", "cat", "/proc/net/unix").splitlines():
            m = SOCKET_RE.search(line.strip())
            if m and (m.group(1), m.group(2)) not in found:
                found.append((m.group(1), m.group(2)))
        return found

    def forward(self, socket_name):
        out = self.run("forward", "tcp:0", "localabstract:" + socket_name).strip()
        if not out.isdigit():
            raise LumenError("adb forward did not return a port: %r" % out)
        return int(out)

    def remove_forward(self, port):
        try:
            self.run("forward", "--remove", "tcp:%d" % port)
        except LumenError:
            pass


def process_matches(process, package):
    """`process` is the socket's middle part: a process name, maybe with a `_<user>` suffix."""
    m = re.fullmatch(r"(.+?)(:[^_]*)?(?:_(\d+))?", process)
    candidates = {process, m.group(1), (m.group(1) + (m.group(2) or ""))} if m else {process}
    return package in candidates or any(c.startswith(package + ":") for c in candidates)


def resolve_target(serial, package):
    """Pick the device and Lumen socket, explaining what to do when there is no match."""
    probe = Adb(serial)
    serials = [serial] if serial else probe.devices()
    if not serials:
        raise LumenError("no adb device connected (adb devices is empty)")
    candidates = []
    for s in serials:
        adb = Adb(s)
        for sock, process in adb.lumen_sockets():
            if package and not process_matches(process, package):
                continue
            candidates.append((adb, sock, process))
    if not candidates:
        hint = (" for package %s" % package) if package else ""
        raise LumenError(
            "no Lumen process found%s. Is a debug build with the Lumen plugin running?\n"
            "  start it:  adb shell monkey -p <applicationId> -c android.intent.category.LAUNCHER 1"
            % hint)
    if len(candidates) > 1:
        listing = "\n".join("  %s  %s" % (a.serial or "-", p) for a, _, p in candidates)
        raise LumenError("several Lumen processes; pick one with -p/--package or -s/--serial:\n"
                         + listing)
    return candidates[0]


# ── minimal WebSocket client (RFC 6455, client side) ────────────────────────


class WebSocket:
    def __init__(self, host, port, path, timeout):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        request = ("GET %s HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\n"
                   "Connection: Upgrade\r\nSec-WebSocket-Key: %s\r\n"
                   "Sec-WebSocket-Version: 13\r\n\r\n") % (path, host, port, key)
        self.sock.sendall(request.encode())
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise LumenError("CDP endpoint closed during the WebSocket handshake")
            head += chunk
        head, self.buffer = head.split(b"\r\n\r\n", 1)
        self.fragments = b""
        status = head.split(b"\r\n", 1)[0]
        if b" 101 " not in status + b" ":
            raise LumenError("WebSocket upgrade refused: %s" % status.decode(errors="replace"))

    def _send_frame(self, opcode, payload):
        header = bytes([0x80 | opcode])
        n = len(payload)
        if n < 126:
            header += bytes([0x80 | n])
        elif n < 65536:
            header += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            header += bytes([0x80 | 127]) + struct.pack(">Q", n)
        mask = os.urandom(4)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(header + mask + masked)

    def send(self, text):
        self._send_frame(0x1, text.encode("utf-8"))

    def _next_frame(self):
        """(opcode, fin, payload) once a whole frame is buffered, else None. Consumes nothing
        until then, so a timeout between reads leaves the stream intact."""
        buf = self.buffer
        if len(buf) < 2:
            return None
        n, off = buf[1] & 0x7F, 2
        if n == 126:
            if len(buf) < 4:
                return None
            n, off = struct.unpack(">H", buf[2:4])[0], 4
        elif n == 127:
            if len(buf) < 10:
                return None
            n, off = struct.unpack(">Q", buf[2:10])[0], 10
        mask = None
        if buf[1] & 0x80:
            if len(buf) < off + 4:
                return None
            mask, off = buf[off:off + 4], off + 4
        if len(buf) < off + n:
            return None
        payload = buf[off:off + n]
        if mask:
            payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.buffer = buf[off + n:]
        return buf[0] & 0x0F, bool(buf[0] & 0x80), payload

    def recv(self):
        """Next complete text message; answers pings, raises on close. Socket timeouts
        propagate with buffered bytes and partial fragments kept for the next call."""
        while True:
            frame = self._next_frame()
            if frame is None:
                chunk = self.sock.recv(65536)
                if not chunk:
                    raise LumenError("CDP connection closed by the app")
                self.buffer += chunk
                continue
            opcode, fin, payload = frame
            if opcode == 0x8:
                raise LumenError("CDP connection closed by the app")
            if opcode == 0x9:
                self._send_frame(0xA, payload)
                continue
            if opcode in (0x0, 0x1, 0x2):
                self.fragments += payload
                if fin:
                    message, self.fragments = self.fragments, b""
                    return message.decode("utf-8", errors="replace")

    def close(self):
        try:
            self._send_frame(0x8, struct.pack(">H", 1000))
        except OSError:
            pass
        self.sock.close()


class Cdp:
    """One CDP session. Events that arrive while waiting for a reply land in self.events."""

    def __init__(self, serial=None, package=None, timeout=15.0):
        self.adb, self.socket_name, self.process = resolve_target(serial, package)
        self.timeout = timeout
        self.port = self.adb.forward(self.socket_name)
        try:
            self.ws = WebSocket("127.0.0.1", self.port, CDP_PATH, timeout)
        except BaseException:
            self.adb.remove_forward(self.port)
            raise
        self.next_id = 0
        self.events = []

    def call(self, method, params=None):
        self.next_id += 1
        call_id = self.next_id
        self.ws.send(json.dumps({"id": call_id, "method": method, "params": params or {}}))
        deadline = time.time() + self.timeout
        while True:
            self.ws.sock.settimeout(max(0.1, deadline - time.time()))
            try:
                msg = json.loads(self.ws.recv())
            except socket.timeout:
                raise LumenError("%s timed out after %.0fs" % (method, self.timeout))
            if msg.get("id") == call_id:
                if "error" in msg:
                    raise LumenError("%s failed: %s" % (method, msg["error"].get("message")))
                return msg.get("result", {})
            if "method" in msg:
                self.events.append(msg)

    def wait_events(self, seconds, on_event=None):
        """Collect (or stream to on_event) events for up to `seconds`."""
        end = time.time() + seconds
        while time.time() < end:
            self.ws.sock.settimeout(max(0.05, end - time.time()))
            try:
                msg = json.loads(self.ws.recv())
            except socket.timeout:
                break
            if "method" in msg:
                if on_event:
                    on_event(msg)
                else:
                    self.events.append(msg)

    def close(self):
        try:
            self.ws.close()
        finally:
            self.adb.remove_forward(self.port)


# ── output helpers ───────────────────────────────────────────────────────────


def emit(args, data, human):
    if args.json:
        print(json.dumps(data, indent=2, ensure_ascii=False))
    else:
        human(data)


def clip(text, width):
    text = str(text)
    return text if len(text) <= width else text[: width - 1] + "…"


def parse_since(value):
    """'90s', '10m', '2h', '1d' ago, or epoch millis -> epoch millis."""
    if value is None:
        return None
    m = re.fullmatch(r"(\d+(?:\.\d+)?)([smhd])", value.strip())
    if m:
        scale = {"s": 1, "m": 60, "h": 3600, "d": 86400}[m.group(2)]
        return (time.time() - float(m.group(1)) * scale) * 1000.0
    return float(value)


def fmt_time(ms):
    return time.strftime("%H:%M:%S", time.localtime(ms / 1000.0)) + ".%03d" % (int(ms) % 1000)


# ── commands ─────────────────────────────────────────────────────────────────


def cmd_targets(args):
    serials = [args.serial] if args.serial else Adb().devices()
    rows = []
    for s in serials:
        for sock, process in Adb(s).lumen_sockets():
            rows.append({"serial": s, "process": process, "socket": sock})

    def human(data):
        if not data:
            print("no Lumen processes (is a debug build with the Lumen plugin running?)")
        for r in data:
            print("%s  %s  @%s" % (r["serial"], r["process"], r["socket"]))

    emit(args, rows, human)


def cmd_status(cdp, args):
    status = cdp.call("Lumen.getStatus")
    version = cdp.call("Browser.getVersion")
    status.update({"process": cdp.process, "lumen": version.get("revision"),
                   "device": version.get("userAgent")})

    def human(d):
        for k in ("process", "lumen", "device", "networkCount", "networkReplayLimit", "segments",
                  "activeLogSegment", "retentionDays", "mockRuleCount", "mockRecording"):
            print("%-19s %s" % (k, d.get(k)))

    emit(args, status, human)


def cmd_net(cdp, args):
    params = {"limit": args.limit, "failed": args.failed}
    if args.url:
        params["urlContains"] = args.url
    if args.method:
        params["method"] = args.method
    if args.status:
        params["status"] = args.status
    if args.session:
        params["sessionId"] = args.session
    result = cdp.call("Lumen.listRequests", params)
    if result.get("error"):
        raise LumenError("no session %s (see `sessions`)" % args.session)

    def human(d):
        for r in d["requests"]:
            status = r["status"] if r["status"] is not None else ("ERR" if r["failed"] else "…")
            kind = "WS" if r["webSocket"] else r["method"]
            dur = "%dms" % r["durationMs"] if r["durationMs"] is not None else "-"
            flags = (" mock" if r["mocked"] else "") + (" frames=%d" % r["frameCount"] if r["webSocket"] else "")
            print("%-14s %-6s %-4s %7s %8s  %s%s" % (r["id"], kind, status, dur, r["size"],
                                                    clip(r["url"], 110), flags))
        print("(%d shown of %d matching)" % (len(d["requests"]), d["total"]))

    emit(args, result, human)


def check_request(r, args):
    if r.get("error") == "unknown-session":
        raise LumenError("no session %s (see `sessions`)" % args.session)
    if r.get("error"):
        raise LumenError("no captured request %s (see `net` for ids)" % args.id)


def request_params(args, **extra):
    params = {"requestId": args.id}
    if args.session:
        params["sessionId"] = args.session
    params.update(extra)
    return params


def cmd_req(cdp, args):
    r = cdp.call("Lumen.getRequest", request_params(args, maxBodyChars=args.body_chars,
                                                    maxFrames=args.frames))
    check_request(r, args)

    def human(d):
        print("%s %s\nstatus %s %s  %s  %s bytes  %sms%s" % (
            d["method"], d["url"], d["status"], d.get("statusText") or "", d.get("mimeType"),
            d["size"], d["durationMs"], "  MOCKED" if d["mocked"] else ""))
        if d.get("failed"):
            print("failed: " + d["failed"])
        if d.get("note"):
            print("note: " + d["note"])
        if d.get("fromDisk"):
            launch = "this launch" if d.get("currentSession") else d.get("sessionId")
            print("(%s, read back from disk: WebSocket frames are not kept)" % launch)
        print("\n> request headers")
        for k, v in d["requestHeaders"].items():
            print("  %s: %s" % (k, v))
        if d.get("requestBody"):
            print("\n> request body\n" + d["requestBody"])
        print("\n< response headers")
        for k, v in d["responseHeaders"].items():
            print("  %s: %s" % (k, v))
        if "responseBody" in d:
            label = "base64" if d["responseBodyBase64"] else "text"
            more = " (truncated)" if d["responseBodyTruncated"] else ""
            print("\n< response body [%s]%s\n%s" % (label, more, d["responseBody"]))
        for f in d.get("frames", []):
            print("%s %s %s" % (fmt_time(f["time"]), f["dir"], f["payload"]))

    emit(args, r, human)


def cmd_body(cdp, args):
    r = cdp.call("Lumen.getRequest", request_params(args, maxBodyChars=2 ** 31 - 1, maxFrames=0))
    check_request(r, args)
    if "responseBody" not in r:
        raise LumenError("no response body captured for %s" % args.id)
    data = base64.b64decode(r["responseBody"]) if r["responseBodyBase64"] else \
        r["responseBody"].encode("utf-8")
    if args.output:
        with open(args.output, "wb") as f:
            f.write(data)
        print("wrote %d bytes to %s" % (len(data), args.output))
    else:
        sys.stdout.buffer.write(data)


def print_log(entry):
    level = entry["level"][0].upper()
    print("%s %s %s" % (fmt_time(entry["time"]), level, entry["text"]))


def cmd_logs(cdp, args):
    if args.follow:
        def on_event(msg):
            if msg["method"] != "Log.entryAdded":
                return
            e = msg["params"]["entry"]
            for line in e["text"].split("\n"):
                if args.grep and args.grep.lower() not in line.lower():
                    continue
                if args.exclude and args.exclude.lower() in line.lower():
                    continue
                print("%s %s %s" % (fmt_time(e["timestamp"]), e["level"][0].upper(), line))
            sys.stdout.flush()

        newest = cdp.call("Lumen.getLogs", {"limit": 1})["logs"]
        high_water = newest[-1]["time"] if newest else 0

        def live_only(msg):
            # Log.enable replays a page of history, possibly after its reply; skip it.
            if msg["method"] == "Log.entryAdded" and msg["params"]["entry"]["timestamp"] <= high_water:
                return
            on_event(msg)

        cdp.call("Log.enable")
        for msg in cdp.events:
            live_only(msg)
        cdp.events.clear()
        cdp.wait_events(args.seconds, live_only)
        cdp.call("Log.disable")
        return
    params = {"limit": args.limit}
    if args.grep:
        params["contains"] = args.grep
    if args.exclude:
        params["excludes"] = args.exclude
    if args.level:
        params["level"] = args.level
    since = parse_since(args.since)
    if since is not None:
        params["sinceMs"] = since
    result = cdp.call("Lumen.getLogs", params)
    emit(args, result, lambda d: [print_log(e) for e in d["logs"]])


def cmd_mock(cdp, args):
    if args.mock_cmd == "list":
        result = cdp.call("Lumen.listMockRules")

        def human(d):
            if not d["rules"]:
                print("no mock rules")
            for r in d["rules"]:
                match = r.get("urlEquals") or r.get("urlGlob") or r.get("urlContains") or "*"
                print("%-28s %-8s %-6s %s -> %s%s" % (r["id"], r["source"], r.get("method") or "*",
                                                     match, r["status"],
                                                     " +%dms" % r["delayMs"] if r["delayMs"] else ""))

        emit(args, result, human)
        return
    if args.mock_cmd == "add":
        if not (args.url_contains or args.url_glob):
            raise LumenError("mock add needs --url-contains or --url-glob")
        body = args.body
        if args.body_file:
            with open(args.body_file, encoding="utf-8") as f:
                body = f.read()
        headers = {"Content-Type": args.content_type}
        for h in args.header or []:
            name, _, value = h.partition(":")
            headers[name.strip()] = value.strip()
        params = {"status": args.status, "body": body if body is not None else "{}",
                  "headers": headers, "delayMs": args.delay}
        if args.url_contains:
            params["urlContains"] = args.url_contains
        if args.url_glob:
            params["urlGlob"] = args.url_glob
        if args.method:
            params["method"] = args.method
        result = cdp.call("Lumen.addMockRule", params)
    elif args.mock_cmd == "rm":
        result = cdp.call("Lumen.removeMockRule", {"id": args.id})
    elif args.mock_cmd == "clear":
        result = cdp.call("Lumen.clearMockRules", {"source": args.source} if args.source else {})
    else:  # record
        result = cdp.call("Lumen.setMockRecording", {"enabled": args.state == "on"})
    emit(args, result, lambda d: print(d.get("value")))


def kv_items(cdp):
    return cdp.call("DOMStorage.getDOMStorageItems", {"storageId": LOCAL_STORAGE})["entries"]


def cmd_kv(cdp, args):
    cdp.call("DOMStorage.enable")
    if args.kv_cmd == "list":
        rows = [{"key": k, "value": v} for k, v in kv_items(cdp)
                if not args.grep or args.grep.lower() in (k + " " + v).lower()]
        emit(args, rows, lambda d: [print("%s = %s" % (r["key"], r["value"])) for r in d])
        return
    if args.kv_cmd == "get":
        for k, v in kv_items(cdp):
            if k == args.key:
                emit(args, {"key": k, "value": v}, lambda d: print(d["value"]))
                return
        raise LumenError("no such key: %s (keys look like 'Engine / store / key')" % args.key)
    if args.kv_cmd == "set":
        cdp.call("DOMStorage.setDOMStorageItem",
                 {"storageId": LOCAL_STORAGE, "key": args.key, "value": args.value})
    else:
        cdp.call("DOMStorage.removeDOMStorageItem", {"storageId": LOCAL_STORAGE, "key": args.key})
    cdp.wait_events(0.3)  # a rejected write comes back as a Console error + revert event
    errors = [e["params"]["message"]["text"] for e in cdp.events
              if e["method"] == "Console.messageAdded"]
    if errors:
        raise LumenError("; ".join(errors))
    print("ok")


def databases(cdp):
    cdp.events.clear()
    cdp.call("Database.enable")
    cdp.wait_events(0.3)
    return {e["params"]["database"]["name"]: e["params"]["database"]["id"]
            for e in cdp.events if e["method"] == "Database.addDatabase"}


def cmd_db(cdp, args):
    dbs = databases(cdp)
    if args.db_cmd == "list":
        emit(args, sorted(dbs), lambda d: print("\n".join(d) or "no SQLite databases"))
        return
    if args.db not in dbs:
        raise LumenError("no database %r; have: %s" % (args.db, ", ".join(sorted(dbs)) or "none"))
    if args.db_cmd == "tables":
        result = cdp.call("Database.getDatabaseTableNames", {"databaseId": dbs[args.db]})
        emit(args, result["tableNames"], lambda d: print("\n".join(d)))
        return
    result = cdp.call("Database.executeSQL", {"databaseId": dbs[args.db], "query": args.query})
    if result.get("sqlError"):
        raise LumenError("SQL error: %s" % result["sqlError"].get("message"))
    columns = result.get("columnNames") or []
    values = result.get("values") or []
    rows = [dict(zip(columns, values[i:i + len(columns)]))
            for i in range(0, len(values), max(1, len(columns)))]

    def human(_):
        print(" | ".join(columns))
        for row in rows:
            print(" | ".join("NULL" if row[c] is None else clip(row[c], 80) for c in columns))
        print("(%d rows%s)" % (len(rows), ", capped at 250" if len(rows) >= 250 else ""))

    emit(args, rows, human)


def ui_lines(node, depth, max_depth, out):
    attrs = node.get("attributes") or []
    pairs = dict(zip(attrs[0::2], attrs[1::2]))
    label = node.get("nodeName", "?")
    shown = " ".join('%s="%s"' % (k, clip(v, 60)) for k, v in pairs.items()
                     if k in ("id", "text", "contentDescription", "hint", "visibility")
                     or k.startswith("lumen"))
    value = (node.get("nodeValue") or "").strip()
    out.append("  " * depth + label + (" " + shown if shown else "") +
               (' "%s"' % clip(value, 60) if value else ""))
    if max_depth is None or depth < max_depth:
        for child in node.get("children") or []:
            ui_lines(child, depth + 1, max_depth, out)


def cmd_ui(cdp, args):
    cdp.call("DOM.enable")
    root = cdp.call("DOM.getDocument", {"depth": -1}).get("root")
    if root is None:
        # Lumen after 0.3.0 leaves DOM out; the dispatcher acknowledges it with {}.
        raise LumenError("this app's Lumen has the View tree (DOM / Elements) turned off; "
                         "only Lumen 0.3.0 and earlier serve it")
    if args.json:
        print(json.dumps(root, indent=2, ensure_ascii=False))
        return
    lines = []
    ui_lines(root, 0, args.depth, lines)
    if args.grep:
        lines = [l for l in lines if args.grep.lower() in l.lower()]
    print("\n".join(lines))


def cmd_sessions(cdp, args):
    result = cdp.call("Lumen.listNetworkSessions")

    def human(d):
        for s in d["sessions"]:
            started = (time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(s["startedAtMs"] / 1000.0))
                       if s["startedAtMs"] else "metadata only")
            print("%-34s %-19s %6d requests %7.1f MB%s" % (
                s["id"], started, s["requestCount"], s["sizeBytes"] / 1048576.0,
                "  (this launch)" if s["current"] else ""))
        if not d["sessions"]:
            print("(no sessions on disk)")

    emit(args, result, human)


def cmd_har(cdp, args):
    params = {"includeBodies": not args.no_bodies}
    if args.session:
        params["sessionId"] = args.session
    path = cdp.call("Lumen.exportHar", params).get("value")
    if not path or not path.startswith("/"):
        raise LumenError("exportHar failed: %s" % path)
    package = cdp.call("Target.getTargetInfo")["targetInfo"]["title"].split(":")[0]
    data = cdp.adb.run("exec-out", "run-as", package, "cat", path, binary=True)
    if not data.lstrip().startswith(b"{"):
        raise LumenError("could not read %s: %s" % (path, data[:200].decode(errors="replace").strip()))
    out = args.output or "lumen-%s-%s.har" % (package, time.strftime("%Y%m%d-%H%M%S"))
    with open(out, "wb") as f:
        f.write(data)
    print("wrote %s (%d bytes)" % (out, len(data)))


def cmd_cdp(cdp, args):
    params = json.loads(args.params) if args.params else {}
    result = cdp.call(args.method, params)
    if args.events:
        cdp.wait_events(args.events)
        result = {"result": result, "events": cdp.events}
    print(json.dumps(result, indent=2, ensure_ascii=False))


# ── CLI ──────────────────────────────────────────────────────────────────────


def build_parser():
    p = argparse.ArgumentParser(prog="lumen", description=__doc__.split("\n\n")[0])
    p.add_argument("-s", "--serial", default=os.environ.get("ANDROID_SERIAL"),
                   help="adb device serial (default: $ANDROID_SERIAL or the only device)")
    p.add_argument("-p", "--package", default=os.environ.get("LUMEN_PACKAGE"),
                   help="target process / applicationId (default: $LUMEN_PACKAGE or the only one)")
    p.add_argument("--json", action="store_true", help="print raw JSON")
    p.add_argument("--timeout", type=float, default=15.0, help="seconds per CDP call")
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("targets", help="list Lumen processes on connected devices")
    sub.add_parser("status", help="agent status, version, capture counters")

    s = sub.add_parser("net", help="list captured HTTP / WebSocket exchanges (newest last)")
    s.add_argument("--url", help="URL substring")
    s.add_argument("--method")
    s.add_argument("--status", type=int)
    s.add_argument("--failed", action="store_true", help="only errors and HTTP >= 400")
    s.add_argument("--limit", type=int, default=50)
    s.add_argument("--session", help="an earlier launch from `sessions` (default: this one)")

    s = sub.add_parser("req", help="one exchange: headers, bodies, WebSocket frames")
    s.add_argument("id")
    s.add_argument("--body-chars", type=int, default=20000)
    s.add_argument("--frames", type=int, default=100, help="newest WebSocket frames to show")
    s.add_argument("--session", help="look only in this launch (ids repeat when pids are reused)")

    s = sub.add_parser("body", help="raw response body (decoded) to stdout or a file")
    s.add_argument("id")
    s.add_argument("-o", "--output")
    s.add_argument("--session", help="look only in this launch")

    sub.add_parser("sessions", help="network history per launch kept on disk")

    s = sub.add_parser("logs", help="search archived logcat, or --follow live")
    s.add_argument("--grep", help="case-insensitive substring")
    s.add_argument("--exclude", help="drop lines containing this (e.g. a tab + 'at ' for stack frames)")
    s.add_argument("--level", choices=["verbose", "info", "warning", "error"],
                   help="minimum level")
    s.add_argument("--since", help="e.g. 90s, 10m, 2h, or epoch millis")
    s.add_argument("--limit", type=int, default=200)
    s.add_argument("-f", "--follow", action="store_true", help="stream new lines")
    s.add_argument("--seconds", type=float, default=30, help="how long --follow runs")

    s = sub.add_parser("mock", help="manage mock rules (served by the OkHttp interceptor)")
    ms = s.add_subparsers(dest="mock_cmd", required=True)
    ms.add_parser("list")
    a = ms.add_parser("add")
    a.add_argument("--url-contains")
    a.add_argument("--url-glob", help="'*' any run, '?' one char")
    a.add_argument("--method")
    a.add_argument("--status", type=int, default=200)
    a.add_argument("--body")
    a.add_argument("--body-file")
    a.add_argument("--content-type", default="application/json")
    a.add_argument("--header", action="append", help="'Name: value', repeatable")
    a.add_argument("--delay", type=int, default=0, help="milliseconds")
    r = ms.add_parser("rm")
    r.add_argument("id")
    c = ms.add_parser("clear")
    c.add_argument("--source", choices=["runtime", "recorded", "asset"])
    rec = ms.add_parser("record", help="persist DevTools overrides as offline rules")
    rec.add_argument("state", choices=["on", "off"])

    s = sub.add_parser("kv", help="SharedPreferences / DataStore / MMKV")
    ks = s.add_subparsers(dest="kv_cmd", required=True)
    kl = ks.add_parser("list")
    kl.add_argument("--grep")
    kg = ks.add_parser("get")
    kg.add_argument("key", help="'Engine / store / key'")
    kset = ks.add_parser("set", help="existing keys only; the value keeps its type")
    kset.add_argument("key")
    kset.add_argument("value")
    krm = ks.add_parser("rm")
    krm.add_argument("key")

    s = sub.add_parser("db", help="SQLite / Room databases")
    ds = s.add_subparsers(dest="db_cmd", required=True)
    ds.add_parser("list")
    dt = ds.add_parser("tables")
    dt.add_argument("db")
    dq = ds.add_parser("sql")
    dq.add_argument("db")
    dq.add_argument("query")

    s = sub.add_parser("ui", help="View hierarchy of the attached windows")
    s.add_argument("--depth", type=int)
    s.add_argument("--grep")

    s = sub.add_parser("har", help="export captured traffic as HAR 1.2")
    s.add_argument("-o", "--output")
    s.add_argument("--no-bodies", action="store_true")
    s.add_argument("--session", help="an earlier launch from `sessions` (default: this one)")

    s = sub.add_parser("cdp", help="raw CDP call, e.g. cdp Network.getResponseBody '{...}'")
    s.add_argument("method")
    s.add_argument("params", nargs="?")
    s.add_argument("--events", type=float, help="also collect events for N seconds")
    return p


COMMANDS = {
    "status": cmd_status, "net": cmd_net, "req": cmd_req, "body": cmd_body, "logs": cmd_logs,
    "sessions": cmd_sessions,
    "mock": cmd_mock, "kv": cmd_kv, "db": cmd_db, "ui": cmd_ui, "har": cmd_har, "cdp": cmd_cdp,
}


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        if args.cmd == "targets":
            cmd_targets(args)
            return 0
        cdp = Cdp(args.serial, args.package, args.timeout)
        try:
            COMMANDS[args.cmd](cdp, args)
        finally:
            cdp.close()
        return 0
    except LumenError as e:
        print("lumen: %s" % e, file=sys.stderr)
        return 1
    except BrokenPipeError:
        # `lumen.py logs | head`: the reader left. The forward is already removed (finally).
        os.dup2(os.open(os.devnull, os.O_WRONLY), sys.stdout.fileno())
        return 0
    except KeyboardInterrupt:
        return 130
    except (OSError, ValueError) as e:  # timeouts, resets, bad --since / JSON params
        print("lumen: %s: %s" % (type(e).__name__, e), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
