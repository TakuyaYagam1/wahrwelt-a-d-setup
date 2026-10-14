#!/usr/bin/env python3
"""Локальный запуск Semgrep и Trivy без сборки анализируемого проекта."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import selectors
import shutil
import signal
import stat
import subprocess
import sys
import time
import unicodedata


SCHEMA = "wahrwelt.external-scan.v1"
ROOT = Path(__file__).resolve().parents[1]
SEMGREP_RULES = ROOT / "rules" / "semgrep.yml"
MAX_OUTPUT = 16 * 1024 * 1024
MAX_FINDINGS = 10000
MAX_RULE_BYTES = 1024 * 1024
MAX_TREE_FILES = 100000
MAX_FILE_BYTES = 16 * 1024 * 1024
MAX_TREE_BYTES = 1024 * 1024 * 1024
MAX_PREFLIGHT_ISSUES = 1000
SKIP_DIRS = {
    ".git", ".hg", ".svn", ".venv", "venv", "node_modules", "vendor",
    "__pycache__", ".cache", ".ssh", ".aws", "artifacts", "dist", "build",
    "target", "obj", "bin",
}
SEVERITIES = {"INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL", "UNKNOWN"}
SEMGREP_SEVERITIES = {"INFO": "INFO", "WARNING": "MEDIUM", "ERROR": "HIGH"}


def _canonical_json(value):
    return json.dumps(value, ensure_ascii=True, sort_keys=True,
                      separators=(",", ":")).encode("utf-8")


def _digest(value):
    return hashlib.sha256(value).hexdigest()


def _now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _positive(value):
    try:
        number = int(value)
    except (TypeError, ValueError) as exc:
        raise argparse.ArgumentTypeError("ожидалось целое число") from exc
    if number < 1 or number > 1800:
        raise argparse.ArgumentTypeError("значение должно быть от 1 до 1800")
    return number


def canonical_root(value):
    requested = Path(os.path.abspath(os.fspath(value)))
    metadata = requested.lstat()
    if stat.S_ISLNK(metadata.st_mode):
        raise ValueError("корень scan не может быть символической ссылкой")
    canonical = requested.resolve(strict=True)
    canonical_metadata = canonical.lstat()
    if (metadata.st_dev, metadata.st_ino, stat.S_IFMT(metadata.st_mode)) != (
            canonical_metadata.st_dev, canonical_metadata.st_ino,
            stat.S_IFMT(canonical_metadata.st_mode)):
        raise ValueError("корень изменился при проверке")
    if not (stat.S_ISDIR(canonical_metadata.st_mode) or stat.S_ISREG(canonical_metadata.st_mode)):
        raise ValueError("корень должен быть обычным файлом или каталогом")
    return canonical


def _root_id(root):
    normalized = unicodedata.normalize("NFC", os.path.normcase(str(root)))
    return "sha256:" + _digest(("wahrwelt-root-v1\0" + normalized).encode(
        "utf-8", errors="surrogatepass"))


def _rule_pack_digest():
    metadata = SEMGREP_RULES.lstat()
    if not stat.S_ISREG(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
        raise ValueError("локальный набор Semgrep должен быть обычным файлом")
    if metadata.st_size > MAX_RULE_BYTES:
        raise ValueError("локальный набор Semgrep слишком велик")
    data = SEMGREP_RULES.read_bytes()
    if len(data) != metadata.st_size:
        raise ValueError("локальный набор Semgrep изменился при чтении")
    return _digest(data)


def _preflight(root):
    """Останавливает внешний scan, если дерево выходит за границу root."""
    if root.is_file():
        try:
            size = root.stat().st_size
        except OSError:
            return [_issue("file_unreadable", path=".")]
        if size > MAX_FILE_BYTES:
            return [_issue("file_size_limit", path=".", limit=MAX_FILE_BYTES)]
        return []
    issues = []
    file_count = 0
    total_bytes = 0

    def add_issue(issue):
        if len(issues) < MAX_PREFLIGHT_ISSUES:
            issues.append(issue)

    def onerror(exc):
        name = Path(getattr(exc, "filename", root))
        try:
            rel = name.relative_to(root).as_posix()
        except ValueError:
            rel = "."
        add_issue({"code": "directory_unreadable", "path": rel})

    for base, dirs, files in os.walk(root, topdown=True, followlinks=False, onerror=onerror):
        kept = []
        for name in sorted(dirs):
            if name in SKIP_DIRS:
                continue
            path = Path(base) / name
            if path.is_symlink():
                add_issue({"code": "symlink_in_scan_tree", "path": path.relative_to(root).as_posix()})
            else:
                kept.append(name)
        dirs[:] = kept
        for name in sorted(files):
            if name in SKIP_DIRS:
                continue
            path = Path(base) / name
            try:
                metadata = path.lstat()
            except OSError:
                add_issue({"code": "file_unreadable", "path": path.relative_to(root).as_posix()})
                continue
            if stat.S_ISLNK(metadata.st_mode):
                add_issue({"code": "symlink_in_scan_tree", "path": path.relative_to(root).as_posix()})
            elif not stat.S_ISREG(metadata.st_mode):
                add_issue({"code": "non_regular_file", "path": path.relative_to(root).as_posix()})
            else:
                file_count += 1
                total_bytes += metadata.st_size
                rel = path.relative_to(root).as_posix()
                if metadata.st_size > MAX_FILE_BYTES:
                    add_issue({"code": "file_size_limit", "path": rel, "limit": MAX_FILE_BYTES})
                if file_count > MAX_TREE_FILES:
                    add_issue({"code": "file_count_limit", "limit": MAX_TREE_FILES})
                    return issues
                if total_bytes > MAX_TREE_BYTES:
                    add_issue({"code": "tree_size_limit", "limit": MAX_TREE_BYTES})
                    return issues
        if any(issue["code"] == "directory_unreadable" for issue in issues):
            break
        if len(issues) >= MAX_PREFLIGHT_ISSUES:
            break
    return issues


def _has_semgrepignore(root):
    if root.is_file():
        return False
    for base, dirs, files in os.walk(root, topdown=True, followlinks=False):
        dirs[:] = [name for name in dirs if name not in SKIP_DIRS]
        if ".semgrepignore" in files:
            return True
    return False


def _environment():
    env = {}
    for name in ("PATH", "HOME", "TMPDIR", "TEMP", "TMP", "LANG", "LC_ALL"):
        if name in os.environ:
            env[name] = os.environ[name]
    env.setdefault("PATH", os.defpath)
    env.setdefault("HOME", str(Path.home()))
    env["LANG"] = "C"
    env["LC_ALL"] = "C"
    env["SEMGREP_SEND_METRICS"] = "off"
    env["SEMGREP_ENABLE_VERSION_CHECK"] = "0"
    return env


def _terminate(proc):
    try:
        os.killpg(proc.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    except OSError:
        try:
            proc.terminate()
        except OSError:
            pass
    try:
        proc.wait(timeout=0.5)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        except OSError:
            try:
                proc.kill()
            except OSError:
                pass
        try:
            proc.wait(timeout=1)
        except subprocess.TimeoutExpired:
            pass


def run_command(argv, *, cwd, timeout, output_limit=MAX_OUTPUT):
    """Запускает только argv без shell, ограничивая время и суммарный вывод."""
    if os.name != "posix":
        return {"status": "unsupported_platform", "returncode": None,
                "stdout": b"", "stderr": b"", "stdout_bytes": 0, "stderr_bytes": 0}
    try:
        proc = subprocess.Popen(
            list(argv), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, cwd=str(cwd), env=_environment(),
            close_fds=True, start_new_session=True, shell=False,
        )
    except FileNotFoundError:
        return {"status": "unavailable", "returncode": None,
                "stdout": b"", "stderr": b"", "stdout_bytes": 0, "stderr_bytes": 0}
    except OSError:
        return {"status": "launch_error", "returncode": None,
                "stdout": b"", "stderr": b"", "stdout_bytes": 0, "stderr_bytes": 0}

    selector = selectors.DefaultSelector()
    buffers = {"stdout": bytearray(), "stderr": bytearray()}
    sizes = {"stdout": 0, "stderr": 0}
    status = "complete"
    deadline = time.monotonic() + timeout
    try:
        for name, stream in (("stdout", proc.stdout), ("stderr", proc.stderr)):
            os.set_blocking(stream.fileno(), False)
            selector.register(stream, selectors.EVENT_READ, name)
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                status = "timeout"
                _terminate(proc)
                break
            events = selector.select(min(remaining, 0.25))
            for key, _ in events:
                name = key.data
                try:
                    chunk = os.read(key.fileobj.fileno(), 65536)
                except BlockingIOError:
                    continue
                if not chunk:
                    selector.unregister(key.fileobj)
                    key.fileobj.close()
                    continue
                sizes[name] += len(chunk)
                if sizes["stdout"] + sizes["stderr"] > output_limit:
                    status = "output_limit_exceeded"
                    _terminate(proc)
                    break
                buffers[name].extend(chunk)
            if status != "complete":
                break
        if proc.poll() is None:
            try:
                proc.wait(timeout=max(0.0, deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                status = "timeout"
                _terminate(proc)
    finally:
        selector.close()
        for stream in (proc.stdout, proc.stderr):
            try:
                stream.close()
            except OSError:
                pass
        if proc.poll() is None:
            _terminate(proc)
    return {"status": status, "returncode": proc.returncode,
            "stdout": bytes(buffers["stdout"]), "stderr": bytes(buffers["stderr"]),
            "stdout_bytes": sizes["stdout"], "stderr_bytes": sizes["stderr"]}


def _safe_version(data):
    text = data.decode("utf-8", errors="replace")
    match = re.search(r"(?<![0-9])([0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?)", text)
    return match.group(1) if match else "unknown"


def _tool(binary, cwd, timeout=10):
    path = shutil.which(binary)
    if not path:
        return None, {"status": "unavailable", "version": None, "state": {}}
    try:
        resolved = Path(path).resolve(strict=True)
        metadata = resolved.stat()
    except OSError:
        return None, {"status": "unavailable", "version": None, "state": {}}
    if not stat.S_ISREG(metadata.st_mode) or not os.access(resolved, os.X_OK):
        return None, {"status": "unavailable", "version": None, "state": {}}
    result = run_command([str(resolved), "--version"], cwd=cwd, timeout=timeout,
                         output_limit=8192)
    if result["status"] != "complete" or result["returncode"] != 0:
        return str(resolved), {"status": "version_error", "version": None, "state": {}}
    output = result["stdout"].decode("utf-8", errors="replace")
    return str(resolved), {"status": "complete", "version": _safe_version(result["stdout"]),
                           "output": output}


def _base_result(engine, status="partial", *, version=None, findings=None, issues=None, scope=None):
    return {
        "engine": engine,
        "version": version,
        "status": status,
        "findings": findings or [],
        "issues": issues or [],
        "scope": scope or {},
    }


def _issue(code, **fields):
    return {"code": code, **fields}


def _relative_path(value, root):
    if not isinstance(value, str) or not value:
        return None
    candidate = Path(value)
    try:
        if candidate.is_absolute():
            relative = candidate.resolve(strict=False).relative_to(root).as_posix()
        else:
            relative = PurePosixPath(value.replace("\\", "/")).as_posix()
    except (OSError, ValueError):
        return None
    pure = PurePosixPath(relative)
    if pure.is_absolute() or ".." in pure.parts:
        return None
    return relative or "."


def _semgrep_finding(item, root):
    extra = item.get("extra") if isinstance(item, dict) else None
    if not isinstance(extra, dict):
        return None
    start = item.get("start")
    if not isinstance(start, dict):
        return None
    path = _relative_path(item.get("path"), root)
    line = start.get("line")
    rule_id = item.get("check_id")
    if (path is None or type(line) is not int or line < 1 or
            not isinstance(rule_id, str) or len(rule_id) > 200):
        return None
    source_severity = str(extra.get("severity", "UNKNOWN")).upper()
    severity = SEMGREP_SEVERITIES.get(source_severity, "UNKNOWN")
    metadata = extra.get("metadata") if isinstance(extra.get("metadata"), dict) else {}
    cwe = metadata.get("cwe", [])
    if isinstance(cwe, str):
        cwe = [cwe]
    if not isinstance(cwe, list):
        cwe = []
    cwe = [item for item in cwe if isinstance(item, str) and re.fullmatch(r"CWE-[1-9][0-9]*", item)][:8]
    title = extra.get("message", rule_id)
    if not isinstance(title, str):
        title = rule_id
    title = "".join(ch for ch in title if ch.isprintable())[:300] or rule_id
    return {"engine": "semgrep", "rule_id": rule_id, "path": path, "line": line,
            "severity": severity if severity in SEVERITIES else "UNKNOWN",
            "source_severity": source_severity, "confidence": "review",
            "title": title, "cwe": cwe}


def _semgrep(root, root_id, timeout, local_rules_sha):
    binary, tool = _tool("semgrep", ROOT)
    if binary is None or tool["status"] != "complete":
        code = "binary_unavailable" if binary is None else "version_unavailable"
        return _base_result("semgrep", "unavailable" if binary is None else "partial",
                            issues=[_issue(code)])
    if tool["version"] == "unknown":
        return _base_result("semgrep", "partial", issues=[_issue("version_unreadable")])
    scope_body = {"root": root_id, "engine": "semgrep", "version": tool["version"],
                  "rules_sha256": local_rules_sha, "path_semantics": "root-relative-posix.v1",
                  "max_target_bytes": MAX_FILE_BYTES, "local_config": True}
    scope = {**scope_body, "sha256": _digest(_canonical_json(scope_body))}
    argv = [binary, "scan", "--config", str(SEMGREP_RULES), "--oss-only",
            "--metrics=off", "--disable-version-check", "--disable-nosem",
            "--no-git-ignore", f"--max-target-bytes={MAX_FILE_BYTES}", "--json"]
    for name in sorted(SKIP_DIRS):
        argv.extend(["--exclude", name])
    argv.append(str(root))
    run = run_command(argv, cwd=ROOT, timeout=timeout)
    if run["status"] != "complete":
        return _base_result("semgrep", "partial", version=tool["version"], scope=scope,
                            issues=[_issue(run["status"], stdout_bytes=run["stdout_bytes"],
                                           stderr_bytes=run["stderr_bytes"])])
    try:
        data = json.loads(run["stdout"].decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError, RecursionError, MemoryError):
        return _base_result("semgrep", "partial", version=tool["version"], scope=scope,
                            issues=[_issue("invalid_json", returncode=run["returncode"])])
    if not isinstance(data, dict) or not isinstance(data.get("results"), list):
        return _base_result("semgrep", "partial", version=tool["version"], scope=scope,
                            issues=[_issue("invalid_report_shape", returncode=run["returncode"])])
    findings = []
    issues = []
    for item in data["results"]:
        finding = _semgrep_finding(item, root)
        if finding is None:
            issues.append(_issue("invalid_finding"))
        elif len(findings) < MAX_FINDINGS:
            findings.append(finding)
        else:
            issues.append(_issue("finding_limit_reached", limit=MAX_FINDINGS))
            break
    errors = data.get("errors", [])
    if isinstance(errors, list) and errors:
        issues.append(_issue("semgrep_report_errors", count=len(errors)))
    elif not isinstance(errors, list):
        issues.append(_issue("invalid_error_list"))
    paths = data.get("paths", {})
    if isinstance(paths, dict):
        scanned = paths.get("scanned", [])
        skipped = paths.get("skipped", [])
        if isinstance(skipped, list) and skipped:
            issues.append(_issue("semgrep_skipped_paths", count=len(skipped)))
        if not isinstance(scanned, list) or not isinstance(skipped, list):
            issues.append(_issue("invalid_path_summary"))
    else:
        scanned = []
        issues.append(_issue("missing_path_summary"))
    if run["returncode"] not in (0, 1) or (run["returncode"] == 1 and not findings):
        issues.append(_issue("scanner_exit", returncode=run["returncode"]))
    if _has_semgrepignore(root):
        issues.append(_issue("target_semgrepignore_present"))
    status = "partial" if issues else "complete"
    return {**_base_result("semgrep", status, version=tool["version"], findings=findings,
                           issues=issues, scope=scope),
            "files_scanned": len(scanned) if isinstance(scanned, list) else 0}


def _parse_time(value):
    if not isinstance(value, str):
        return None
    match = re.match(r"^(\d{4}-\d\d-\d\d) (\d\d:\d\d:\d\d)(?:\.(\d+))? ([+-]\d{4})", value)
    if not match:
        return None
    fraction = (match.group(3) or "")[:6].ljust(6, "0")
    normalized = f"{match.group(1)} {match.group(2)}.{fraction} {match.group(4)}"
    try:
        return datetime.strptime(normalized, "%Y-%m-%d %H:%M:%S.%f %z").astimezone(timezone.utc)
    except ValueError:
        return None


def _trivy_state(text):
    state = {}
    key_names = {"updatedat": "updated_at", "nextupdate": "next_update",
                 "downloadedat": "downloaded_at"}
    current = None
    for raw in text.splitlines():
        line = raw.strip()
        if line in {"Vulnerability DB:", "Java DB:", "Check Bundle:"}:
            current = line[:-1].lower().replace(" ", "_")
            state[current] = {}
        elif current and ":" in line:
            key, value = line.split(":", 1)
            normalized = key.strip().lower().replace(" ", "_")
            normalized = key_names.get(normalized.replace("_", ""), normalized)
            state[current][normalized] = value.strip()
    return state


def _has_java_artifacts(root):
    if root.is_file():
        return root.name.lower() in {"pom.xml", "gradle.lockfile", "build.gradle", "build.gradle.kts"} or root.suffix.lower() == ".jar"
    for base, dirs, files in os.walk(root, topdown=True, followlinks=False):
        dirs[:] = [name for name in dirs if name not in SKIP_DIRS]
        if any(name.lower() in {"pom.xml", "gradle.lockfile", "build.gradle", "build.gradle.kts"}
               or name.lower().endswith(".jar") for name in files):
            return True
    return False


def _trivy_finding(item, kind, root):
    if not isinstance(item, dict):
        return None
    if kind == "vulnerability":
        rule_id = item.get("VulnerabilityID")
        title = item.get("Title") or item.get("PkgName") or rule_id
        severity = item.get("Severity", "UNKNOWN")
        line = None
        category = "dependency"
    elif kind == "misconfiguration":
        rule_id = item.get("ID") or item.get("AVDID")
        title = item.get("Title") or rule_id
        severity = item.get("Severity", "UNKNOWN")
        cause = item.get("CauseMetadata")
        line = cause.get("StartLine") if isinstance(cause, dict) else None
        category = "misconfiguration"
    else:
        rule_id = item.get("RuleID")
        title = item.get("Title") or rule_id
        severity = item.get("Severity", "UNKNOWN")
        line = item.get("StartLine")
        category = "secret"
    if not isinstance(rule_id, str) or len(rule_id) > 200:
        return None
    target = item.get("Target", item.get("PkgPath", "."))
    path = _relative_path(target, root)
    if path is None:
        return None
    if type(line) is not int or line < 1:
        line = None
    if not isinstance(title, str):
        title = rule_id
    title = "".join(ch for ch in title if ch.isprintable())[:300] or rule_id
    severity = str(severity).upper()
    finding = {"engine": "trivy", "category": category, "rule_id": rule_id,
               "path": path, "line": line,
               "severity": severity if severity in SEVERITIES else "UNKNOWN",
               "confidence": "review", "title": title}
    if kind == "vulnerability":
        for field, key, limit in (("package", "PkgName", 200),
                                  ("installed_version", "InstalledVersion", 100),
                                  ("fixed_version", "FixedVersion", 100)):
            value = item.get(key)
            if isinstance(value, str):
                value = "".join(ch for ch in value if ch.isprintable())[:limit].strip()
                if value:
                    finding[field] = value
        primary_url = item.get("PrimaryURL")
        if (isinstance(primary_url, str) and len(primary_url) <= 500 and
                re.fullmatch(r"https?://[^\s\x00-\x1f]+", primary_url)):
            finding["primary_url"] = primary_url
    return finding


def _trivy(root, root_id, timeout, update_db):
    binary, tool = _tool("trivy", ROOT)
    if binary is None or tool["status"] != "complete":
        code = "binary_unavailable" if binary is None else "version_unavailable"
        return _base_result("trivy", "unavailable" if binary is None else "partial",
                            issues=[_issue(code)])
    version_text = tool.get("output", "")
    version_state = _trivy_state(version_text)
    state_digest = _digest(version_text.encode("utf-8", errors="replace"))
    scope_body = {"root": root_id, "engine": "trivy", "version": tool["version"],
                  "tool_state_sha256": state_digest, "scanners": ["vuln", "misconfig", "secret"],
                  "path_semantics": "root-relative-posix.v1", "offline_scan": True,
                  "database_update_enabled": update_db}
    scope = {**scope_body, "sha256": _digest(_canonical_json(scope_body))}
    argv = [binary, "fs", "--format", "json", "--quiet", "--config", os.devnull,
            "--scanners", "vuln,misconfig,secret", "--offline-scan",
            "--disable-telemetry", "--skip-version-check", "--skip-vex-repo-update",
            "--ignorefile", os.devnull, "--secret-config="]
    if not update_db:
        argv.extend(["--skip-db-update", "--skip-java-db-update", "--skip-check-update"])
    argv.extend(["--skip-dirs", ",".join(sorted(SKIP_DIRS))])
    argv.append(str(root))
    run = run_command(argv, cwd=ROOT, timeout=timeout)
    if run["status"] != "complete":
        return _base_result("trivy", "partial", version=tool["version"], scope=scope,
                            issues=[_issue(run["status"], stdout_bytes=run["stdout_bytes"],
                                           stderr_bytes=run["stderr_bytes"])],
                            findings=[])
    try:
        data = json.loads(run["stdout"].decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError, RecursionError, MemoryError):
        return _base_result("trivy", "partial", version=tool["version"], scope=scope,
                            issues=[_issue("invalid_json", returncode=run["returncode"])])
    if not isinstance(data, dict):
        return _base_result("trivy", "partial", version=tool["version"], scope=scope,
                            issues=[_issue("invalid_report_shape", returncode=run["returncode"])])
    schema_version = data.get("SchemaVersion")
    if type(schema_version) is not int:
        return _base_result("trivy", "partial", version=tool["version"], scope=scope,
                            issues=[_issue("missing_schema_version", returncode=run["returncode"])])
    if schema_version != 2:
        return _base_result("trivy", "partial", version=tool["version"], scope=scope,
                            issues=[_issue("unsupported_schema_version", version=schema_version)])
    if (not isinstance(data.get("Trivy"), dict) or
            not isinstance(data["Trivy"].get("Version"), str) or
            not isinstance(data.get("Results"), (list, type(None)))):
        return _base_result("trivy", "partial", version=tool["version"], scope=scope,
                            issues=[_issue("invalid_report_shape", returncode=run["returncode"])])
    findings = []
    issues = []
    for result in data.get("Results") or []:
        if not isinstance(result, dict):
            issues.append(_issue("invalid_result"))
            continue
        target = result.get("Target", ".")
        kind_keys = (("Vulnerabilities", "vulnerability"),
                     ("Misconfigurations", "misconfiguration"),
                     ("Secrets", "secret"))
        for key, kind in kind_keys:
            entries = result.get(key) or []
            if not isinstance(entries, list):
                issues.append(_issue("invalid_finding_list", category=kind))
                continue
            for item in entries:
                normalized = _trivy_finding({**item, "Target": target}, kind, root) if isinstance(item, dict) else None
                if normalized is None:
                    issues.append(_issue("invalid_finding", category=kind))
                elif len(findings) < MAX_FINDINGS:
                    findings.append(normalized)
                else:
                    issues.append(_issue("finding_limit_reached", limit=MAX_FINDINGS))
                    break
    if run["returncode"] != 0:
        issues.append(_issue("scanner_exit", returncode=run["returncode"]))
    if update_db:
        post = run_command([binary, "--version"], cwd=ROOT, timeout=10, output_limit=8192)
        if post["status"] == "complete" and post["returncode"] == 0:
            version_state = _trivy_state(post["stdout"].decode("utf-8", errors="replace"))
            state_digest = _digest(post["stdout"])
            scope["tool_state_sha256"] = state_digest
            scope_body["tool_state_sha256"] = state_digest
            scope["sha256"] = _digest(_canonical_json(scope_body))
        else:
            issues.append(_issue("database_state_unavailable"))
    required = ["vulnerability_db", "check_bundle"]
    if _has_java_artifacts(root):
        required.append("java_db")
    for key in required:
        entry = version_state.get(key)
        if not entry:
            issues.append(_issue("database_metadata_missing", database=key))
            continue
        next_update = _parse_time(entry.get("next_update"))
        if not update_db and next_update is not None and next_update <= datetime.now(timezone.utc):
            issues.append(_issue("database_expired", database=key,
                                 next_update=entry.get("next_update")))
        if key == "check_bundle" and not entry.get("digest", "").startswith("sha256:"):
            issues.append(_issue("checks_bundle_missing"))
    status = "partial" if issues else "complete"
    return {**_base_result("trivy", status, version=tool["version"], findings=findings,
                           issues=issues, scope=scope),
            "data": {key: value for key, value in version_state.items() if key in required}}


def scan(root, engine="all", *, timeout=180, update_trivy_db=False):
    if engine not in {"semgrep", "trivy", "all"}:
        raise ValueError("неизвестный движок scan")
    root = canonical_root(root)
    root_id = _root_id(root)
    selected = ["semgrep", "trivy"] if engine == "all" else [engine]
    report = {"schema": SCHEMA, "created_at": _now(), "root": root_id,
              "network": "trivy-db-update-enabled" if update_trivy_db else "disabled",
              "status": "complete", "results": [], "exit_code": 0}
    try:
        rules_sha = _rule_pack_digest() if "semgrep" in selected else None
    except (OSError, ValueError):
        rules_sha = None
    preflight = _preflight(root)
    for name in selected:
        if preflight:
            result = _base_result(name, "partial", issues=preflight)
        elif name == "semgrep":
            if rules_sha is None:
                result = _base_result(name, "partial", issues=[_issue("local_rules_unavailable")])
            else:
                result = _semgrep(root, root_id, timeout, rules_sha)
        elif name == "trivy":
            result = _trivy(root, root_id, timeout, update_trivy_db)
        else:
            result = _base_result(name, "unavailable", issues=[_issue("unknown_engine")])
        report["results"].append(result)
    if any(result["status"] != "complete" for result in report["results"]):
        report["status"] = "partial"
        report["exit_code"] = 2
    elif any(result["findings"] for result in report["results"]):
        report["exit_code"] = 1
    return report


def _terminal(value):
    text = str(value)
    return json.dumps(text, ensure_ascii=True)[1:-1] if not text.isprintable() else text


def _issue_text(issue):
    messages = {
        "binary_unavailable": "исполняемый файл движка не найден",
        "version_unavailable": "не удалось получить версию движка",
        "version_unreadable": "версия движка не распознана",
        "timeout": "превышен лимит времени",
        "output_limit_exceeded": "превышен лимит вывода процесса",
        "invalid_json": "движок вернул некорректный JSON",
        "invalid_report_shape": "структура отчета движка не распознана",
        "missing_schema_version": "в отчете Trivy отсутствует версия формата",
        "unsupported_schema_version": "версия формата отчета Trivy не поддерживается",
        "scanner_exit": "движок завершился с ошибкой",
        "database_expired": "локальная база Trivy просрочена",
        "database_metadata_missing": "нет метаданных локальной базы Trivy",
        "checks_bundle_missing": "локальный набор Trivy checks не найден",
        "symlink_in_scan_tree": "дерево содержит символическую ссылку",
        "target_semgrepignore_present": "Semgrep ignore-файл может скрыть часть дерева",
        "file_size_limit": "файл превышает допустимый размер",
        "file_count_limit": "дерево содержит слишком много файлов",
        "tree_size_limit": "дерево превышает допустимый общий размер",
        "directory_unreadable": "часть каталогов недоступна для чтения",
        "file_unreadable": "часть файлов недоступна для чтения",
        "local_rules_unavailable": "локальные правила Semgrep недоступны",
        "unknown_engine": "запрошен неизвестный движок",
    }
    code = issue.get("code", "unknown") if isinstance(issue, dict) else "unknown"
    return messages.get(code, "неполный результат или ошибка движка")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", help="локальный файл или каталог с исходниками")
    parser.add_argument("--engine", choices=("semgrep", "trivy", "all"), default="all",
                        help="движок scan; по умолчанию запускаются оба")
    parser.add_argument("--timeout", type=_positive, default=180,
                        help="лимит времени для одного процесса, секунды (1-1800)")
    parser.add_argument("--update-trivy-db", action="store_true",
                        help="разрешить Trivy обновить локальные базы через сеть")
    parser.add_argument("--json", action="store_true", help="вывести версионированный JSON-отчет")
    args = parser.parse_args(argv)
    if args.update_trivy_db and args.engine == "semgrep":
        parser.error("--update-trivy-db применим только к Trivy")
    try:
        report = scan(args.root, args.engine, timeout=args.timeout,
                      update_trivy_db=args.update_trivy_db)
    except (OSError, ValueError, RecursionError, MemoryError) as exc:
        report = {"schema": SCHEMA, "created_at": _now(), "root": None,
                  "network": "trivy-db-update-enabled" if args.update_trivy_db else "disabled",
                  "status": "partial", "results": [],
                  "issues": [_issue("invalid_scan_input", reason=type(exc).__name__)],
                  "exit_code": 2}
    if args.json:
        print(json.dumps(report, ensure_ascii=True, indent=2))
    else:
        for result in report["results"]:
            for finding in result["findings"]:
                location = finding["path"] + (":" + str(finding["line"]) if finding.get("line") else "")
                details = []
                if finding.get("package"):
                    package = finding["package"]
                    installed = finding.get("installed_version", "")
                    fixed = finding.get("fixed_version", "")
                    version = f" {installed}" if installed else ""
                    if fixed:
                        version += f" -> исправить до {fixed}"
                    details.append(f"пакет {package}{version}")
                if finding.get("primary_url"):
                    details.append(f"источник {finding['primary_url']}")
                suffix = " (" + "; ".join(_terminal(value) for value in details) + ")" if details else ""
                print(f"{_terminal(location)} [{finding['engine']}:{_terminal(finding['rule_id'])}] "
                      f"{finding['severity']} {_terminal(finding['title'])}{suffix}")
            for issue in result["issues"]:
                code = issue.get("code", "unknown")
                print(f"Проблема: {result['engine']} - {_issue_text(issue)} ({_terminal(code)})")
            status = {"complete": "полный", "partial": "частичный", "unavailable": "недоступен"}.get(
                result["status"], "неизвестен")
            print(f"Утилита: {result['engine']}; результат: {status}; находок: {len(result['findings'])}")
        if report.get("issues"):
            for issue in report["issues"]:
                code = issue.get("code", "unknown")
                print(f"Проблема: {_issue_text(issue)} ({_terminal(code)})")
        status = {"complete": "полный", "partial": "частичный"}.get(report["status"], "неизвестен")
        print(f"Итог: {status}; код завершения: {report['exit_code']}")
        print("Все находки требуют ручной проверки; чистый отчет не доказывает отсутствие уязвимостей.")
    return report["exit_code"]


if __name__ == "__main__":
    raise SystemExit(main())
