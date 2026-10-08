"""CI-only release signing setup; never prints private material or passwords."""
import base64
import hashlib
import os
from pathlib import Path
import re
import subprocess


def main():
    names = ("SIGNING_BASE64", "NAMUFARM_STORE_PASSWORD", "NAMUFARM_KEY_ALIAS",
             "NAMUFARM_KEY_PASSWORD", "NAMUFARM_SIGNING_SHA256")
    configured = [bool(os.environ.get(name)) for name in names]
    if not any(configured):
        print("Release APK not published: persistent signing credentials are not configured.")
        return
    if not all(configured):
        raise SystemExit("Release signing configuration is incomplete. No APK was published.")
    expected = os.environ["NAMUFARM_SIGNING_SHA256"].replace(":", "").lower()
    if not re.fullmatch(r"[a-f0-9]{64}", expected):
        raise SystemExit("The pinned signing certificate fingerprint is invalid.")
    path = Path(os.environ["RUNNER_TEMP"]) / "namufarm-release.jks"
    try:
        data = base64.b64decode(os.environ["SIGNING_BASE64"], validate=True)
        with path.open("xb") as output:
            os.chmod(path, 0o600)
            output.write(data)
        result = subprocess.run(["keytool", "-exportcert", "-keystore", str(path),
                                 "-alias", os.environ["NAMUFARM_KEY_ALIAS"],
                                 "-storepass:env", "NAMUFARM_STORE_PASSWORD"], capture_output=True)
        if result.returncode or hashlib.sha256(result.stdout).hexdigest() != expected:
            raise ValueError()
    except Exception:
        path.unlink(missing_ok=True)
        raise SystemExit("The release keystore does not match the pinned certificate. No APK was published.") from None
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
        output.write("ready=true\n")
    print("Persistent release certificate verified.")


if __name__ == "__main__":
    main()
