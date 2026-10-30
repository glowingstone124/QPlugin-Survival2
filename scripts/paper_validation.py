#!/usr/bin/env python3
"""Local-only Paper probe client. Uses the private validation.json created for the test server.

The QHotProbe plugin is test support, never a production dependency. No credentials are printed.
"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import struct
import time


def serve_api(server):
    """QAPI stand-in for the isolated server; never forwards a request or records credentials."""
    config = json.loads((Path(server) / "validation.json").read_text())

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            route = self.path.split("?", 1)[0]
            if route == "/qo/msglist/download": value = {"messages": []}
            elif route == "/qo/download/registry": value = {"code": 0, "qq": 0, "score": 12, "frozen": False}
            elif route == "/qo/authorization/auto-login": value = {"ok": False}
            elif route == "/qo/download/logingreeting": value = {"time": {"time": 0}, "online": [{"id": 1, "players": []}, {"id": 4, "players": []}]}
            elif route == "/qo/download/ip": value = True
            elif route.endswith("/download"): value = {}
            else: value = {"ok": True, "result": True}
            body = json.dumps(value).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

        def do_POST(self):
            self.rfile.read(int(self.headers.get("Content-Length", "0")))
            self.do_GET()

    with ThreadingHTTPServer(("127.0.0.1", config["ports"]["api"]), Handler) as http:
        print("Local QAPI stub listening on loopback", flush=True)
        http.serve_forever()


class Rcon:
    def __init__(self, server):
        config = json.loads((Path(server) / "validation.json").read_text())
        self.socket = socket.create_connection(("127.0.0.1", config["ports"]["rcon"]), timeout=10)
        self.sequence = 0
        self.send(3, config["rcon_password"])
        request, kind, _ = self.receive()
        if kind == 0:
            request, kind, _ = self.receive()
        if request == -1 or kind != 2:
            raise RuntimeError("Local RCON authentication failed")

    def read(self, size):
        result = b""
        while len(result) < size:
            block = self.socket.recv(size - len(result))
            if not block:
                raise EOFError("RCON connection closed")
            result += block
        return result

    def send(self, kind, text):
        self.sequence += 1
        body = struct.pack("<ii", self.sequence, kind) + text.encode() + b"\0\0"
        self.socket.sendall(struct.pack("<i", len(body)) + body)
        return self.sequence

    def receive(self):
        size, = struct.unpack("<i", self.read(4))
        if not 10 <= size <= 4 * 1024 * 1024:
            raise RuntimeError("Invalid RCON packet size")
        body = self.read(size)
        return (*struct.unpack("<ii", body[:8]), body[8:-2].decode())

    def command(self, command):
        request = self.send(2, command)
        received, _, text = self.receive()
        if received != request:
            raise RuntimeError("Unexpected RCON response")
        return text.strip()

    def probe(self, command):
        value = json.loads(self.command("qprobe " + command).splitlines()[-1])
        if "error" in value:
            raise RuntimeError(value["error"])
        return value

    def close(self):
        self.socket.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


def sample(server, seconds):
    with Rcon(server) as client:
        client.probe("tick-reset")
        start = client.probe("resources")
        time.sleep(seconds)
        end = client.probe("resources")
        ticks = client.probe("ticks")
    elapsed = (end["nanoTime"] - start["nanoTime"]) / 1e9
    return {"seconds": elapsed,
            "cpuPercentOfOneCore": 100 * (end["processCpuNanos"] - start["processCpuNanos"]) / 1e9 / elapsed,
            "ticksMillis": ticks, "start": start, "end": end}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-dir", type=Path, required=True)
    parser.add_argument("--sample-seconds", type=float)
    parser.add_argument("--serve-api", action="store_true")
    parser.add_argument("command", nargs="*", default=[])
    args = parser.parse_args()
    if args.serve_api:
        serve_api(args.server_dir)
    elif args.sample_seconds:
        print(json.dumps(sample(args.server_dir, args.sample_seconds), ensure_ascii=False, indent=2))
    else:
        with Rcon(args.server_dir) as client:
            print(client.command(" ".join(args.command) or "qprobe resources"))


if __name__ == "__main__":
    main()
