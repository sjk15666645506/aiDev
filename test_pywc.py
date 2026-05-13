"""Tests for pywc.py — a wc-like CLI tool."""

import json
import os
import sys
import tempfile
import pytest
from io import StringIO

# Import the module under test
import pywc


# ── helpers ──────────────────────────────────────────────────────────────

def _write_temp(content, encoding="utf-8"):
    """Create a temp file with given content and return its path."""
    tmp = tempfile.NamedTemporaryFile(mode="w", encoding=encoding, delete=False, suffix=".txt")
    tmp.write(content)
    tmp.close()
    return tmp.name


def _run_main(argv):
    """Run pywc.main() with given argv, capturing stdout/stderr and exit code."""
    old_argv = sys.argv
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    try:
        sys.argv = ["pywc.py"] + argv
        sys.stdout = StringIO()
        sys.stderr = StringIO()
        exit_code = 0
        try:
            pywc.main()
        except SystemExit as e:
            exit_code = e.code if e.code is not None else 1
        return exit_code, sys.stdout.getvalue(), sys.stderr.getvalue()
    finally:
        sys.argv = old_argv
        sys.stdout = old_stdout
        sys.stderr = old_stderr


# ── count_stats tests ────────────────────────────────────────────────────

class TestCountStats:
    """Direct unit tests for count_stats()."""

    def test_empty_file(self):
        path = _write_temp("")
        try:
            stats = pywc.count_stats(path)
            assert stats["lines"] == 0
            assert stats["words"] == 0
            assert stats["chars"] == 0
            assert stats["file"] == path
        finally:
            os.unlink(path)

    def test_single_line_no_trailing_newline(self):
        path = _write_temp("hello world")
        try:
            stats = pywc.count_stats(path)
            assert stats["lines"] == 1
            assert stats["words"] == 2
            assert stats["chars"] == 11   # "hello world" = 11 chars
        finally:
            os.unlink(path)

    def test_single_line_with_trailing_newline(self):
        path = _write_temp("hello world\n")
        try:
            stats = pywc.count_stats(path)
            assert stats["lines"] == 1
            assert stats["words"] == 2
            assert stats["chars"] == 12   # "hello world\n" = 12 chars
        finally:
            os.unlink(path)

    def test_multiple_lines(self):
        content = "line one\nline two\nline three\n"
        path = _write_temp(content)
        try:
            stats = pywc.count_stats(path)
            assert stats["lines"] == 3
            assert stats["words"] == 6
            # 3 lines: 8+1 + 8+1 + 10+1 = 29
            assert stats["chars"] == 29
        finally:
            os.unlink(path)

    def test_multiple_spaces_between_words(self):
        path = _write_temp("a   b    c\n")
        try:
            stats = pywc.count_stats(path)
            assert stats["lines"] == 1
            assert stats["words"] == 3   # split() on whitespace
            assert stats["chars"] == 11  # "a   b    c\n" = 1+3+1+4+1+1
        finally:
            os.unlink(path)

    def test_only_newlines(self):
        path = _write_temp("\n\n\n")
        try:
            stats = pywc.count_stats(path)
            assert stats["lines"] == 3
            assert stats["words"] == 0   # empty strings after split
            assert stats["chars"] == 3
        finally:
            os.unlink(path)

    def test_only_whitespace(self):
        path = _write_temp("   \t  \n")
        try:
            stats = pywc.count_stats(path)
            assert stats["lines"] == 1
            assert stats["words"] == 0   # split() on whitespace yields empty list
            assert stats["chars"] == 7   # "   \t  \n" = 3 spaces + tab + 2 spaces + newline
        finally:
            os.unlink(path)

    def test_file_not_found(self):
        with pytest.raises(FileNotFoundError):
            pywc.count_stats("/nonexistent/path/file_987654321.txt")

    def test_unicode_content(self):
        path = _write_temp("café résumé\nnaïve\n")
        try:
            stats = pywc.count_stats(path)
            assert stats["lines"] == 2
            assert stats["words"] == 3   # café, résumé, naïve
            # "café résumé\n"=4+7+1=12, "naïve\n"=5+1=6 => 18
            assert stats["chars"] == 18
        finally:
            os.unlink(path)

    def test_large_file(self):
        # 1000 lines, each "word{i}" so 1 word per line
        # i=0..9: 5-char words (10), i=10..99: 6-char words (90), i=100..999: 7-char words (900)
        # chars: 10*5 + 90*6 + 900*7 = 6890, plus 1000 newlines = 7890
        content = "\n".join(f"word{i}" for i in range(1000)) + "\n"
        path = _write_temp(content)
        try:
            stats = pywc.count_stats(path)
            assert stats["lines"] == 1000
            assert stats["words"] == 1000
            assert stats["chars"] == 7890
        finally:
            os.unlink(path)

    def test_returns_dict_with_expected_keys(self):
        path = _write_temp("x\n")
        try:
            stats = pywc.count_stats(path)
            assert set(stats.keys()) == {"file", "lines", "words", "chars"}
        finally:
            os.unlink(path)


