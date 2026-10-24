"""Sign an original-app update locally; reject a different certificate or app identity."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile

PACKAGE = "com.example.namufarm.original"
CERTIFICATE = "a31a78a895be8f89bacb2c7b355f413e1cacecfbfd769b257801fcb619e616e1"


def run(command):
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode:
        raise SystemExit("APK signing/verification failed. No update was published.")
    return result.stdout


def certificate(apksigner, apk):
    text = run([str(apksigner), "verify", "--print-certs", str(apk)])
    matches = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", text)
    if matches != [CERTIFICATE]:
        raise SystemExit("APK certificate does not match the original app. No update was published.")


def identity(aapt, apk):
    text = run([str(aapt), "dump", "badging", str(apk)])
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", text, re.M)
    if not match or match[1] != PACKAGE:
        raise SystemExit("APK package does not match the original app.")
    return int(match[2]), "application-debuggable" in text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--unsigned-apk", type=Path, required=True)
    parser.add_argument("--reference-apk", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    names = ("NAMUFARM_INITIAL_KEYSTORE", "NAMUFARM_INITIAL_KEY_ALIAS",
             "NAMUFARM_INITIAL_STORE_PASSWORD", "NAMUFARM_INITIAL_KEY_PASSWORD")
    if not all(os.environ.get(name) for name in names):
        raise SystemExit("Original-app signing credentials are required; no debug/new-key fallback.")
    if args.output.exists():
        raise SystemExit("Output already exists; choose a new update filename.")
    tools = args.build_tools
    signer = tools / "apksigner"
    certificate(signer, args.reference_apk)
    previous, _ = identity(tools / "aapt", args.reference_apk)
    current, debug = identity(tools / "aapt", args.unsigned_apk)
    if debug or current <= previous:
        raise SystemExit("A release APK with a newer versionCode is required.")
    keytool = Path(os.environ["JAVA_HOME"]) / "bin" / "keytool"
    result = subprocess.run([str(keytool), "-exportcert", "-keystore",
        os.environ["NAMUFARM_INITIAL_KEYSTORE"], "-alias", os.environ["NAMUFARM_INITIAL_KEY_ALIAS"],
        "-storepass:env", "NAMUFARM_INITIAL_STORE_PASSWORD"], capture_output=True)
    if result.returncode or hashlib.sha256(result.stdout).hexdigest() != CERTIFICATE:
        raise SystemExit("Signing key does not match the original app; no new-key fallback.")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="initial-sign-", dir=args.output.parent) as tmp:
        aligned, signed = Path(tmp) / "aligned.apk", Path(tmp) / "signed.apk"
        run([str(tools / "zipalign"), "-p", "-f", "4", str(args.unsigned_apk), str(aligned)])
        run([str(signer), "sign", "--ks", os.environ["NAMUFARM_INITIAL_KEYSTORE"],
            "--ks-key-alias", os.environ["NAMUFARM_INITIAL_KEY_ALIAS"],
            "--ks-pass", "env:NAMUFARM_INITIAL_STORE_PASSWORD",
            "--key-pass", "env:NAMUFARM_INITIAL_KEY_PASSWORD", "--out", str(signed), str(aligned)])
        certificate(signer, signed)
        if identity(tools / "aapt", signed) != (current, False):
            raise SystemExit("Signed update identity verification failed.")
        run([str(tools / "zipalign"), "-c", "-p", "4", str(signed)])
        signed.replace(args.output)
    print("Original-app certificate, package, version and alignment verified.")
    print(args.output)


if __name__ == "__main__":
    main()
