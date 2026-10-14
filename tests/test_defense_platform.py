"""Проверки изолированного запуска и поведения на разных путях."""
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
SCANNER_SCRIPT = ROOT / "scripts" / "defense_scan.py"
DIFF_SCRIPT = ROOT / "scripts" / "defense_diff.py"
CONTROL_SCRIPT = ROOT / "control.py"

SPEC = importlib.util.spec_from_file_location("platform_scanner", SCANNER_SCRIPT)
scanner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(scanner)
DIFF_SPEC = importlib.util.spec_from_file_location("platform_diff", DIFF_SCRIPT)
diff = importlib.util.module_from_spec(DIFF_SPEC)
DIFF_SPEC.loader.exec_module(diff)


def isolated_cli(script, *args, cwd=None, env=None):
    return subprocess.run(
        [sys.executable, "-X", "utf8", "-I", "-S", str(script), *map(str, args)],
        cwd=cwd or ROOT,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=20,
    )


def cp1252_cli(script, *args):
    env = os.environ.copy()
    env["PYTHONIOENCODING"] = "cp1252:strict"
    env["PYTHONUTF8"] = "0"
    return subprocess.run(
        [sys.executable, str(script), *map(str, args)], cwd=ROOT, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20,
    )


def scan_report(findings=(), *, version=scanner.VERSION):
    scope = {
        "schema": "wahrwelt.scanner-scope.v1",
        "root": "sha256:" + "1" * 64,
        "rules_sha256": "2" * 64,
        "exclude": [],
        "limits": {"max_bytes": 1048576, "max_files": 20000,
                   "max_findings": 10000},
        "path_semantics": "root-relative-posix.v1",
    }
    scope["sha256"] = hashlib.sha256(json.dumps(
        scope, ensure_ascii=True, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")).hexdigest()
    return {
        "tool": "wahrwelt defense_scan",
        "version": version,
        "offline": True,
        "created_at": "2026-10-09T00:00:00Z",
        "scope": scope,
        "findings": list(findings),
        "issues": [],
        "partial": False,
        "exit_code": 1 if findings else 0,
    }


class CliEncodingTests(unittest.TestCase):
    def assert_cli_exit(self, result, expected):
        stderr = result.stderr.decode("ascii", "backslashreplace")
        self.assertNotIn("UnicodeEncodeError", stderr, stderr)
        self.assertEqual(result.returncode, expected, stderr)
        self.assertTrue(result.stdout, "CLI не вывел результат")

    def test_scanner_help_rules_and_explain_support_cp1252_stdout(self):
        for args in (("--help",), ("--list-rules",), ("--explain", "PY-EVAL")):
            with self.subTest(args=args):
                self.assert_cli_exit(cp1252_cli(SCANNER_SCRIPT, *args), 0)

    def test_diff_help_and_exit_codes_support_cp1252(self):
        self.assert_cli_exit(cp1252_cli(DIFF_SCRIPT, "--help"), 0)
        finding = {"path": "service.py", "line": 3, "rule": "PY-EVAL"}
        cases = (
            (scan_report(), scan_report(), 0),
            (scan_report(), scan_report([finding]), 1),
            (scan_report(), scan_report(version="1.5.0"), 2),
        )
        with tempfile.TemporaryDirectory() as temp:
            before_path, after_path = Path(temp) / "before.json", Path(temp) / "after.json"
            for before, after, expected in cases:
                with self.subTest(expected=expected):
                    before_path.write_text(json.dumps(before), encoding="ascii")
                    after_path.write_text(json.dumps(after), encoding="ascii")
                    self.assert_cli_exit(cp1252_cli(DIFF_SCRIPT, before_path, after_path), expected)


class IsolatedCliTests(unittest.TestCase):
    def test_core_commands_run_without_site_or_pythonpath(self):
        with tempfile.TemporaryDirectory() as temp:
            environment = os.environ.copy()
            environment["PYTHONPATH"] = temp
            (Path(temp) / "common.py").write_text(
                "raise RuntimeError('untrusted cwd helper imported')\n", encoding="ascii")
            for script, args in (
                (SCANNER_SCRIPT, ("--help",)),
                (DIFF_SCRIPT, ("--help",)),
                (CONTROL_SCRIPT, ("--help",)),
                (CONTROL_SCRIPT, ("scan", "--help")),
            ):
                with self.subTest(script=script.name, args=args):
                    completed = isolated_cli(script, *args, cwd=temp, env=environment)
                    self.assertEqual(completed.returncode, 0,
                                     completed.stderr.decode("utf-8", "backslashreplace"))
                    self.assertTrue(completed.stdout)

    def test_control_forwards_scanning_and_json_arguments(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "service"
            root.mkdir()
            (root / "safe.py").write_text("value = 1\n", encoding="utf-8")
            completed = isolated_cli(CONTROL_SCRIPT, "scan", root, "--json")
            self.assertEqual(completed.returncode, 0,
                             completed.stderr.decode("utf-8", "backslashreplace"))
            result = json.loads(completed.stdout)
            self.assertEqual(result["files_scanned"], 1)
            self.assertFalse(result["partial"])

    def test_scanner_and_diff_accept_unicode_and_space_paths(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp) / "service тест"
            directory.mkdir()
            source = directory / "safe код.py"
            source.write_text("value = 1\n", encoding="utf-8")
            completed = isolated_cli(SCANNER_SCRIPT, directory, "--json", cwd=temp)
            self.assertEqual(completed.returncode, 0,
                             completed.stderr.decode("utf-8", "backslashreplace"))
            report = json.loads(completed.stdout)
            self.assertEqual(report["files_scanned"], 1)
            self.assertFalse(report["partial"])
            report_path = Path(temp) / "report тест.json"
            report_path.write_bytes(completed.stdout)
            compared = isolated_cli(DIFF_SCRIPT, report_path, report_path, "--json", cwd=temp)
            self.assertEqual(compared.returncode, 0,
                             compared.stderr.decode("utf-8", "backslashreplace"))
            self.assertEqual(json.loads(compared.stdout)["exit_code"], 0)


class SymlinkRootTests(unittest.TestCase):
    def symlink_or_skip(self, link, target, *, directory=False):
        try:
            link.symlink_to(target, target_is_directory=directory)
        except (NotImplementedError, OSError) as exc:
            self.skipTest(f"symlink creation is unavailable: {type(exc).__name__}")
        self.assertTrue(link.is_symlink())

    def test_regular_file_below_symlinked_ancestor_is_accepted(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            real = root / "real"
            real.mkdir()
            (real / "service.py").write_text("value = 1\n", encoding="utf-8")
            alias = root / "alias"
            self.symlink_or_skip(alias, real, directory=True)
            report = scanner.scan(alias / "service.py", scanner.load_rules())
            self.assertEqual(report["files_scanned"], 1)
            self.assertEqual(report["issues"], [])
            self.assertEqual(report["exit_code"], 0)

    def test_symlink_leaf_and_directory_roots_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            target = root / "target.py"
            target.write_text("value = 1\n", encoding="utf-8")
            link = root / "service.py"
            self.symlink_or_skip(link, target)
            with self.assertRaisesRegex(ValueError, "symlink"):
                scanner.scan(link, scanner.load_rules())

            target_dir = root / "target"
            target_dir.mkdir()
            alias = root / "directory-link"
            self.symlink_or_skip(alias, target_dir, directory=True)
            with self.assertRaisesRegex(ValueError, "symlink"):
                scanner.scan(alias, scanner.load_rules())

    def test_scan_root_leaf_swap_during_canonicalization_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "service.py"
            path.write_text("value = 1\n", encoding="utf-8")
            regular = path.lstat()
            symlink = type("SymlinkMetadata", (), {
                "st_dev": regular.st_dev,
                "st_ino": regular.st_ino,
                "st_mode": stat.S_IFLNK | 0o777,
            })()
            with patch.object(Path, "lstat", side_effect=[regular, symlink]):
                with self.assertRaisesRegex(ValueError, "symlink"):
                    scanner.canonical_scan_root(path)

    def test_diff_accepts_symlinked_ancestor_and_rejects_leaf(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            real = root / "real"
            real.mkdir()
            report = real / "report.json"
            report.write_text(json.dumps(scan_report()), encoding="utf-8")
            alias = root / "alias"
            self.symlink_or_skip(alias, real, directory=True)
            self.assertEqual(diff.load_report(alias / "report.json")["findings"], {})

            target = root / "target.json"
            target.write_text(json.dumps(scan_report()), encoding="utf-8")
            leaf = root / "leaf.json"
            self.symlink_or_skip(leaf, target)
            with self.assertRaisesRegex(ValueError, "symlink"):
                diff.load_report(leaf)


if __name__ == "__main__":
    unittest.main()
