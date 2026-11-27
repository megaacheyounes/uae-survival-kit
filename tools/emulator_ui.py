"""Read/tap the workspace emulator UI. Never selects a USB device."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
import os

ROOT = Path(__file__).resolve().parents[1]
ADB = Path(os.environ.get("ANDROID_HOME",r"D:\android\sdk")) / "platform-tools" / "adb.exe"


def adb(*args, **kwargs):
    return subprocess.run([str(ADB),"-s","emulator-5580",*args],check=True,timeout=60,**kwargs)


def nodes():
    for _ in range(5):
        raw=adb("exec-out","uiautomator","dump","/dev/tty",capture_output=True).stdout
        start=raw.find(b"<hierarchy")
        end=raw.rfind(b"</hierarchy>")
        if 0 <= start < end:
            return list(ET.fromstring(raw[start:end+len(b"</hierarchy>")]).iter("node"))
        time.sleep(.4)
    raise RuntimeError("The emulator did not expose a current UI hierarchy.")


def find(label, *, partial=False, timeout=18):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        for n in nodes():
            labels=[n.get(x,"") for x in ("text","content-desc","resource-id")]
            if any((label in s if partial else label==s) for s in labels):
                return n
        time.sleep(.4)
    raise ValueError(f"Could not find {label!r}")


def tap(label,partial=False):
    n=find(label,partial=partial)
    x,y,r,b=map(int,re.findall(r"\d+",n.get("bounds")))
    adb("shell","input","tap",str((x+r)//2),str((y+b)//2))


def show():
    for n in nodes():
        label=n.get("text") or n.get("content-desc")
        if label:
            print(label,"|",n.get("bounds"))


def fixture_pdf(path):
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>", b"<< /Type /Pages /Count 2 /Kids [3 0 R 4 0 R] >>"]
    for content in (5,6):
        objects.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Resources << /Font << /F1 7 0 R >> >> /Contents {content} 0 R >>".encode())
    for index in (1,2):
        text=f"BT /F1 20 Tf 40 300 Td (Field guide QA page {index}) Tj ET".encode()
        objects.append(f"<< /Length {len(text)} >>\nstream\n".encode()+text+b"\nendstream")
    objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    doc=bytearray(b"%PDF-1.4\n")
    offsets=[0]
    for i,obj in enumerate(objects,1):
        offsets.append(len(doc))
        doc.extend(f"{i} 0 obj\n".encode()+obj+b"\nendobj\n")
    pos=len(doc)
    doc.extend(f"xref\n0 {len(offsets)}\n0000000000 65535 f \n".encode())
    for offset in offsets[1:]:
        doc.extend(f"{offset:010d} 00000 n \n".encode())
    doc.extend(f"trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{pos}\n%%EOF\n".encode())
    path.write_bytes(doc)


if __name__=="__main__":
    if len(sys.argv)==1: show()
    elif sys.argv[1]=="fixture": fixture_pdf(ROOT/".build"/"QA-Manual.pdf")
    elif sys.argv[1]=="tap": tap(sys.argv[2])
    elif sys.argv[1]=="shot":
        (ROOT/".build"/"emulator-screen.png").write_bytes(adb("exec-out","screencap","-p",capture_output=True).stdout)
