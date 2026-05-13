#!/usr/bin/env python3
"""A wc-like CLI tool that counts lines, words, and characters in a file."""

import argparse
import json
import sys
import os


def count_stats(filepath):
    """Count lines, words, and characters in a file.

    Args:
        filepath: Path to the file to analyze.

    Returns:
        A dict with keys 'lines', 'words', 'chars', and 'file'.
    """
    lines = 0
    words = 0
    chars = 0

    with open(filepath, "r", encoding="utf-8") as f:
        for line in f:
            lines += 1
            words += len(line.split())
            chars += len(line)

    return {
        "file": filepath,
        "lines": lines,
        "words": words,
        "chars": chars,
    }


def main():
    parser = argparse.ArgumentParser(
        description="Count lines, words, and characters in a file (similar to wc)."
    )
    parser.add_argument(
        "file",
        help="Path to the file to analyze.",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        dest="json_output",
        help="Output results in JSON format.",
    )
    parser.add_argument(
        "--chars",
        action="store_true",
        dest="show_chars",
        default=False,
        help="Show character count (always included in JSON mode).",
    )

    args = parser.parse_args()

    if not os.path.exists(args.file):
        print(f"Error: File not found: {args.file}", file=sys.stderr)
        sys.exit(1)

    if not os.path.isfile(args.file):
        print(f"Error: Not a file: {args.file}", file=sys.stderr)
        sys.exit(1)

    stats = count_stats(args.file)

    if args.json_output:
        print(json.dumps(stats, indent=2))
    else:
        parts = [
            f"{stats['lines']:>8}",
            f"{stats['words']:>8}",
        ]
        if args.show_chars:
            parts.append(f"{stats['chars']:>8}")
        parts.append(stats["file"])
        print(" ".join(parts))


if __name__ == "__main__":
    main()
