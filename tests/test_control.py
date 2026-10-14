"""Команды стенда сохраняют данные и разделяют запуск, пробу и отправку."""

import contextlib
import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

SPEC = importlib.util.spec_from_file_location(
    "control", Path(__file__).resolve().parents[1] / "control.py"
)
control = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = control
SPEC.loader.exec_module(control)


class InitTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / ".env.example").write_text(
            "FARM_PASSWORD=\nNEO_KEY=\nFARM_SYSTEM_TOKEN=\nFARM_SYSTEM_SERVER_KEY=\nFARM_PORT=8080\n",
            encoding="utf-8",
        )

    def run_init(self):
        with (
            contextlib.redirect_stdout(io.StringIO()),
            contextlib.redirect_stderr(io.StringIO()),
        ):
            return control.init(self.root)

    def test_generates_distinct_secrets_and_keeps_manual_settings(self):
        self.assertEqual(self.run_init(), 0)
        values = dict(
            line.split("=", 1) for line in (self.root / ".env").read_text().splitlines()
        )
        for key in ("FARM_PASSWORD", "NEO_KEY"):
            self.assertRegex(values[key], r"^[0-9a-f]{48}$")
        self.assertNotEqual(values["FARM_PASSWORD"], values["NEO_KEY"])
        self.assertEqual(values["FARM_SYSTEM_TOKEN"], "")
        self.assertEqual(values["FARM_SYSTEM_SERVER_KEY"], "")
        self.assertEqual(values["FARM_PORT"], "8080")
        if os.name != "nt":
            self.assertEqual((self.root / ".env").stat().st_mode & 0o777, 0o600)

    def test_keeps_existing_file(self):
        path = self.root / ".env"
        path.write_text("FARM_PASSWORD=existing\n")
        self.assertEqual(self.run_init(), 2)
        self.assertEqual(path.read_text(), "FARM_PASSWORD=existing\n")

    def test_does_not_follow_symlink(self):
        target = self.root / "settings"
        target.write_text("existing")
        (self.root / ".env").symlink_to(target)
        self.assertEqual(self.run_init(), 2)
        self.assertEqual(target.read_text(), "existing")

    def test_keeps_existing_capture(self):
        directory = self.root / "local/pcaps"
        directory.mkdir(parents=True)
        capture = directory / "capture.pcap"
        capture.write_bytes(b"existing capture")
        self.assertEqual(self.run_init(), 0)
        self.assertEqual(capture.read_bytes(), b"existing capture")

    def test_rejects_capture_directory_symlink_before_creating_secrets(self):
        target = self.root / "other"
        target.mkdir()
        (self.root / "local").symlink_to(target, target_is_directory=True)
        with self.assertRaises(OSError):
            self.run_init()
        self.assertFalse((self.root / ".env").exists())
        self.assertEqual(list(target.iterdir()), [])

    def test_init_never_calls_docker(self):
        with (
            patch.object(control.subprocess, "run") as run,
            patch.object(control.subprocess, "call") as call,
        ):
            self.assertEqual(self.run_init(), 0)
        run.assert_not_called()
        call.assert_not_called()


class ControlTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "local/jobs").mkdir(parents=True)
        (self.root / ".env").write_text("FARM_PORT=18080\n")
        (self.root / ".env").chmod(0o600)
        (self.root / "compose.yaml").write_text("name: check\n")
        self.key = "a" * 48
        self.config = {
            "services": {
                "neo": {"environment": {"NEO_GRPC_AUTH_KEY": self.key}},
                "farm": {
                    "environment": {
                        "FARM_FLAG_FORMAT": "FLAG\\{[a-z]+\\}",
                        "FARM_PASSWORD": "b" * 48,
                    }
                },
                "farm-front": {
                    "ports": [{"host_ip": "127.0.0.1", "published": "18080"}]
                },
            }
        }
        self.stdout = io.StringIO()
        self.stderr = io.StringIO()

    def invoke(self, *args, result=0, callback=None):
        config = subprocess.CompletedProcess(
            [], 0, stdout=json.dumps(self.config), stderr=""
        )
        with (
            patch.object(control.subprocess, "run", return_value=config) as run,
            patch.object(
                control.subprocess, "call", return_value=result, side_effect=callback
            ) as call,
            contextlib.redirect_stdout(self.stdout),
            contextlib.redirect_stderr(self.stderr),
        ):
            status = control.main(list(args), root=self.root)
        return status, call, run

    def test_component_start_does_not_launch_other_components(self):
        for component, needed, excluded in (
            ("farm", {"farm-front"}, {"packmate", "neo", "neo-worker", "farm-submit"}),
            (
                "pack",
                {"packmate"},
                {"farm", "farm-front", "neo", "neo-worker", "farm-submit"},
            ),
            (
                "neo",
                {"neo", "neo-worker"},
                {"farm", "farm-front", "packmate", "farm-submit"},
            ),
        ):
            with self.subTest(component=component):
                status, call, _ = self.invoke(component)
                self.assertEqual(status, 0, self.stderr.getvalue())
                command = call.call_args.args[0]
                self.assertTrue(needed.issubset(command))
                self.assertFalse(excluded.intersection(command))
                self.assertIn("up", command)
                self.assertIn("--wait", command)
                self.assertIn("--build", command)

    def test_component_down_stops_its_database_and_keeps_other_components(self):
        for component, services in (
            ("farm", {"farm", "farm-front", "farm-db", "farm-submit"}),
            ("pack", {"packmate", "packmate-db"}),
            ("neo", {"neo", "neo-worker", "neo-redis"}),
        ):
            with self.subTest(component=component):
                status, call, _ = self.invoke(component, "down")
                self.assertEqual(status, 0)
                command = call.call_args.args[0]
                self.assertEqual(set(command[command.index("stop") + 1 :]), services)
                self.assertNotIn("down", command)

    def test_all_start_excludes_submission_and_all_stop_keeps_volumes(self):
        status, call, _ = self.invoke("all-up")
        self.assertEqual(status, 0)
        command = call.call_args.args[0]
        self.assertTrue({"farm-front", "packmate", "neo-worker"}.issubset(command))
        self.assertNotIn("farm-submit", command)
        status, call, _ = self.invoke("all-down")
        self.assertEqual(status, 0)
        command = call.call_args.args[0]
        self.assertIn("down", command)
        self.assertNotIn("-v", command)
        self.assertNotIn("--volumes", command)
        self.assertNotIn("--remove-orphans", command)

    def test_submission_is_an_explicit_action(self):
        status, call, _ = self.invoke("farm", "submit-up")
        self.assertEqual(status, 0)
        self.assertIn("farm-submit", call.call_args.args[0])
        status, call, _ = self.invoke("farm", "submit-down")
        self.assertEqual(status, 0)
        command = call.call_args.args[0]
        self.assertEqual(command[command.index("stop") + 1 :], ["farm-submit"])

    def test_docker_failure_reaches_the_caller(self):
        status, _, _ = self.invoke("pack", result=17)
        self.assertEqual(status, 17)

    def test_help_and_unknown_commands_do_not_start_docker(self):
        for args, expected in (
            (["--help"], 0),
            (["neo", "--help"], 0),
            (["unknown"], 2),
            (["neo", "run"], 2),
            (["farm", "unknown"], 2),
        ):
            with self.subTest(args=args):
                status, call, run = self.invoke(*args)
                self.assertEqual(status, expected)
                call.assert_not_called()
                run.assert_not_called()

    def test_root_and_project_are_explicit(self):
        env_file = self.root / "alternate.env"
        env_file.write_text("FARM_PORT=18081\n")
        env_file.chmod(0o600)
        status, call, _ = self.invoke(
            "--env-file", str(env_file), "--project-name", "control-check", "pack"
        )
        self.assertEqual(status, 0)
        command = call.call_args.args[0]
        self.assertIn(str(self.root / "compose.yaml"), command)
        self.assertIn(str(env_file), command)
        self.assertIn("control-check", command)
        self.assertEqual(Path(call.call_args.kwargs["cwd"]), self.root)

    def test_passes_admin_key_only_via_environment(self):
        status, call, _ = self.invoke("neo", "info")
        self.assertEqual(status, 0, self.stderr.getvalue())
        command = call.call_args.args[0]
        self.assertNotIn(self.key, command)
        self.assertEqual(call.call_args.kwargs["env"]["NEO_GRPC_AUTH_KEY"], self.key)
        self.assertIn("--no-deps", command)
        self.assertNotIn("--entrypoint", command)
        self.assertNotIn(self.key, self.stdout.getvalue() + self.stderr.getvalue())

    def test_local_test_gets_no_key_and_never_starts_servers(self):
        script = self.root / "проба с пробелом.py"
        script.write_text("#!/usr/bin/env python3\nprint('FLAG{test}')\n")
        script.chmod(0o600)
        status, call, _ = self.invoke(
            "neo", "test", str(script), "192.0.2.10", "--timeout", "3s"
        )
        self.assertEqual(status, 0, self.stderr.getvalue())
        command = call.call_args.args[0]
        self.assertIn("--no-deps", command)
        self.assertIn("--rm", command)
        self.assertIn("NEO_GRPC_AUTH_KEY=", command)
        self.assertNotIn(self.key, repr(call.call_args))
        self.assertNotIn("up", command)
        self.assertIn("test", command)
        self.assertIn("192.0.2.10", command)
        self.assertIn("FLAG\\{[a-z]+\\}", command)
        self.assertEqual(script.stat().st_mode & 0o777, 0o600)
        self.assertEqual(list((self.root / "local/jobs").iterdir()), [])

    def test_add_mounts_only_the_requested_file_and_cleans_staging(self):
        script = self.root / "sample.py"
        script.write_text("#!/usr/bin/env python3\nprint('ok')\n")
        script.chmod(0o600)
        observed = []

        def inspect(command, **_):
            mount = command[command.index("-v") + 1]
            host, _, mode = mount.rsplit(":", 2)
            self.assertEqual(mode, "ro")
            folder = Path(host)
            self.assertEqual([p.name for p in folder.iterdir()], [script.name])
            staged = folder / script.name
            self.assertEqual(staged.read_bytes(), script.read_bytes())
            self.assertTrue(staged.stat().st_mode & 0o111)
            observed.append(folder)
            return 0

        status, call, _ = self.invoke(
            "neo", "add", str(script), "--id", "sample", "--disabled", callback=inspect
        )
        self.assertEqual(status, 0, self.stderr.getvalue())
        self.assertIn("--yes", call.call_args.args[0])
        self.assertTrue(observed)
        self.assertFalse(observed[0].exists())
        self.assertEqual(script.stat().st_mode & 0o777, 0o600)

    def test_directory_upload_rejects_links_without_running_the_job(self):
        folder = self.root / "script"
        folder.mkdir()
        script = folder / "main.py"
        script.write_text("#!/usr/bin/env python3\nprint('ok')\n")
        (folder / "secret").symlink_to(self.root / ".env")
        status, call, _ = self.invoke("neo", "add", str(script), "--dir")
        self.assertEqual(status, 2)
        call.assert_not_called()
        self.assertEqual(list((self.root / "local/jobs").iterdir()), [])

    def test_manual_flag_uses_farm_api_and_keeps_password_out_of_output(self):
        opener = MagicMock()
        opener.open.return_value.__enter__.return_value.status = 200
        with patch.object(control, "build_opener", return_value=opener) as build:
            status, call, _ = self.invoke("farm", "flag", "FLAG{test}")
        self.assertEqual(status, 0, self.stderr.getvalue())
        call.assert_not_called()
        request = opener.open.call_args.args[0]
        self.assertEqual(request.full_url, "http://127.0.0.1:18080/api/post_flags")
        self.assertEqual(
            json.loads(request.data),
            [{"flag": "FLAG{test}", "sploit": "Manual", "team": "*"}],
        )
        self.assertEqual(request.get_header("Authorization"), "b" * 48)
        self.assertEqual(request.get_header("X-token"), "b" * 48)
        self.assertEqual(build.call_args.args[0].proxies, {})
        self.assertNotIn("b" * 48, self.stdout.getvalue() + self.stderr.getvalue())

    def test_manual_flag_requires_team_for_named_script(self):
        status, call, run = self.invoke("farm", "flag", "FLAG{test}", "--script", "job")
        self.assertEqual(status, 2)
        call.assert_not_called()
        run.assert_not_called()

    def test_farm_rejection_does_not_echo_flag_or_password(self):
        opener = MagicMock()
        opener.open.side_effect = control.HTTPError(
            "http://127.0.0.1", 403, "Forbidden", {}, None
        )
        with patch.object(control, "build_opener", return_value=opener):
            status, _, _ = self.invoke("farm", "flag", "FLAG{test}")
        self.assertEqual(status, 2)
        self.assertIn("HTTP 403", self.stderr.getvalue())
        self.assertNotIn("FLAG{test}", self.stdout.getvalue() + self.stderr.getvalue())
        self.assertNotIn("b" * 48, self.stdout.getvalue() + self.stderr.getvalue())
