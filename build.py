#!/usr/bin/env python3
"""Build with the installed SDK/JDK; no Gradle, downloads, or third-party app libs."""
import hashlib
import os
from pathlib import Path
import subprocess
import zipfile
import tempfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent
sdk = Path(os.environ["ANDROID_HOME"])
jdk = Path(os.environ["JAVA_HOME"])
bt = sdk / "build-tools/34.0.0"
android = sdk / "platforms/android-34/android.jar"
build_root = root / "build"
build_root.mkdir(parents=True, exist_ok=True)
build = Path(tempfile.mkdtemp(prefix="v2-", dir=build_root))
dist = root / "dist"
for directory in (build / "classes", build / "dex", dist):
    directory.mkdir(parents=True, exist_ok=True)
env = dict(os.environ, JAVA_HOME=str(jdk), PATH=str(jdk / "bin") + os.pathsep + os.environ.get("PATH", ""))
if os.environ.get("ANDROID_NDK_HOME"):
    subprocess.run(["sh", str(root / "native/build.sh")], cwd=root, env=env, check=True)
native_manifest = root / "assets/native/build.sha256"
if not native_manifest.exists():
    raise RuntimeError("Build native assets first: ANDROID_NDK_HOME=... sh native/build.sh")
native_inputs = {"native/build.sh", "native/nci_rewrite.h", "native/hal_discovery.h", "native/hal_name.h", "native/hook_debug.h", "native/owned_hook.h", "native/qpoll_hook.c", "native/qpoll_inject.c",
                 "assets/native/arm64-v8a/libqpollhook.so", "assets/native/arm64-v8a/qpoll-inject"}
manifest_lines = native_manifest.read_text().splitlines()
if {line.split(maxsplit=1)[1] for line in manifest_lines} != native_inputs:
    raise RuntimeError("Incomplete native manifest; rebuild native/build.sh")
for line in manifest_lines:
    expected, relative = line.split(maxsplit=1)
    source = (root / relative).resolve()
    if not source.is_relative_to(root) or hashlib.sha256(source.read_bytes()).hexdigest() != expected:
        raise RuntimeError("Native assets are stale; rebuild native/build.sh: " + relative)
libraries = []
for aar in sorted((root / "libs").glob("*-13.1.5.aar")):
    jar = build / (aar.stem + ".jar")
    with zipfile.ZipFile(aar) as archive:
        jar.write_bytes(archive.read("classes.jar"))
    libraries.append(jar)
if len(libraries) != 4:
    raise RuntimeError("Expected pinned Shizuku api/provider/aidl/shared 13.1.5 AARs in libs/")

def run(*args, cwd=root):
    subprocess.run([str(arg) for arg in args], cwd=cwd, env=env, check=True)

run(jdk / "bin/javac", "-encoding", "UTF-8", "--release", "8",
    "-classpath", os.pathsep.join(str(p) for p in [android, *libraries]), "-d", build / "classes", *sorted((root / "src").rglob("*.java")))
run(jdk / "bin/jar", "cf", build / "classes.jar", "-C", build / "classes", ".")
run(bt / "d8", "--min-api", "26", "--lib", android, "--output", build / "dex", build / "classes.jar", *libraries)
run(bt / "aapt", "package", "-f", "-M", root / "AndroidManifest.xml", "-S", root / "res",
    "-A", root / "assets", "-I", android, "-F", build / "unsigned.apk")
with zipfile.ZipFile(build / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as archive:
    archive.write(build / "dex/classes.dex", "classes.dex")
run(bt / "zipalign", "-f", "4", build / "unsigned.apk", build / "aligned.apk")
key = build_root / "debug.keystore"
if not key.exists():
    run(jdk / "bin/keytool", "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android",
        "-alias", "qpolllab", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-dname", "CN=QPoll Lab Local Debug", "-noprompt")
version = ET.parse(root / "AndroidManifest.xml").getroot().attrib["{http://schemas.android.com/apk/res/android}versionName"]
apk = dist / ("QPollLab-" + version + ".apk")
run(bt / "apksigner", "sign", "--ks", key, "--ks-key-alias", "qpolllab", "--ks-pass", "pass:android",
    "--key-pass", "pass:android", "--out", apk, build / "aligned.apk")
run(bt / "apksigner", "verify", "--verbose", apk)
run(bt / "aapt", "dump", "badging", apk)
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
(dist / (apk.name + ".sha256")).write_text(digest + "  " + apk.name + "\n", encoding="utf-8")
print("APK:", apk)
print("SHA256:", digest)
