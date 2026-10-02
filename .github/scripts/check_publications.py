"""Check that local Maven publications contain only library files."""

from pathlib import Path
import sys
from zipfile import ZipFile


repository = Path(sys.argv[1]) / "app" / "waus" / "tmark"
expected = {
    "core": "core-0.1.0.module",
    "core-jvm": "core-jvm-0.1.0.jar",
    "android": "android-0.1.0.aar",
}

for module, filename in expected.items():
    artifact = repository / module / "0.1.0" / filename
    if not artifact.is_file():
        raise SystemExit(f"Missing publication: {artifact}")

if repository.joinpath("ratex").exists() or repository.joinpath("example").exists():
    raise SystemExit("Demo code must not be published")

for archive in repository.rglob("*"):
    if archive.suffix not in {".jar", ".aar"}:
        continue
    with ZipFile(archive) as content:
        for entry in content.namelist():
            parts = Path(entry).parts
            if (
                any(part in {"test", "androidTest", "example", "examples"} for part in parts)
                or entry.endswith((".md", ".tmark", ".fixture"))
            ):
                raise SystemExit(f"Unexpected file in {archive}: {entry}")

print("Maven publications contain no tests, examples or Markdown files")
