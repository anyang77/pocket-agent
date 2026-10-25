#!/usr/bin/env python3
"""Print a compact health report for a source tree."""

from __future__ import annotations

import argparse
import json
import os
from dataclasses import asdict, dataclass
from pathlib import Path


SOURCE_EXTENSIONS = {
    ".c",
    ".cc",
    ".cpp",
    ".h",
    ".hpp",
    ".java",
    ".js",
    ".jsx",
    ".kt",
    ".kts",
    ".md",
    ".py",
    ".sh",
    ".ts",
    ".tsx",
}

SKIP_DIRECTORIES = {
    ".build",
    ".git",
    ".gradle",
    ".idea",
    ".kotlin",
    "__pycache__",
    "build",
    "dist",
    "node_modules",
    "vendor",
}


@dataclass(frozen=True)
class FileStats:
    path: str
    lines: int
    bytes: int
    todos: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", default=".", help="directory to inspect")
    parser.add_argument(
        "--large-kb",
        type=int,
        default=512,
        help="report files larger than this size (default: 512)",
    )
    parser.add_argument("--top", type=int, default=10, help="rows in largest-files table")
    parser.add_argument("--json", action="store_true", help="print JSON instead of text")
    return parser.parse_args()


def source_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for current, directories, names in os.walk(root):
        directories[:] = sorted(
            name for name in directories if name not in SKIP_DIRECTORIES
        )
        current_path = Path(current)
        for name in sorted(names):
            path = current_path / name
            if path.suffix.lower() in SOURCE_EXTENSIONS and path.is_file():
                files.append(path)
    return files


def inspect(path: Path, root: Path) -> FileStats | None:
    try:
        data = path.read_bytes()
    except OSError:
        return None
    if b"\0" in data[:4096]:
        return None
    text = data.decode("utf-8", errors="replace")
    marker_count = sum(
        text.count(marker) for marker in ("TODO", "FIXME", "HACK")
    )
    return FileStats(
        path=path.relative_to(root).as_posix(),
        lines=text.count("\n") + (0 if text.endswith("\n") else 1),
        bytes=len(data),
        todos=marker_count,
    )


def human_size(size: int) -> str:
    if size >= 1024 * 1024:
        return f"{size / (1024 * 1024):.1f} MiB"
    if size >= 1024:
        return f"{size / 1024:.1f} KiB"
    return f"{size} B"


def print_text(stats: list[FileStats], root: Path, large_bytes: int, top: int) -> None:
    total_files = len(stats)
    total_lines = sum(item.lines for item in stats)
    total_bytes = sum(item.bytes for item in stats)
    total_markers = sum(item.todos for item in stats)
    large_files = [item for item in stats if item.bytes >= large_bytes]

    print(f"项目：{root}")
    print(f"源文件：{total_files}")
    print(f"总行数：{total_lines:,}")
    print(f"总大小：{human_size(total_bytes)}")
    print(f"TODO/FIXME/HACK：{total_markers}")
    print(f"超大文件（≥ {human_size(large_bytes)}）：{len(large_files)}")

    if large_files:
        print("\n超大文件：")
        for item in sorted(large_files, key=lambda value: value.bytes, reverse=True)[:top]:
            print(f"  {human_size(item.bytes):>10}  {item.lines:>7} 行  {item.path}")

    print("\n最大文件：")
    for item in sorted(stats, key=lambda value: value.bytes, reverse=True)[:top]:
        print(f"  {human_size(item.bytes):>10}  {item.lines:>7} 行  {item.path}")

    by_extension: dict[str, int] = {}
    for item in stats:
        suffix = Path(item.path).suffix.lower() or "(none)"
        by_extension[suffix] = by_extension.get(suffix, 0) + item.lines

    print("\n按扩展名统计行数：")
    for suffix, lines in sorted(
        by_extension.items(), key=lambda pair: pair[1], reverse=True
    ):
        print(f"  {suffix:<10} {lines:>9,}")


def main() -> int:
    args = parse_args()
    root = Path(args.root).expanduser().resolve()
    if not root.is_dir():
        raise SystemExit(f"目录不存在：{root}")

    stats = [
        item
        for path in source_files(root)
        if (item := inspect(path, root)) is not None
    ]
    payload = {
        "root": str(root),
        "files": len(stats),
        "lines": sum(item.lines for item in stats),
        "bytes": sum(item.bytes for item in stats),
        "markers": sum(item.todos for item in stats),
        "largest": [
            asdict(item)
            for item in sorted(stats, key=lambda value: value.bytes, reverse=True)[
                : args.top
            ]
        ],
    }
    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        print_text(
            stats,
            root,
            large_bytes=max(1, args.large_kb) * 1024,
            top=max(1, args.top),
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
