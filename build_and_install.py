#!/usr/bin/env python3
"""Build the launcher (debug APK) and install it on the TV.

  python build_and_install.py              build + install + grant permission
  python build_and_install.py --set-home   ... and make it the default Home
  python build_and_install.py --no-build   install the last built APK
  python build_and_install.py --build-only just build apks/atvlauncher-debug.apk

The Android tools mis-handle non-ASCII paths on Windows, and build output doesn't
belong in a synced folder, so the sources are copied to BUILD_DIR first.
Override with SDK_DIR (or ANDROID_HOME) / BUILD_DIR / JAVA_HOME / GRADLE.
TV_HOST / TV_PORT pick the TV.
"""
import os
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "tools"))
import tv  # noqa: E402

ROOT = Path(__file__).resolve().parent
SDK = Path(os.environ.get("SDK_DIR") or os.environ.get("ANDROID_HOME") or Path.home() / "Android" / "Sdk")
BUILD = Path(os.environ.get("BUILD_DIR") or Path.home() / "tvhome-build")
JAVA_HOME = os.environ.get("JAVA_HOME", "")
PKG = "io.github.tvhomemodules.launcher"
HOME = PKG + "/.MainActivity"
APK_OUT = ROOT / "apks" / "atvlauncher-debug.apk"


def build():
    src = ROOT / "launcher"
    dst = BUILD / "launcher"
    dst.mkdir(parents=True, exist_ok=True)
    # Copy sources (keep the build cache in dst between runs)
    for item in ["settings.gradle", "build.gradle", "gradle.properties"]:
        shutil.copy2(src / item, dst / item)
    if (dst / "app" / "src").exists():
        shutil.rmtree(dst / "app" / "src")
    shutil.copytree(src / "app" / "src", dst / "app" / "src")
    shutil.copy2(src / "app" / "build.gradle", dst / "app" / "build.gradle")
    (dst / "local.properties").write_text("sdk.dir=" + str(SDK).replace("\\", "\\\\") + "\n")

    # Gradle 8.9: GRADLE env, else "gradle-8.9" next to the SDK, else "gradle" on PATH
    gradle = os.environ.get("GRADLE") or str(SDK / "gradle-8.9" / "bin" / ("gradle.bat" if os.name == "nt" else "gradle"))
    if not Path(gradle).exists():
        gradle = shutil.which("gradle") or sys.exit("Gradle 8.9 not found: set GRADLE or put gradle on PATH")
    env = dict(os.environ)
    if JAVA_HOME:
        env["JAVA_HOME"] = JAVA_HOME
    if os.name == "nt":  # trust the Windows certificate store (HTTPS-inspecting antivirus/proxies)
        env["GRADLE_OPTS"] = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"
    r = subprocess.run([gradle, "-p", str(dst), "--no-daemon", "-q", "assembleDebug"], env=env)
    if r.returncode != 0:
        sys.exit("Gradle build failed")
    apk = dst / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    APK_OUT.parent.mkdir(exist_ok=True)
    shutil.copy2(apk, APK_OUT)
    print(f"built {APK_OUT} ({APK_OUT.stat().st_size // 1024} KB)")


def sh(cmd):
    r = tv.adb("shell", cmd)
    out = (r.stdout + r.stderr).decode(errors="replace").strip()
    print(f"$ {cmd}\n  {out}")
    return out


def install(set_home):
    tv.ensure_connected()
    # adb install can stall on some firmware waiting for an on-screen OK; pm install never does
    tv.adb("push", str(APK_OUT), "/data/local/tmp/atvl.apk", check=True)
    out = sh("pm install -r /data/local/tmp/atvl.apk")
    sh("rm /data/local/tmp/atvl.apk")
    if "Success" not in out:
        sys.exit("install failed: " + out)
    # Android 9 only reveals the Wi-Fi SSID to apps holding location
    sh(f"pm grant {PKG} android.permission.ACCESS_FINE_LOCATION")
    if set_home:
        sh(f"cmd package set-home-activity {HOME}")
    sh(f"am start -n {HOME}")


if __name__ == "__main__":
    args = sys.argv[1:]
    if "--no-build" not in args:
        build()
    if "--build-only" not in args:
        install("--set-home" in args)
