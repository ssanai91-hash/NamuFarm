"""Portable executable-relative data directory with a non-destructive legacy migration."""
import os
import shutil
import sqlite3
import sys
from contextlib import closing
from pathlib import Path


def portable_vault_path(legacy_directory):
    executable_directory = Path(sys.executable).resolve().parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parents[1]
    folder = executable_directory / "자료"
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / "namufarm-vault.db"
    legacy = Path(legacy_directory)
    if target.exists() or not (legacy / target.name).exists() or legacy.resolve() == folder.resolve():
        return target
    # Stage all sibling DB/workbook files first. The vault is published last, so
    # an interrupted migration is retried instead of opening a partially copied workspace.
    staged = []
    try:
        for source in sorted(legacy.iterdir(), key=lambda p: p.name == target.name):
            if not source.is_file() or source.suffix.lower() not in (".db", ".xlsx"):
                continue
            destination = folder / source.name
            if destination.exists():
                continue
            temporary = destination.with_name("." + destination.name + ".migrating")
            temporary.unlink(missing_ok=True)
            if source.suffix.lower() == ".db":
                with closing(sqlite3.connect(source.as_uri() + "?mode=ro", uri=True)) as old, closing(sqlite3.connect(temporary)) as new:
                    old.backup(new)
            else:
                shutil.copy2(source, temporary)
            staged.append((temporary, destination))
        for temporary, destination in staged:
            os.replace(temporary, destination)
    finally:
        for temporary, _ in staged:
            temporary.unlink(missing_ok=True)
    return target
