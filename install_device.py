import hashlib
import http.server
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path


TV_IP = "10.0.0.44"
TV_ADB = f"{TV_IP}:5555"
PACKAGE = "com.example.tvparental"
APP_LABEL = "健康使用"
PORT = 8765
TIMEOUT_SECONDS = 300
ROOT = Path(__file__).resolve().parent
APK = ROOT / "app" / "build" / "outputs" / "parental" / "Parental-TV.apk"
METADATA = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "output-metadata.json"
PUSH_DIR = ROOT / ".tools" / "tv-parental-push"
PUSH_APK = PUSH_DIR / "TvParental.apk"


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *_args):
        pass


def adb_path():
    adb_names = ("adb.exe", "adb") if os.name == "nt" else ("adb", "adb.exe")
    sdk_dirs = []
    for name in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(name, "").strip()
        if value:
            sdk_dirs.append(Path(value).expanduser())

    properties_file = ROOT / "local.properties"
    try:
        properties = properties_file.read_text(encoding="utf-8-sig").splitlines()
    except OSError:
        properties = []
    for line in properties:
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if separator and key.strip() == "sdk.dir":
            value = value.strip().replace("\\:", ":").replace("\\ ", " ").replace("\\\\", "\\")
            if value:
                sdk_dirs.append(Path(value).expanduser())
            break

    if os.environ.get("LOCALAPPDATA"):
        sdk_dirs.append(Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk")
    elif os.name != "nt":
        sdk_dirs.append(Path.home() / ("Library/Android/sdk" if sys.platform == "darwin" else "Android/Sdk"))

    seen = set()
    for sdk_dir in sdk_dirs:
        key = os.path.normcase(os.path.abspath(sdk_dir))
        if key in seen:
            continue
        seen.add(key)
        for name in adb_names:
            candidate = sdk_dir / "platform-tools" / name
            if candidate.is_file():
                return str(candidate)

    found = shutil.which("adb")
    if not found:
        raise RuntimeError(
            "adb was not found. Install Android SDK Platform-Tools, set ANDROID_SDK_ROOT/ANDROID_HOME, "
            "open the project in Android Studio to create local.properties, or add adb to PATH."
        )
    return found


def run_adb(adb, *args):
    result = subprocess.run([adb, *args], text=True, capture_output=True, check=False)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or f"ADB command failed: {' '.join(args)}")
    return result.stdout.strip()


def send_message(sock, message):
    data = message.encode("utf-8")
    sock.sendall(struct.pack(">i", len(data)) + data)


def read_messages(buffer):
    messages = []
    while len(buffer) >= 4:
        size = struct.unpack(">i", buffer[:4])[0]
        if size < 0:
            raise RuntimeError("Invalid response length from the TV.")
        if len(buffer) < size + 4:
            break
        messages.append(buffer[4 : size + 4].decode("utf-8", "replace"))
        buffer = buffer[size + 4 :]
    return messages, buffer


def request_tcl_install(version_code):
    url = f"http://127.0.0.1:{PORT}/TvParental.apk"
    path_hash = hashlib.md5(url.encode("utf-8")).hexdigest()
    with socket.create_connection((TV_IP, 6553), timeout=5) as sock:
        sock.settimeout(1)
        send_message(sock, "159>>1>>0")
        send_message(
            sock,
            ">>".join(
                ["223", "INSTALL", PACKAGE, APP_LABEL, url, "2", str(version_code), "20", "", "", "", path_hash]
            ),
        )

        print("Accept the installation request on the TV when prompted.", flush=True)
        buffer = b""
        last_heartbeat = time.monotonic()
        deadline = last_heartbeat + TIMEOUT_SECONDS
        while time.monotonic() < deadline:
            try:
                chunk = sock.recv(65536)
                if not chunk:
                    raise RuntimeError("The TV closed the installation session.")
                buffer += chunk
            except socket.timeout:
                pass

            messages, buffer = read_messages(buffer)
            for message in messages:
                fields = message.split(">>")
                if len(fields) < 5 or fields[0] != "223" or fields[2] != PACKAGE:
                    continue
                if fields[1] == "INSTALL" and fields[4] == "confirm_pending":
                    print("The TV is waiting for confirmation.", flush=True)
                elif fields[1] == "INSTALL" and fields[4] == "denied":
                    raise RuntimeError("The installation request was refused on the TV.")
                elif fields[1] == "PROGRESS":
                    if fields[4] == "5":
                        print("Installed.", flush=True)
                        return
                    if fields[4] == "2":
                        raise RuntimeError("The TV could not download or install the APK.")

            if time.monotonic() - last_heartbeat > 5:
                send_message(sock, "150>>")
                last_heartbeat = time.monotonic()

    raise RuntimeError("Timed out waiting for the TV to finish installation.")


def main():
    if not APK.is_file():
        raise RuntimeError(f"APK not found: {APK}. Run build.bat first.")
    if not METADATA.is_file():
        raise RuntimeError(f"Build metadata not found: {METADATA}. Run build.bat first.")

    metadata = json.loads(METADATA.read_text(encoding="utf-8"))
    version_code = int(metadata["elements"][0]["versionCode"])
    PUSH_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copy2(APK, PUSH_APK)

    adb = adb_path()
    print(f"Connecting to TCL TV at {TV_ADB}...")
    run_adb(adb, "connect", TV_ADB)
    devices = run_adb(adb, "devices")
    if not any(line.split() == [TV_ADB, "device"] for line in devices.splitlines()):
        raise RuntimeError(f"TV is not ready for ADB. Current devices:\n{devices}")

    server = None
    reverse_ready = False
    try:
        run_adb(adb, "-s", TV_ADB, "reverse", f"tcp:{PORT}", f"tcp:{PORT}")
        reverse_ready = True
        handler = lambda *args, **kwargs: QuietHandler(*args, directory=str(PUSH_DIR), **kwargs)
        server = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), handler)
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()
        probe = urllib.request.Request(f"http://127.0.0.1:{PORT}/TvParental.apk", method="HEAD")
        with urllib.request.urlopen(probe, timeout=5) as response:
            if response.status != 200:
                raise RuntimeError("The local APK server did not return HTTP 200.")
        print(f"Installing versionCode {version_code} ({APP_LABEL})...")
        request_tcl_install(version_code)

        installed = run_adb(adb, "-s", TV_ADB, "shell", "dumpsys", "package", PACKAGE)
        if f"versionCode={version_code} " not in installed:
            raise RuntimeError(f"Installed versionCode {version_code} could not be verified on the TV.")
        print(f"Verified {PACKAGE} versionCode={version_code}.")
    finally:
        if server is not None:
            server.shutdown()
            server.server_close()
        if reverse_ready:
            subprocess.run([adb, "-s", TV_ADB, "reverse", "--remove", f"tcp:{PORT}"], capture_output=True)
        subprocess.run([adb, "disconnect", TV_ADB], capture_output=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
