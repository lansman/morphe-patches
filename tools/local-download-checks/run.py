#!/usr/bin/env python3
"""Run focused production-code checks with test-only Android preference stubs."""

from pathlib import Path
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory


def main() -> int:
    tool = Path(__file__).resolve().parent
    repo = tool.parents[1]
    production = repo / "extensions/youtube/src/main/java/app/morphe/extension/youtube/downloads"
    sources = [production / name for name in (
        "LocalVideoWatchProgress.java",
        "LocalVideoDownloadPreferences.java",
        "LocalVideoQuality.java",
    )]
    sources += sorted((tool / "stubs").rglob("*.java"))
    sources += sorted((tool / "fixtures").rglob("*.java"))
    for source in sources:
        if not source.is_file():
            print(f"Missing source: {source}", file=sys.stderr)
            return 2
    javac, java = shutil.which("javac"), shutil.which("java")
    if not javac or not java:
        print("Install a JDK 17+ and put javac/java on PATH.", file=sys.stderr)
        return 2
    try:
        with TemporaryDirectory(prefix="morphe-local-download-checks-") as temporary:
            classes = Path(temporary) / "classes"
            classes.mkdir()
            subprocess.run(
                [javac, "--release", "17", "-encoding", "UTF-8", "-d", str(classes)]
                + [str(source) for source in sources],
                cwd=temporary,
                check=True,
            )
            for fixture in ("ProgressFixtures", "QualityPreferencesCheck"):
                subprocess.run(
                    [java, "-ea", "-cp", str(classes),
                     "app.morphe.extension.youtube.downloads." + fixture],
                    cwd=temporary,
                    check=True,
                )
    except subprocess.CalledProcessError as failure:
        return failure.returncode or 1
    print("All focused local-download checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
