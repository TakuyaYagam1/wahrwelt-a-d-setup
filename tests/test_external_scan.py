import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "external_scan.py"
SPEC = importlib.util.spec_from_file_location("external_scan", SCRIPT)
external_scan = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(external_scan)


class ExternalScanTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name)
        self.root = self.base / "tree"
        self.root.mkdir()
        (self.root / "sample.py").write_text("value = 1\n", encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def test_cli_rejects_unknown_engine(self):
        stdout = io.StringIO()
        stderr = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as raised:
                external_scan.main([str(self.root), "--engine", "unknown"])
        self.assertEqual(raised.exception.code, 2)
        self.assertIn("invalid choice", stderr.getvalue())

    def test_cli_rejects_database_update_for_semgrep(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as raised:
                external_scan.main([str(self.root), "--engine", "semgrep", "--update-trivy-db"])
        self.assertEqual(raised.exception.code, 2)
        self.assertIn("применим только к Trivy", stderr.getvalue())

    def test_missing_binary_is_reported_as_unavailable(self):
        with mock.patch.object(external_scan.shutil, "which", return_value=None):
            report = external_scan.scan(self.root, "semgrep")
        self.assertEqual(report["status"], "partial")
        self.assertEqual(report["exit_code"], 2)
        self.assertEqual(report["results"][0]["status"], "unavailable")
        self.assertEqual(report["results"][0]["issues"][0]["code"], "binary_unavailable")

    def test_one_engine_failure_makes_combined_scan_partial(self):
        complete = {"engine": "trivy", "status": "complete", "findings": [], "issues": [], "scope": {}}
        unavailable = {"engine": "semgrep", "status": "unavailable", "findings": [],
                       "issues": [{"code": "binary_unavailable"}], "scope": {}}
        with mock.patch.object(external_scan, "_rule_pack_digest", return_value="rules"), \
             mock.patch.object(external_scan, "_semgrep", return_value=unavailable), \
             mock.patch.object(external_scan, "_trivy", return_value=complete):
            report = external_scan.scan(self.root, "all")
        self.assertEqual(report["status"], "partial")
        self.assertEqual(report["exit_code"], 2)
        self.assertEqual([item["status"] for item in report["results"]],
                         ["unavailable", "complete"])

    def test_invalid_semgrep_json_is_partial(self):
        tool = {"status": "complete", "version": "1.2.3", "output": "1.2.3"}
        run = {"status": "complete", "returncode": 0, "stdout": b"{bad",
               "stderr": b"", "stdout_bytes": 4, "stderr_bytes": 0}
        with mock.patch.object(external_scan, "_tool", return_value=("/bin/semgrep", tool)), \
             mock.patch.object(external_scan, "run_command", return_value=run):
            result = external_scan._semgrep(self.root, "sha256:test", 1,
                                            external_scan._rule_pack_digest())
        self.assertEqual(result["status"], "partial")
        self.assertEqual(result["issues"][0]["code"], "invalid_json")

    def test_invalid_trivy_json_is_partial(self):
        tool = {"status": "complete", "version": "0.1.2", "output": "0.1.2"}
        run = {"status": "complete", "returncode": 0, "stdout": b"not-json",
               "stderr": b"", "stdout_bytes": 8, "stderr_bytes": 0}
        with mock.patch.object(external_scan, "_tool", return_value=("/bin/trivy", tool)), \
             mock.patch.object(external_scan, "run_command", return_value=run):
            result = external_scan._trivy(self.root, "sha256:test", 1, False)
        self.assertEqual(result["status"], "partial")
        self.assertEqual(result["issues"][0]["code"], "invalid_json")

    def test_empty_trivy_json_is_rejected(self):
        tool = {"status": "complete", "version": "0.1.2", "output": "0.1.2"}
        run = {"status": "complete", "returncode": 0, "stdout": b"{}",
               "stderr": b"", "stdout_bytes": 2, "stderr_bytes": 0}
        with mock.patch.object(external_scan, "_tool", return_value=("/bin/trivy", tool)), \
             mock.patch.object(external_scan, "run_command", return_value=run):
            result = external_scan._trivy(self.root, "sha256:test", 1, False)
        self.assertEqual(result["status"], "partial")
        self.assertEqual(result["issues"][0]["code"], "missing_schema_version")

    def test_trivy_vulnerability_fields_are_preserved(self):
        raw = {"VulnerabilityID": "CVE-2026-1234", "Title": "Example issue",
               "Severity": "HIGH", "Target": "requirements.txt", "PkgName": "requests",
               "InstalledVersion": "2.31.0", "FixedVersion": "2.32.4",
               "PrimaryURL": "https://example.invalid/advisory"}
        finding = external_scan._trivy_finding(raw, "vulnerability", self.root)
        self.assertEqual(finding["package"], "requests")
        self.assertEqual(finding["installed_version"], "2.31.0")
        self.assertEqual(finding["fixed_version"], "2.32.4")
        self.assertEqual(finding["primary_url"], raw["PrimaryURL"])
        self.assertEqual(finding["path"], "requirements.txt")

    def test_trivy_metadata_uses_go_time_field_names(self):
        metadata = external_scan._trivy_state(
            "Vulnerability DB:\n  Version: 2\n"
            "  UpdatedAt: 2026-10-12 07:12:51.98420317 +0000 UTC\n"
            "  NextUpdate: 2026-10-13 07:12:51.98420302 +0000 UTC\n")
        entry = metadata["vulnerability_db"]
        self.assertIn("next_update", entry)
        parsed = external_scan._parse_time(entry["next_update"])
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed.year, 2026)

    def test_expired_trivy_database_is_not_a_clean_result(self):
        state = (
            "0.1.2\nVulnerability DB:\n  Version: 2\n"
            "  UpdatedAt: 2020-01-01 00:00:00.0 +0000 UTC\n"
            "  NextUpdate: 2020-01-02 00:00:00.0 +0000 UTC\n"
            "Check Bundle:\n  Digest: sha256:abc\n")
        tool = {"status": "complete", "version": "0.1.2", "output": state}
        empty_report = {"SchemaVersion": 2, "Trivy": {"Version": "0.1.2"}, "Results": []}
        raw_report = json.dumps(empty_report).encode("utf-8")
        run = {"status": "complete", "returncode": 0, "stdout": raw_report,
               "stderr": b"", "stdout_bytes": len(raw_report), "stderr_bytes": 0}
        with mock.patch.object(external_scan, "_tool", return_value=("/bin/trivy", tool)), \
             mock.patch.object(external_scan, "run_command", return_value=run):
            result = external_scan._trivy(self.root, "sha256:test", 1, False)
        self.assertEqual(result["status"], "partial")
        self.assertIn("database_expired", [item["code"] for item in result["issues"]])

    def test_trivy_config_files_are_explicit_and_scope_reports_flags(self):
        state = (
            "0.1.2\nVulnerability DB:\n  Version: 2\n"
            "  NextUpdate: 2099-01-02 00:00:00.0 +0000 UTC\n"
            "Check Bundle:\n  Digest: sha256:abc\n")
        tool = {"status": "complete", "version": "0.1.2", "output": state}
        report = {"SchemaVersion": 2, "Trivy": {"Version": "0.1.2"}, "Results": []}
        raw_report = json.dumps(report).encode("utf-8")
        seen = {}

        def fake_run(argv, **kwargs):
            seen.setdefault("argv", list(argv))
            if "fs" in argv:
                payload = raw_report
            else:
                payload = state.encode("utf-8")
            return {"status": "complete", "returncode": 0, "stdout": payload,
                    "stderr": b"", "stdout_bytes": len(payload), "stderr_bytes": 0}

        with mock.patch.object(external_scan, "_tool", return_value=("/bin/trivy", tool)), \
             mock.patch.object(external_scan, "run_command", side_effect=fake_run), \
             mock.patch.object(external_scan, "_has_java_artifacts", return_value=False):
            result = external_scan._trivy(self.root, "sha256:test", 1, True)
        argv = seen["argv"]
        self.assertIn("--offline-scan", argv)
        self.assertEqual(result["scope"]["offline_scan"], True)
        self.assertEqual(result["scope"]["database_update_enabled"], True)
        self.assertEqual(argv[argv.index("--ignorefile") + 1], os.devnull)
        self.assertIn("--secret-config=", argv)

    @unittest.skipUnless(os.name == "posix", "лимит процесса проверяется на POSIX")
    def test_timeout_kills_child_process(self):
        result = external_scan.run_command(
            [sys.executable, "-c", "import time; time.sleep(2)"],
            cwd=self.root, timeout=0.05, output_limit=1024)
        self.assertEqual(result["status"], "timeout")
        self.assertIsNotNone(result["returncode"])

    def test_symlink_tree_is_not_scanned(self):
        link = self.root / "outside.py"
        target = self.base / "outside.py"
        target.write_text("secret = 'fixture'\n", encoding="utf-8")
        try:
            link.symlink_to(target)
        except OSError as exc:
            self.skipTest(f"symlink недоступен: {type(exc).__name__}")
        report = external_scan.scan(self.root, "semgrep")
        self.assertEqual(report["status"], "partial")
        self.assertEqual(report["results"][0]["issues"][0]["code"], "symlink_in_scan_tree")

    def test_oversized_target_is_not_scanned(self):
        target = self.root / "large.bin"
        with target.open("wb") as stream:
            stream.truncate(external_scan.MAX_FILE_BYTES + 1)
        report = external_scan.scan(self.root, "semgrep")
        self.assertEqual(report["status"], "partial")
        self.assertEqual(report["results"][0]["issues"][0]["code"], "file_size_limit")

    @unittest.skipUnless(shutil.which("semgrep"), "Semgrep не установлен")
    def test_semgrep_local_rules_smoke(self):
        (self.root / "sample.py").write_text(
            "import requests\n"
            "def vulnerable(request):\n"
            "    url = request.args.get('url')\n"
            "    return requests.get(url, timeout=1)\n",
            encoding="utf-8")
        (self.root / "safe.py").write_text(
            "import requests\n"
            "def safe(request):\n"
            "    return requests.get('https://service.invalid', "
            "headers={'X-lookup': request.args.get('id')}, timeout=1)\n",
            encoding="utf-8")
        (self.root / "query.js").write_text(
            "function vulnerable(req, db) {\n"
            "  const sql = req.query.statement;\n"
            "  return db.query(sql);\n"
            "}\n"
            "function safe(req, db) {\n"
            "  return db.query('SELECT * FROM users WHERE id = ?', [req.query.id]);\n"
            "}\n",
            encoding="utf-8")
        report = external_scan.scan(self.root, "semgrep", timeout=60)
        result = report["results"][0]
        self.assertEqual(result["status"], "complete", msg=json.dumps(result, ensure_ascii=False))
        self.assertEqual(len(result["findings"]), 2, msg=json.dumps(result, ensure_ascii=False))
        by_rule = {item["rule_id"].rsplit(".", 1)[-1]: item for item in result["findings"]}
        self.assertIn("request-url-to-http", by_rule)
        self.assertIn("request-to-sql", by_rule)
        self.assertEqual(by_rule["request-url-to-http"]["severity"], "MEDIUM")
        self.assertEqual(by_rule["request-url-to-http"]["source_severity"], "WARNING")
        self.assertEqual(by_rule["request-to-sql"]["severity"], "MEDIUM")

    @unittest.skipUnless(shutil.which("trivy"), "Trivy не установлен")
    def test_trivy_cached_database_smoke_or_explicit_partial(self):
        report = external_scan.scan(self.root, "trivy", timeout=60)
        result = report["results"][0]
        self.assertIn(result["status"], {"complete", "partial"})
        self.assertNotEqual(result["status"], "unavailable")
        self.assertTrue(result.get("version"))
        self.assertTrue(result["scope"]["offline_scan"])
        self.assertFalse(result["scope"]["database_update_enabled"])

    @unittest.skipUnless(shutil.which("trivy"), "Trivy не установлен")
    def test_trivy_builtin_secret_rules_remain_enabled_and_redacted(self):
        canary = "ghp_012345678901234567890123456789012345"
        (self.root / "token.txt").write_text(f"token = {canary}\n", encoding="utf-8")
        report = external_scan.scan(self.root, "trivy", timeout=60)
        result = report["results"][0]
        findings = [item for item in result["findings"]
                    if item["category"] == "secret" and item["rule_id"] == "github-pat"]
        self.assertEqual(len(findings), 1, msg=json.dumps(result, ensure_ascii=False))
        self.assertNotIn(canary, json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    unittest.main()
