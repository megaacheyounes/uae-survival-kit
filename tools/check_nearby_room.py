"""Verify a fresh, disposable Trusted pair invitation against a running host."""
import base64
import hashlib
import hmac
import getpass
from urllib.parse import parse_qs, urlsplit
import json
import socket
import ssl
import struct
import time
import uuid


class Guest:
    def __init__(self, address, name):
        self.address = address
        self.name = name
        self.token = str(uuid.uuid4()) + str(uuid.uuid4())
        self.pin = None

    def send(self, packet_type, **data):
        raw = json.dumps(dict(type=packet_type, **data)).encode()
        self.conn.sendall(struct.pack(">I", len(raw)) + raw)

    def receive(self):
        def read(size):
            buf = bytearray()
            while len(buf) < size:
                chunk = self.conn.recv(size - len(buf))
                if not chunk:
                    raise EOFError("Room connection closed")
                buf.extend(chunk)
            return bytes(buf)
        size, = struct.unpack(">I", read(4))
        assert 0 < size <= 540000
        return json.loads(read(size))

    def until(self, kind, **expected):
        deadline = time.monotonic() + 17
        while time.monotonic() < deadline:
            msg = self.receive()
            if msg["type"] == "ping":
                self.send("ping")
            if msg["type"] == kind and all(msg.get(k) == v for k, v in expected.items()):
                return msg
        raise AssertionError(f"Timed out awaiting {kind}")

    def enter(self, secret):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        self.conn = ctx.wrap_socket(socket.create_connection(self.address, timeout=12))
        self.conn.settimeout(12)
        pin = hashlib.sha256(self.conn.getpeercert(binary_form=True)).hexdigest()
        hello = self.until("hello")
        nonce = str(uuid.uuid4())

        def proof(role):
            fields = ("FieldPair1", role, pin, hello["room"], hello["challenge"], nonce)
            digest = hmac.digest(secret.encode(), "|".join(fields).encode(), "sha256")
            return base64.b64encode(digest).decode()

        if self.pin:
            assert (self.pin, self.room) == (pin, hello["room"])
        self.send("knock", name=self.name, nonce=nonce, proof=proof("guest"), **{"return": bool(self.pin)})
        if not self.pin:
            assert hmac.compare_digest(self.until("pair")["proof"], proof("host"))
        self.send("join", token=self.token)
        self.until("welcome")
        self.pin, self.room = pin, hello["room"]

    def close(self):
        if hasattr(self, "conn"):
            self.conn.close()


def exercise(address, secret):
    def rejected(code):
        other = Guest(address, "Other phone")
        try:
            other.enter(code)
        except (EOFError, ConnectionResetError):
            return
        finally:
            other.close()
        raise AssertionError("Host accepted an unauthorized guest")

    rejected(("3" if secret[0] == "2" else "2") + secret[1:])
    guest = Guest(address, "Paired phone")
    try:
        guest.enter(secret)
        rejected(secret)
        msg = str(uuid.uuid4())
        guest.send("send", id=msg, kind="text", body="Private delivery check")
        guest.until("post", id=msg, got=1)
        guest.send("floor")
        guest.until("grant")
        guest.send("stop")
        guest.close()
        guest.enter(secret)
        guest.until("post", id=msg)
        print("PASS bad code rejected, one-use admission, private receipt, voice floor and pinned reconnect")
    finally:
        guest.close()


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host")
    parser.add_argument("port", type=int)
    args = parser.parse_args()
    invitation = urlsplit(getpass.getpass("Decoded private invitation QR (uae-room://pair/...): ").strip())
    if invitation.scheme != "uae-room" or invitation.netloc != "pair":
        parser.error("This protocol check uses the private invitation QR.")
    exercise((args.host, args.port), parse_qs(invitation.query)["code"][0])