# ── CLI tests ────────────────────────────────────────────────────────────

class TestCLI:
    """Integration tests via main()."""

    def test_default_output_format(self):
        path = _write_temp("hello world\n")
        try:
            code, out, err = _run_main([path])
            assert code == 0
            assert err == ""
            # output: "       1        2 hello world" (numbers right-aligned to 8)
            assert "1" in out
            assert "2" in out
            assert path in out
        finally:
            os.unlink(path)

    def test_json_output(self):
        path = _write_temp("hello world\n")
        try:
            code, out, err = _run_main([path, "--json"])
            assert code == 0
            assert err == ""
            data = json.loads(out)
            assert data["lines"] == 1
            assert data["words"] == 2
            assert data["chars"] == 12
            assert data["file"] == path
        finally:
            os.unlink(path)

    def test_chars_flag(self):
        path = _write_temp("hello world\n")
        try:
            code, out, err = _run_main([path, "--chars"])
            assert code == 0
            assert err == ""
            # output should have three numbers + filename
            parts = out.strip().split()
            assert len(parts) == 4  # lines, words, chars, file
            assert parts[0] == "1"  # lines
            assert parts[1] == "2"  # words
            assert parts[2] == "12" # chars
            assert parts[3] == path
        finally:
            os.unlink(path)

    def test_json_with_chars_irrelevant(self):
        """--chars is irrelevant in json mode, but should not break."""
        path = _write_temp("a\n")
        try:
            code, out, err = _run_main([path, "--json", "--chars"])
            assert code == 0
            assert err == ""
            data = json.loads(out)
            assert data["lines"] == 1
        finally:
            os.unlink(path)

    def test_file_not_found_exit(self):
        code, out, err = _run_main(["/nonexistent/file_xyz.txt"])
        assert code == 1
        assert "Error: File not found" in err
        assert out == ""

    def test_directory_as_file(self):
        code, out, err = _run_main([tempfile.gettempdir()])
        assert code == 1
        assert "Error: Not a file" in err
        assert out == ""

    def test_missing_required_argument(self):
        code, out, err = _run_main([])
        assert code != 0  # argparse calls sys.exit(2) on missing required arg


# ── Edge-case / regression tests ─────────────────────────────────────────

class TestEdgeCases:
    """Additional edge-case coverage."""

    def test_file_with_tabs(self):
        path = _write_temp("col1\tcol2\tcol3\n")
        try:
            stats = pywc.count_stats(path)
            assert stats["lines"] == 1
            # split() splits on any whitespace including tabs
            assert stats["words"] == 3
        finally:
            os.unlink(path)

    def test_single_character_file(self):
        path = _write_temp("x")
        try:
            stats = pywc.count_stats(path)
            assert stats["lines"] == 1
            assert stats["words"] == 1
            assert stats["chars"] == 1
        finally:
            os.unlink(path)

    def test_newline_only_file(self):
        path = _write_temp("\n")
        try:
            stats = pywc.count_stats(path)
            assert stats["lines"] == 1
            assert stats["words"] == 0
            assert stats["chars"] == 1
        finally:
            os.unlink(path)
