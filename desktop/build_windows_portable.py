"""Cross-build a Windows x64 folder bundle without Wine or GitHub Actions.

Requires an x86_64-w64-mingw32 GCC toolchain, the official CPython 3.12 x64
embeddable ZIP, and Windows wheels downloaded from this project's dependencies.
The original application modules and Windows manifest are preserved.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import zipfile


RUNTIME_PACKAGES = {
    "pyside6", "pyside6_essentials", "pyside6_addons", "pyside6_webengine",
    "pyside6_pdf", "shiboken6", "cryptography", "cffi", "pycparser", "xlrd", "xlwt",
}


def unpack(archive, destination, wheel=False):
    with zipfile.ZipFile(archive) as source:
        for item in source.infolist():
            if item.is_dir():
                continue
            parts = PurePosixPath(item.filename).parts
            if not parts or PurePosixPath(item.filename).is_absolute() or ".." in parts:
                raise ValueError(f"Unsafe archive member: {item.filename}")
            if wheel and parts[0].endswith(".data"):
                if len(parts) >= 3 and parts[1] in ("scripts", "headers"):
                    continue  # Build/command-line helpers are not application dependencies.
                if len(parts) < 3 or parts[1] not in ("purelib", "platlib"):
                    raise ValueError(f"Unsupported wheel data member: {item.filename}")
                parts = parts[2:]
            target = destination.joinpath(*parts)
            content = source.read(item)
            if target.exists() and target.read_bytes() != content:
                raise ValueError(f"Conflicting package file: {target}")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python-embed", type=Path, required=True)
    parser.add_argument("--wheels", type=Path, required=True)
    parser.add_argument("--compiler", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source-commit", required=True)
    args = parser.parse_args()
    desktop = Path(__file__).resolve().parent
    output = args.output.resolve()
    if output.exists():
        raise ValueError("Choose an empty output path to preserve previous bundles.")
    output.mkdir(parents=True)
    bundle = output / "NamuFarm-PC"
    runtime = bundle / "_runtime"
    site = runtime / "Lib" / "site-packages"
    site.mkdir(parents=True)
    unpack(args.python_embed, runtime)
    (runtime / "python312._pth").write_text(
        "python312.zip\n.\nLib/site-packages\nimport site\n", encoding="ascii")
    package_records = []
    selected = set()
    for wheel in sorted(args.wheels.glob("*.whl")):
        name = wheel.name.split("-")[0].lower().replace("-", "_")
        if name not in RUNTIME_PACKAGES:
            continue
        if name in selected:
            raise ValueError(f"Multiple wheel versions for {name}")
        if not wheel.name.endswith(("win_amd64.whl", "none-any.whl")):
            raise ValueError(f"Unexpected wheel platform: {wheel.name}")
        unpack(wheel, site, wheel=True)
        selected.add(name)
        package_records.append({"filename": wheel.name,
            "sha256": hashlib.sha256(wheel.read_bytes()).hexdigest()})
    required = {"pyside6", "pyside6_essentials", "pyside6_addons", "shiboken6",
                "cryptography", "cffi", "pycparser", "xlrd", "xlwt"}
    if not required <= selected:
        raise ValueError(f"Missing runtime wheels: {sorted(required - selected)}")
    shutil.copytree(desktop / "namufarm_desktop", runtime / "namufarm_desktop",
        ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    for file in (runtime / "namufarm_desktop").glob("*.py"):
        compile(file.read_text(encoding="utf-8"), str(file), "exec")
    for required_file in (
        "python312.dll", "python3.dll", "python312.zip", "vcruntime140.dll",
        "Lib/site-packages/PySide6/QtWidgets.pyd",
        "Lib/site-packages/PySide6/QtWebEngineCore.pyd",
        "Lib/site-packages/PySide6/QtWebEngineWidgets.pyd",
        "Lib/site-packages/PySide6/plugins/platforms/qwindows.dll",
        "namufarm_desktop/assets/quote_stamp.png",
    ):
        if not (runtime / required_file).is_file():
            raise ValueError(f"Missing runtime file: {required_file}")
    compiler = args.compiler.resolve()
    environment = os.environ.copy()
    environment["PATH"] = str(compiler.parent) + os.pathsep + environment["PATH"]
    rc = output / "launcher.rc"
    manifest = (desktop / "windows.manifest").as_posix()
    rc.write_text(f'1 24 "{manifest}"\n', encoding="utf-8")
    resource = output / "launcher-resource.o"
    subprocess.run([str(compiler.parent / "x86_64-w64-mingw32-windres"),
        str(rc), "-O", "coff", "-o", str(resource)], env=environment, check=True)
    executable = bundle / "NamuFarm-PC.exe"
    subprocess.run([str(compiler), "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
        "-municode", "-mwindows", "-static", "-s", "-finput-charset=UTF-8",
        "-Wl,--dynamicbase,--nxcompat,--high-entropy-va",
        str(desktop / "windows_portable_launcher.c"), str(resource), "-o", str(executable),
        "-lshell32", "-luser32"], env=environment, check=True)
    (bundle / "사용안내.txt").write_text(
        "나무팜 PC Windows 64비트 배포본\n\n"
        "1. ZIP 압축을 모두 풀어주세요.\n"
        "2. NamuFarm-PC.exe를 실행하세요. Python 별도 설치는 필요 없습니다.\n"
        "3. _runtime 폴더는 실행파일 옆에 그대로 두세요.\n"
        "4. 자료 폴더는 실행 시 생성됩니다. 기존 자료 폴더는 삭제하지 마세요.\n"
        "5. 업데이트할 때는 앱을 종료하고 EXE와 _runtime을 함께 교체하세요.\n"
        "6. 실행 오류가 나면 NamuFarm-error.log를 전달해주세요.\n"
        "7. 다른 Windows 계정 등으로 자료가 열리지 않으면 '새 자료로 시작'으로 기존 자료 폴더 전체를 자료_보관_날짜-시각에 보관하고 새 자료를 만듭니다. 서버 미전송 기록은 보관 폴더에만 남습니다.\n"
        "8. 원래 Windows 계정에서는 앱을 종료하고 현재 자료 폴더도 별도로 보관한 뒤, 자료_보관_… 폴더를 자료로 이름만 되돌려 기존 자료를 다시 열 수 있습니다. 기존 폴더에 덮어쓰지 마세요.\n"
        "9. 다른 PC로 옮기거나 Windows를 다시 설치하기 전 '자료 관리 > 자료 보관 상태'에서 전송 대기 0건과 마지막 동기화를 확인하세요.\n\n"
        "고객문의 서버 기능은 기존 Apps Script의 최신 버전 배포가 필요합니다.\n"
        "Windows 실제 화면과 서버·문자·메일 동작은 사용자 확인 대상입니다.\n",
        encoding="utf-8-sig")
    record = {"source_commit": args.source_commit, "target": "Windows x64",
        "format": "CPython embedded runtime with MinGW launcher",
        "compiler": subprocess.check_output([str(compiler), "--version"],
            env=environment, text=True).splitlines()[0],
        "python_embed_sha256": hashlib.sha256(args.python_embed.read_bytes()).hexdigest(),
        "wheels": package_records, "windows_execution_verified": False}
    (bundle / "BUILD-INFO.json").write_text(json.dumps(record, ensure_ascii=False, indent=2),
        encoding="utf-8")
    archive = output / "NamuFarm-PC-Windows-x64.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for file in sorted(bundle.rglob("*")):
            if file.is_file():
                z.write(file, file.relative_to(output))
    print(json.dumps({"exe": str(executable), "archive": str(archive),
        "archive_bytes": archive.stat().st_size, "source_commit": args.source_commit}))


if __name__ == "__main__":
    main()
