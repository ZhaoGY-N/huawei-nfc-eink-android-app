#!/usr/bin/env python3
"""Bootstrap the Android toolchain, run offline regressions and build an unsigned APK.

Required environment variables:
  JAVA_HOME    JDK 17+
  ANDROID_HOME Android SDK installation root (may be empty; SDK packages are installed here)

Python 3.9+ is the only host-side scripting requirement. No Gradle is used.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import platform
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ANDROID_PLATFORM = "android-34"
BUILD_TOOLS = "34.0.0"
NDK_VERSION = "27.2.12479018"
SHIZUKU_VERSION = "13.1.5"
SHIZUKU_ARTIFACTS = ("api", "provider", "aidl", "shared")
SDK_REPOSITORY_XML = "https://dl.google.com/android/repository/repository2-3.xml"

ROOT = Path(__file__).resolve().parent
NATIVE_SOURCES = (
    "native/build.sh", "native/nci_rewrite.h", "native/hal_discovery.h",
    "native/hal_name.h", "native/hook_debug.h", "native/owned_hook.h",
    "native/qpoll_hook.c", "native/qpoll_inject.c",
)
NATIVE_OUTPUTS = (
    "assets/native/arm64-v8a/libqpollhook.so",
    "assets/native/arm64-v8a/qpoll-inject",
)


def fail(message: str) -> None:
    raise RuntimeError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def require_env_path(name: str) -> Path:
    raw = os.environ.get(name, "").strip().strip('"')
    if not raw:
        fail(f"缺少环境变量 {name}。请先设置 {name} 后再运行 build.bat / build.py")
    path = Path(os.path.expandvars(os.path.expanduser(raw))).resolve()
    if name == "JAVA_HOME":
        java = path / "bin" / ("java.exe" if os.name == "nt" else "java")
        javac = path / "bin" / ("javac.exe" if os.name == "nt" else "javac")
        if not java.is_file() or not javac.is_file():
            fail(f"{name} 必须指向完整 JDK（需要 bin/java 和 bin/javac）：{path}")
    return path


def java_tool(jdk: Path, name: str) -> Path:
    suffix = ".exe" if os.name == "nt" else ""
    path = jdk / "bin" / (name + suffix)
    if not path.is_file() and os.name == "nt":
        path = jdk / "bin" / name
    if not path.is_file():
        fail(f"JDK 工具不存在：{path}")
    return path


def command_for(executable: Path, args: list[str]) -> list[str]:
    """Build a direct process command for native executables."""
    return [str(executable), *[str(arg) for arg in args]]


def run(executable: Path, *args: object, cwd: Path = ROOT, env: dict[str, str] | None = None,
        input_text: str | None = None) -> None:
    arguments = [str(arg) for arg in args]
    if os.name == "nt" and executable.suffix.lower() in (".bat", ".cmd"):
        # Batch files are parsed by cmd.exe (not by the Windows C runtime). Let
        # Python invoke the shell and quote every token, rather than embedding a
        # pre-escaped command line as a single cmd.exe argument.
        def cmd_quote(value: str) -> str:
            if '"' in value or "\r" in value or "\n" in value:
                fail("Windows 批处理参数包含不支持的引号或换行")
            return '"' + value + '"'
        command = " ".join(cmd_quote(value) for value in [str(executable), *arguments])
        print("+", command, flush=True)
        subprocess.run(command, cwd=str(cwd), env=env, check=True, shell=True,
                       input=None if input_text is None else input_text.encode("utf-8"))
        return
    cmd = command_for(executable, arguments)
    print("+", subprocess.list2cmdline(cmd) if os.name == "nt" else " ".join(cmd), flush=True)
    subprocess.run(cmd, cwd=str(cwd), env=env, check=True,
                   input=None if input_text is None else input_text.encode("utf-8"))


def http_download(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    temp = destination.with_suffix(destination.suffix + ".part")
    print(f"下载：{url}", flush=True)
    try:
        if os.name == "nt":
            # The Python embeddable distribution has no pip/certifi payload.
            # Use Windows PowerShell + the Windows trust store for HTTPS, so a
            # clean bootstrap does not depend on an OpenSSL CA bundle being present.
            script = "\n".join((
                "param([string]$Url, [string]$OutFile)",
                "$ErrorActionPreference = 'Stop'",
                "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12",
                "Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $OutFile",
            ))
            with tempfile.TemporaryDirectory(prefix="qpoll-download-") as temp_dir:
                ps1 = Path(temp_dir) / "download.ps1"
                ps1.write_text(script, encoding="utf-8")
                powershell = shutil.which("powershell.exe") or shutil.which("powershell")
                if not powershell:
                    fail("Windows PowerShell 未找到，无法下载 Android 构建依赖")
                subprocess.run([powershell, "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass",
                                "-File", str(ps1), url, str(temp)], check=True)
        else:
            request = urllib.request.Request(url, headers={"User-Agent": "QPollLab-Build/2.19"})
            with urllib.request.urlopen(request, timeout=90) as response, temp.open("wb") as out:
                total = int(response.headers.get("Content-Length", "0") or 0)
                received = 0
                while True:
                    block = response.read(1024 * 1024)
                    if not block:
                        break
                    out.write(block)
                    received += len(block)
                    if total:
                        print(f"  {received * 100 // total}% ({received // (1024 * 1024)} / {total // (1024 * 1024)} MiB)", end="\r", flush=True)
            if total:
                print()
        if not temp.is_file() or temp.stat().st_size == 0:
            fail(f"下載結果為空：{url}")
        temp.replace(destination)
    except Exception:
        try:
            temp.unlink()
        except OSError:
            pass
        raise


def local_tag(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def sdkmanager_archive_url() -> str:
    repository_file = ROOT / "build" / "sdk-repository.xml"
    http_download(SDK_REPOSITORY_XML, repository_file)
    repository = ET.parse(repository_file).getroot()
    os_name = "windows" if os.name == "nt" else "linux" if sys.platform.startswith("linux") else "macosx"
    for package in repository.iter():
        if local_tag(package.tag) != "remotePackage" or package.attrib.get("path") != "cmdline-tools;latest":
            continue
        for archive in package.iter():
            if local_tag(archive.tag) != "archive":
                continue
            host = next((node.text for node in archive if local_tag(node.tag) == "host-os"), None)
            if host != os_name:
                continue
            for complete in (node for node in archive if local_tag(node.tag) == "complete"):
                url = next((node.text for node in complete.iter() if local_tag(node.tag) == "url"), None)
                if url:
                    return url if url.startswith("http") else "https://dl.google.com/android/repository/" + url
    fail(f"无法从 Google SDK 仓库元数据中找到当前主机 ({os_name}) 的 cmdline-tools;latest 下载地址")


def safe_extract(zip_path: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    base = destination.resolve()
    with zipfile.ZipFile(zip_path) as archive:
        for info in archive.infolist():
            target = (destination / info.filename).resolve()
            if not target.is_relative_to(base):
                fail("SDK 工具压缩包包含越界路径，已拒绝解压")
        archive.extractall(destination)


def locate_sdkmanager(sdk: Path) -> Path | None:
    bin_name = "sdkmanager.bat" if os.name == "nt" else "sdkmanager"
    locations = sorted((sdk / "cmdline-tools").glob("*/bin/" + bin_name), reverse=True)
    if locations:
        return locations[0]
    legacy = sdk / "tools" / "bin" / bin_name
    return legacy if legacy.is_file() else None


def bootstrap_sdkmanager(sdk: Path, env: dict[str, str]) -> Path:
    existing = locate_sdkmanager(sdk)
    if existing:
        return existing
    print("Android command-line tools 未找到，正在从 Google SDK 仓库下载并安装…", flush=True)
    archive_url = sdkmanager_archive_url()
    temp_root = Path(tempfile.mkdtemp(prefix="qpoll-cmdline-tools-", dir=str(ROOT / "build")))
    try:
        archive_file = temp_root / "cmdline-tools.zip"
        http_download(archive_url, archive_file)
        extracted = temp_root / "extract"
        safe_extract(archive_file, extracted)
        candidates = list(extracted.rglob("sdkmanager.bat" if os.name == "nt" else "sdkmanager"))
        if not candidates:
            fail("下载的 command-line-tools 压缩包中未找到 sdkmanager")
        sdkmanager = candidates[0]
        bin_dir = sdkmanager.parent
        package_root = bin_dir.parent
        # Official archives have cmdline-tools/{bin,lib}; accommodate a wrapper folder as well.
        if not (package_root / "lib").exists() and package_root.parent.name == "cmdline-tools":
            package_root = package_root.parent
        destination = sdk / "cmdline-tools" / "latest"
        destination.parent.mkdir(parents=True, exist_ok=True)
        staging = destination.with_name("latest.new")
        if staging.exists():
            shutil.rmtree(staging)
        shutil.copytree(package_root, staging)
        if destination.exists():
            shutil.rmtree(destination)
        staging.replace(destination)
    finally:
        shutil.rmtree(temp_root, ignore_errors=True)
    result = locate_sdkmanager(sdk)
    if not result:
        fail("Android command-line-tools 安装后仍找不到 sdkmanager")
    return result


def ensure_android_toolchain(sdk: Path, env: dict[str, str]) -> tuple[Path, Path, Path]:
    build_root = ROOT / "build"
    build_root.mkdir(parents=True, exist_ok=True)
    sdkmanager = bootstrap_sdkmanager(sdk, env)
    packages = [f"platforms;{ANDROID_PLATFORM}", f"build-tools;{BUILD_TOOLS}", f"ndk;{NDK_VERSION}"]
    print("检查 Android SDK 组件许可；如首次安装，请在终端逐项阅读并接受…", flush=True)
    run(sdkmanager, f"--sdk_root={sdk}", "--licenses", cwd=ROOT, env=env)
    print("检查 / 安装 Android 平台、Build Tools 与 NDK…", flush=True)
    run(sdkmanager, f"--sdk_root={sdk}", "--install", *packages, cwd=ROOT, env=env)

    platform_jar = sdk / "platforms" / ANDROID_PLATFORM / "android.jar"
    build_tools = sdk / "build-tools" / BUILD_TOOLS
    ndk = sdk / "ndk" / NDK_VERSION
    required = [platform_jar, build_tools / ("aapt.exe" if os.name == "nt" else "aapt"),
                build_tools / ("zipalign.exe" if os.name == "nt" else "zipalign"),
                build_tools / ("d8.bat" if os.name == "nt" else "d8")]
    missing = [str(path) for path in required if not path.is_file()]
    if not ndk.is_dir():
        missing.append(str(ndk))
    if missing:
        fail("SDK 安装完成后仍缺少构建组件：\n  " + "\n  ".join(missing))
    return platform_jar, build_tools, ndk


def ensure_shizuku_dependencies() -> list[Path]:
    libs = ROOT / "libs"
    libs.mkdir(exist_ok=True)
    jars: list[Path] = []
    for artifact in SHIZUKU_ARTIFACTS:
        aar = libs / f"{artifact}-{SHIZUKU_VERSION}.aar"
        if not aar.is_file():
            urls = (
                f"https://repo.maven.apache.org/maven2/dev/rikka/shizuku/{artifact}/{SHIZUKU_VERSION}/{artifact}-{SHIZUKU_VERSION}.aar",
                f"https://maven.rikka.app/releases/dev/rikka/shizuku/{artifact}/{SHIZUKU_VERSION}/{artifact}-{SHIZUKU_VERSION}.aar",
            )
            last_error: Exception | None = None
            for url in urls:
                try:
                    http_download(url, aar)
                    break
                except Exception as exc:
                    last_error = exc
            else:
                fail(f"无法下载 Shizuku {artifact} {SHIZUKU_VERSION} AAR：{last_error}")
        try:
            with zipfile.ZipFile(aar) as archive:
                if "classes.jar" not in archive.namelist():
                    fail(f"Shizuku AAR 缺少 classes.jar：{aar}")
        except zipfile.BadZipFile:
            fail(f"Shizuku AAR 不是有效 ZIP：{aar}")
        jars.append(aar)
    return jars


def native_clang(ndk: Path) -> Path:
    # Use the NDK target wrapper rather than raw clang: it supplies the matching
    # Android sysroot, API level and linker search paths on every host OS.
    host = platform.system().lower()
    if host == "windows":
        tag, suffix = "windows-x86_64", ".cmd"
    elif host == "linux":
        tag, suffix = "linux-x86_64", ""
    elif host == "darwin":
        tag, suffix = "darwin-x86_64", ""
    else:
        fail(f"暂不支持在 {platform.system()} 主机上构建 Android 原生组件")
    clang = ndk / "toolchains" / "llvm" / "prebuilt" / tag / "bin" / ("aarch64-linux-android26-clang" + suffix)
    if not clang.is_file():
        fail(f"NDK Android API 26 目标 Clang wrapper 未找到：{clang}")
    return clang


def build_native(ndk: Path) -> None:
    clang = native_clang(ndk)
    output_dir = ROOT / "assets" / "native" / "arm64-v8a"
    output_dir.mkdir(parents=True, exist_ok=True)
    shared = output_dir / "libqpollhook.so"
    injector = output_dir / "qpoll-inject"
    common = [
        "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
        "-mbranch-protection=bti", "-Wl,-z,relro,-z,now",
    ]
    run(clang, *common, "-fPIC", "-fno-builtin", "-fvisibility=hidden", "-mno-outline-atomics",
        "-shared", "-o", shared, ROOT / "native" / "qpoll_hook.c", "-ldl", cwd=ROOT)
    run(clang, *common, "-o", injector, ROOT / "native" / "qpoll_inject.c", "-ldl", cwd=ROOT)
    try:
        injector.chmod(0o755)
    except OSError:
        pass

    inputs = [*NATIVE_SOURCES, *NATIVE_OUTPUTS]
    lines = []
    for relative in inputs:
        path = ROOT / relative
        if not path.is_file():
            fail(f"原生构建文件缺失：{relative}")
        lines.append(f"{sha256(path)}  {relative}")
    manifest = ROOT / "assets" / "native" / "build.sha256"
    manifest.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Native arm64 assets: {shared} / {injector}", flush=True)


def extract_aar_jars(aars: list[Path], work: Path) -> list[Path]:
    result: list[Path] = []
    for aar in aars:
        jar = work / (aar.stem + ".jar")
        with zipfile.ZipFile(aar) as archive:
            jar.write_bytes(archive.read("classes.jar"))
        result.append(jar)
    return result


def verify_native_manifest() -> None:
    manifest = ROOT / "assets" / "native" / "build.sha256"
    if not manifest.is_file():
        fail("原生组件清单不存在")
    expected_set = set(NATIVE_SOURCES) | set(NATIVE_OUTPUTS)
    parsed: set[str] = set()
    for line in manifest.read_text(encoding="utf-8").splitlines():
        parts = line.split(maxsplit=1)
        if len(parts) != 2:
            fail("原生组件清单格式错误")
        expected, relative = parts
        if relative in parsed:
            fail("原生组件清单包含重复路径：" + relative)
        parsed.add(relative)
        path = (ROOT / relative).resolve()
        if not path.is_relative_to(ROOT) or not path.is_file() or sha256(path) != expected:
            fail("原生组件文件缺失或哈希不匹配：" + relative)
    if parsed != expected_set:
        fail("原生组件清单不完整；请重新构建 native 组件")


def run_java_regressions(jdk: Path, env: dict[str, str]) -> None:
    runner = ROOT / "tests" / "run_java.py"
    print("运行 Java 离线回归…", flush=True)
    subprocess.run([sys.executable, str(runner)], cwd=str(ROOT), env=env, check=True)


def build_unsigned_apk(jdk: Path, sdk: Path, platform_jar: Path, build_tools: Path,
                       aar_files: list[Path], env: dict[str, str]) -> Path:
    build_root = ROOT / "build"
    dist = ROOT / "dist"
    build_root.mkdir(parents=True, exist_ok=True)
    dist.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="unsigned-", dir=str(build_root)))
    try:
        classes = work / "classes"
        dex = work / "dex"
        classes.mkdir()
        dex.mkdir()
        dependency_jars = extract_aar_jars(aar_files, work)
        sources = sorted((ROOT / "src").rglob("*.java"))
        if not sources:
            fail("src/ 下没有 Java 源文件")
        classpath = os.pathsep.join(str(path) for path in [platform_jar, *dependency_jars])
        javac = java_tool(jdk, "javac")
        jar_tool = java_tool(jdk, "jar")
        run(javac, "-encoding", "UTF-8", "--release", "8", "-classpath", classpath,
            "-d", classes, *sources, cwd=ROOT, env=env)
        classes_jar = work / "classes.jar"
        run(jar_tool, "cf", classes_jar, "-C", classes, ".", cwd=ROOT, env=env)

        d8 = build_tools / ("d8.bat" if os.name == "nt" else "d8")
        run(d8, "--min-api", "26", "--lib", platform_jar, "--output", dex,
            classes_jar, *dependency_jars, cwd=ROOT, env=env)

        aapt = build_tools / ("aapt.exe" if os.name == "nt" else "aapt")
        zipalign = build_tools / ("zipalign.exe" if os.name == "nt" else "zipalign")
        raw_apk = work / "resources-and-dex.apk"
        run(aapt, "package", "-f", "-M", ROOT / "AndroidManifest.xml", "-S", ROOT / "res",
            "-A", ROOT / "assets", "-I", platform_jar, "-F", raw_apk, cwd=ROOT, env=env)
        dex_files = list(dex.glob("classes*.dex"))
        if not dex_files or not (dex / "classes.dex").is_file():
            fail("D8 没有生成 classes.dex")
        def dex_sort_key(path: Path) -> int:
            suffix = path.stem[len("classes"):]
            return 1 if not suffix else int(suffix)
        dex_files.sort(key=dex_sort_key)
        with zipfile.ZipFile(raw_apk, "a", compression=zipfile.ZIP_DEFLATED) as archive:
            for dex_file in dex_files:
                archive.write(dex_file, dex_file.name)
        version = ET.parse(ROOT / "AndroidManifest.xml").getroot().attrib[
            "{http://schemas.android.com/apk/res/android}versionName"]
        apk_name = f"QPollLab-{version}-unsigned.apk"
        final_apk = dist / apk_name
        aligned_tmp = work / "aligned-unsigned.apk"
        run(zipalign, "-f", "4", raw_apk, aligned_tmp, cwd=ROOT, env=env)
        run(zipalign, "-c", "-v", "4", aligned_tmp, cwd=ROOT, env=env)
        with zipfile.ZipFile(aligned_tmp) as archive:
            bad_member = archive.testzip()
            if bad_member:
                fail("APK ZIP 完整性检查失败：" + bad_member)
            apk_members = set(archive.namelist())
            if "classes.dex" not in apk_members:
                fail("最终 APK 缺少 classes.dex")
            if not any(name.startswith("classes") and name.endswith(".dex") for name in apk_members):
                fail("最终 APK 缺少 DEX 文件")
            signature_entries = [name for name in archive.namelist()
                                 if name.upper().startswith("META-INF/") and name.upper().endswith((".RSA", ".DSA", ".EC", ".SF"))]
            if signature_entries:
                fail("目标应为未签名包，但发现签名文件：" + ", ".join(signature_entries))
        shutil.copy2(aligned_tmp, final_apk)
        run(aapt, "dump", "badging", final_apk, cwd=ROOT, env=env)
        digest = sha256(final_apk)
        (dist / (apk_name + ".sha256")).write_text(f"{digest}  {apk_name}\n", encoding="utf-8")
        print("\nUNSIGNED APK:", final_apk)
        print("SHA256:", digest)
        print("注意：这是未签名 APK；安装或覆盖安装前必须由你自己的签名密钥签名。")
        return final_apk
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="自动下载 Android 编译工具并生成未签名 APK")
    parser.add_argument("--skip-tests", action="store_true", help="跳过 Java 离线回归")
    args = parser.parse_args()

    if sys.version_info < (3, 9):
        fail("需要 Python 3.9 或更高版本")
    jdk = require_env_path("JAVA_HOME")
    sdk = require_env_path("ANDROID_HOME")
    sdk.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    env["JAVA_HOME"] = str(jdk)
    env["ANDROID_HOME"] = str(sdk)
    env["ANDROID_SDK_ROOT"] = str(sdk)
    env["PATH"] = str(jdk / "bin") + os.pathsep + env.get("PATH", "")

    java_version = subprocess.run([str(java_tool(jdk, "java")), "-version"],
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, env=env, check=True).stdout
    print(java_version.strip(), flush=True)
    import re
    version_match = re.search(r'version "(?:1\.)?(\d+)', java_version)
    if not version_match or int(version_match.group(1)) < 17:
        fail("需要 JDK 17 或更高版本；当前 JAVA_HOME 不是受支持的 JDK：" + java_version.strip())

    # Keep the Java-only tests usable even on machines with no Android SDK yet.
    if not args.skip_tests:
        run_java_regressions(jdk, env)

    aar_files = ensure_shizuku_dependencies()
    platform_jar, build_tools, ndk = ensure_android_toolchain(sdk, env)
    build_native(ndk)
    verify_native_manifest()
    build_unsigned_apk(jdk, sdk, platform_jar, build_tools, aar_files, env)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        print(f"\n构建失败：命令退出码 {error.returncode}", file=sys.stderr)
        raise SystemExit(error.returncode or 1)
    except Exception as error:
        print(f"\n构建失败：{error}", file=sys.stderr)
        raise SystemExit(1)
