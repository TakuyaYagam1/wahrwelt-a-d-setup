#!/usr/bin/env python3
"""Управление сервисами и локальными сканерами wahrwelt."""

import json
import os
import re
import secrets
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener

ROOT = Path(__file__).resolve().parent
COMMANDS = {
    "scan": ROOT / "scripts" / "defense_scan.py",
    "scan-external": ROOT / "scripts" / "external_scan.py",
}
GENERATED_SECRETS = {
    "FARM_PASSWORD",
    "FARM_DB_PASSWORD",
    "NEO_KEY",
    "NEO_WORKER_KEY",
    "PACKMATE_PASSWORD",
    "PACKMATE_DB_PASSWORD",
}
COMPONENT_SERVICES = {
    "farm": ("farm-db", "farm", "farm-front"),
    "packmate": ("packmate-db", "packmate"),
    "neo": ("neo-redis", "neo", "neo-worker"),
}
ADMIN_COMMANDS = {"info", "update", "enable", "disable", "single", "tail", "broadcast"}
MAX_STAGE_BYTES = 512 * 1024 * 1024
MAX_STAGE_ENTRIES = 10_000
DEFAULT_FLAG_FORMAT = r"[A-Z0-9]{31}="


def usage() -> str:
    return (
        "Использование: ./control [--env-file PATH] [--project-name NAME] КОМАНДА\n"
        "\n"
        "Команды: init, up, down, all-up, all-down, restart, logs, status, farm, pack, neo, scan, scan-external\n"
        "\n"
        "Сервисы: ./control farm|pack|neo [up|down|restart|logs|status]\n"
        "Все сервисы: ./control up|down|restart|logs|status\n"
        "Farm: ./control farm flag FLAG [--team NAME] [--script NAME]; submit-up|submit-down\n"
        "Neo: ./control neo test|add|info|update|enable|disable|single|tail|broadcast\n"
        "Инициализация: ./control init\n"
        "Сканеры: ./control scan PATH [параметры], ./control scan-external PATH [параметры]"
    )


def neo_usage() -> str:
    return (
        "Neo: test FILE HOST [--timeout 30s] [--dir]; add FILE [--id NAME] [--dir] "
        "[--disabled] [--interval 30s] [--timeout 30s] [--endless]; "
        "info, update, enable, disable, single, tail, broadcast"
    )


def init_usage() -> str:
    return "Использование: ./control init. Создает .env и локальные каталоги; Docker не запускается."


def component_usage(component: str) -> str:
    if component == "farm":
        return (
            "Farm: up, down, restart, logs, status, flag FLAG, submit-up, submit-down"
        )
    if component in {"pack", "packmate"}:
        return "Packmate: up, down, restart, logs, status"
    return "Neo: up, down, restart, logs, status, test, add, info, update, enable, disable, single, tail, broadcast"


def init(root: Path = ROOT) -> int:
    """Создать приватный .env и нужные каталоги, не запуская Docker."""
    root = Path(root).resolve()
    template_path = root / ".env.example"
    template = template_path.read_text(encoding="utf-8")

    for directory in (
        root / "local",
        root / "local/pcaps",
        root / "local/rsa_keys",
        root / "local/jobs",
    ):
        if directory.is_symlink() or (directory.exists() and not directory.is_dir()):
            raise OSError("путь для локальных данных должен быть обычным каталогом")
    capture = root / "local/pcaps/capture.pcap"
    if capture.is_symlink() or (capture.exists() and not capture.is_file()):
        raise OSError("путь захвата должен быть обычным файлом")

    value = re.sub(
        r"^([A-Z][A-Z0-9_]*(?:_PASSWORD|_KEY))=$",
        lambda match: (
            f"{match[1]}={secrets.token_hex(24)}"
            if match[1] in GENERATED_SECRETS
            else match[0]
        ),
        template,
        flags=re.MULTILINE,
    )
    try:
        descriptor = os.open(root / ".env", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        print("Файл .env уже существует. Его содержимое сохранено.", file=sys.stderr)
        return 2
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        stream.write(value)

    for directory, mode in (
        (root / "local", 0o700),
        (root / "local/pcaps", 0o755),
        (root / "local/rsa_keys", 0o755),
        (root / "local/jobs", 0o755),
    ):
        if directory.is_symlink():
            raise OSError("локальный каталог не должен быть символической ссылкой")
        directory.mkdir(mode=mode, exist_ok=True)

    try:
        descriptor = os.open(capture, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
    except FileExistsError:
        pass
    else:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(struct.pack("<IHHIIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, 1))

    print(
        "Создан .env. Перед запуском отправки флагов настройте параметры соревнования."
    )
    return 0


def parse_global_options(args: list[str]) -> tuple[str | None, str | None, list[str]]:
    env_file = None
    project_name = None
    remaining = list(args)
    seen: set[str] = set()

    while remaining:
        option = remaining[0]
        if option.startswith("--env-file="):
            name, value = option.split("=", 1)
            if name in seen or not value:
                raise ValueError("параметр --env-file указан неверно")
            env_file = value
            seen.add(name)
            remaining.pop(0)
        elif option.startswith("--project-name="):
            name, value = option.split("=", 1)
            if name in seen or not value:
                raise ValueError("параметр --project-name указан неверно")
            project_name = value
            seen.add(name)
            remaining.pop(0)
        elif option in {"--env-file", "--project-name"}:
            if option in seen or len(remaining) < 2 or not remaining[1]:
                raise ValueError(f"после {option} укажите значение")
            value = remaining[1]
            if option == "--env-file":
                env_file = value
            else:
                project_name = value
            seen.add(option)
            del remaining[:2]
        else:
            break

    if project_name is not None and not re.fullmatch(
        r"[a-z0-9][a-z0-9_-]*", project_name
    ):
        raise ValueError(
            "имя Compose-проекта должно содержать строчные латинские буквы, цифры, '-' или '_'"
        )
    return env_file, project_name, remaining


def resolve_env_file(root: Path, env_file: str | Path | None = None) -> Path:
    path = Path(env_file) if env_file is not None else Path(".env")
    if not path.is_absolute():
        path = Path(root) / path
    path = path.absolute()
    if path.is_symlink():
        raise OSError("файл настроек не должен быть символической ссылкой")
    try:
        info = path.stat()
    except FileNotFoundError as exc:
        raise FileNotFoundError(
            f"не найден файл {path}; сначала выполните ./control init"
        ) from exc
    if not stat.S_ISREG(info.st_mode):
        raise OSError("файл настроек должен быть обычным файлом")
    if os.name == "posix" and info.st_mode & 0o077:
        raise PermissionError(
            "файл настроек должен быть доступен только владельцу (например, chmod 600)"
        )
    return path


def compose_base(
    root: Path = ROOT,
    env_file: str | Path | None = None,
    project_name: str | None = None,
    profiles: tuple[str, ...] = (),
) -> list[str]:
    root = Path(root).resolve()
    command = [
        "docker",
        "compose",
        "--project-directory",
        str(root),
        "--file",
        str(root / "compose.yaml"),
        "--env-file",
        str(resolve_env_file(root, env_file)),
    ]
    if project_name is not None:
        if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", project_name):
            raise ValueError("недопустимое имя Compose-проекта")
        command.extend(("--project-name", project_name))
    for profile in profiles:
        command.extend(("--profile", profile))
    return command


def _compose_environment() -> dict[str, str]:
    """Disable ambient profile activation; command-specific flags select profiles."""
    environment = dict(os.environ)
    environment["COMPOSE_PROFILES"] = ""
    return environment


def run_compose(
    arguments: list[str],
    root: Path = ROOT,
    env_file: str | Path | None = None,
    project_name: str | None = None,
    profiles: tuple[str, ...] = (),
) -> int:
    root = Path(root).resolve()
    if not (root / "compose.yaml").is_file():
        print(f"Не найден файл Compose: {root / 'compose.yaml'}", file=sys.stderr)
        return 2
    try:
        command = [*compose_base(root, env_file, project_name, profiles), *arguments]
        return subprocess.call(command, cwd=root, env=_compose_environment())
    except (OSError, ValueError) as exc:
        print(f"Не удалось запустить Docker Compose: {exc}", file=sys.stderr)
        return 2


def _compose_config(
    root: Path,
    env_file: str | Path | None,
    project_name: str | None,
    profiles: tuple[str, ...] = (),
) -> dict:
    command = [
        *compose_base(root, env_file, project_name, profiles),
        "config",
        "--format",
        "json",
    ]
    result = subprocess.run(
        command,
        cwd=Path(root).resolve(),
        env=_compose_environment(),
        capture_output=True,
        text=True,
        check=False,
        timeout=30,
    )
    if result.returncode != 0:
        raise ValueError("Docker Compose не смог прочитать конфигурацию")
    config = json.loads(result.stdout)
    if not isinstance(config, dict):
        raise TypeError("неверный формат конфигурации Compose")
    return config


def all_services(
    action: str,
    root: Path = ROOT,
    env_file: str | Path | None = None,
    project_name: str | None = None,
    extra: list[str] | None = None,
) -> int:
    tail = list(extra or [])
    if action == "up":
        if tail:
            print("Команда up не принимает дополнительные параметры.", file=sys.stderr)
            return 2
        services = [
            "farm-db",
            "farm",
            "farm-front",
            "packmate-db",
            "packmate",
            "neo-redis",
            "neo",
            "neo-worker",
        ]
        return run_compose(
            ["up", "-d", "--build", "--wait", *services],
            root,
            env_file,
            project_name,
            ("worker",),
        )
    if action == "down":
        if tail:
            print(
                "Команда down не принимает дополнительные параметры.", file=sys.stderr
            )
            return 2
        return run_compose(
            ["down"], root, env_file, project_name, ("worker", "submission")
        )
    if action == "restart":
        if tail:
            print(
                "Команда restart не принимает дополнительные параметры.",
                file=sys.stderr,
            )
            return 2
        return run_compose(["restart"], root, env_file, project_name, ("worker",))
    if action == "logs":
        return run_compose(
            ["logs", "--tail", "100", "--follow", *tail],
            root,
            env_file,
            project_name,
            ("worker", "submission"),
        )
    if action == "status":
        if tail:
            print(
                "Команда status не принимает дополнительные параметры.", file=sys.stderr
            )
            return 2
        return run_compose(
            ["ps", "--all"], root, env_file, project_name, ("worker", "submission")
        )
    print(f"Неизвестная команда жизненного цикла: {action}", file=sys.stderr)
    return 2


def component_action(
    component: str,
    action: str,
    root: Path = ROOT,
    env_file: str | Path | None = None,
    project_name: str | None = None,
    extra: list[str] | None = None,
) -> int:
    canonical = {"pack": "packmate", "packmate": "packmate"}.get(component, component)
    if canonical not in COMPONENT_SERVICES:
        print(f"Неизвестный компонент: {component}", file=sys.stderr)
        return 2
    services = COMPONENT_SERVICES[canonical]
    tail = list(extra or [])

    if action == "up":
        if tail:
            print("Команда up не принимает дополнительные параметры.", file=sys.stderr)
            return 2
        profiles = ("worker",) if canonical == "neo" else ()
        return run_compose(
            ["up", "-d", "--build", "--wait", *services],
            root,
            env_file,
            project_name,
            profiles,
        )
    if action == "down":
        if tail:
            print(
                "Команда down не принимает дополнительные параметры.", file=sys.stderr
            )
            return 2
        profiles = ("worker",) if canonical == "neo" else ()
        stop_services = list(services)
        if canonical == "farm":
            profiles = ("submission",)
            stop_services = ["farm-submit", "farm-front", "farm", "farm-db"]
        elif canonical == "neo":
            stop_services = ["neo-worker", "neo", "neo-redis"]
        elif canonical == "packmate":
            stop_services = ["packmate", "packmate-db"]
        return run_compose(
            ["stop", *stop_services], root, env_file, project_name, profiles
        )
    if action == "restart":
        if tail:
            print(
                "Команда restart не принимает дополнительные параметры.",
                file=sys.stderr,
            )
            return 2
        profiles = ("worker",) if canonical == "neo" else ()
        restart_services = list(services)
        if canonical == "neo":
            restart_services.remove("neo-worker")
            restart_services.insert(0, "neo-worker")
        return run_compose(
            ["restart", *restart_services], root, env_file, project_name, profiles
        )
    if action == "logs":
        profiles = ("worker",) if canonical == "neo" else ()
        log_services = list(services)
        if canonical == "farm":
            profiles = ("submission",)
            log_services.append("farm-submit")
        return run_compose(
            ["logs", "--tail", "100", "--follow", *tail, *log_services],
            root,
            env_file,
            project_name,
            profiles,
        )
    if action == "status":
        if tail:
            print(
                "Команда status не принимает дополнительные параметры.", file=sys.stderr
            )
            return 2
        profiles = ("worker",) if canonical == "neo" else ()
        status_services = list(services)
        if canonical == "farm":
            profiles = ("submission",)
            status_services.append("farm-submit")
        return run_compose(
            ["ps", "--all", *status_services], root, env_file, project_name, profiles
        )
    if canonical == "farm" and action == "submit-up":
        if tail:
            print(
                "Команда submit-up не принимает дополнительные параметры.",
                file=sys.stderr,
            )
            return 2
        return run_compose(
            ["up", "-d", "--build", "--wait", "farm-submit"],
            root,
            env_file,
            project_name,
            ("submission",),
        )
    if canonical == "farm" and action == "submit-down":
        if tail:
            print(
                "Команда submit-down не принимает дополнительные параметры.",
                file=sys.stderr,
            )
            return 2
        return run_compose(
            ["stop", "farm-submit"], root, env_file, project_name, ("submission",)
        )

    print(f"Неизвестная команда для {canonical}: {action}", file=sys.stderr)
    return 2


def _local_path(value: str) -> Path:
    candidate = Path(value)
    if not candidate.is_absolute():
        candidate = Path.cwd() / candidate
    candidate = Path(os.path.abspath(candidate))
    info = candidate.lstat()
    if not stat.S_ISREG(info.st_mode):
        raise ValueError(
            "точка входа должна быть обычным файлом, а не ссылкой или каталогом"
        )
    resolved = candidate.resolve(strict=True)
    if resolved != candidate:
        raise ValueError(
            "путь к точке входа не должен проходить через символическую ссылку"
        )
    return candidate


def _safe_jobs_dir(root: Path) -> Path:
    local = Path(root) / "local"
    jobs = local / "jobs"
    for path in (local, jobs):
        if path.is_symlink():
            raise ValueError("каталог заданий не должен быть символической ссылкой")
    if not local.is_dir() or not jobs.is_dir():
        raise ValueError("сначала выполните ./control init")
    return jobs


def _copy_regular_file(
    source: Path,
    destination: Path,
    entrypoint: bool = False,
    counter: dict | None = None,
) -> None:
    info = source.lstat()
    if not stat.S_ISREG(info.st_mode):
        raise ValueError(f"обнаружен не обычный файл: {source.name}")
    if counter is not None:
        counter["entries"] += 1
        counter["bytes"] += info.st_size
        if counter["entries"] > MAX_STAGE_ENTRIES:
            raise ValueError("каталог превышает предел 10000 файлов")
        if counter["bytes"] > MAX_STAGE_BYTES:
            raise ValueError("данные превышают предел 512 MiB")
    elif info.st_size > MAX_STAGE_BYTES:
        raise ValueError("файл превышает предел 512 MiB")
    shutil.copyfile(source, destination, follow_symlinks=False)
    mode = 0o555 if entrypoint or info.st_mode & 0o111 else 0o444
    os.chmod(destination, mode)


def _copy_directory(
    source: Path,
    destination: Path,
    entrypoint_name: str,
    counter: dict,
    excluded_path: Path | None = None,
) -> None:
    destination.mkdir(mode=0o755)
    os.chmod(destination, 0o755)
    with os.scandir(source) as entries:
        for item in sorted(entries, key=lambda entry: entry.name):
            source_item = source / item.name
            target_item = destination / item.name
            if excluded_path is not None and source_item == excluded_path:
                continue
            info = item.stat(follow_symlinks=False)
            counter["entries"] += 1
            if counter["entries"] > MAX_STAGE_ENTRIES:
                raise ValueError("каталог превышает предел 10000 файлов")
            if stat.S_ISLNK(info.st_mode):
                raise ValueError(f"символическая ссылка запрещена: {item.name}")
            if stat.S_ISDIR(info.st_mode):
                _copy_directory(
                    source_item, target_item, entrypoint_name, counter, excluded_path
                )
            elif stat.S_ISREG(info.st_mode):
                # Число элементов учитывается на уровне каталога.
                counter["entries"] -= 1
                _copy_regular_file(
                    source_item, target_item, item.name == entrypoint_name, counter
                )
            else:
                raise ValueError(f"специальный файл запрещен: {item.name}")


@contextmanager
def staged_entrypoint(
    raw_path: str,
    root: Path = ROOT,
    include_parent: bool = False,
) -> Iterator[tuple[Path, str]]:
    source = _local_path(raw_path)
    jobs = _safe_jobs_dir(Path(root).resolve())
    stage = Path(tempfile.mkdtemp(prefix="neo-", dir=jobs))
    os.chmod(stage, 0o755)
    try:
        if include_parent:
            stage.rmdir()
            _copy_directory(
                source.parent, stage, source.name, {"entries": 0, "bytes": 0}, stage
            )
        else:
            _copy_regular_file(source, stage / source.name, entrypoint=True)
        yield stage, source.name
    finally:
        shutil.rmtree(stage)


def _parse_duration(value: str) -> str:
    if not re.fullmatch(r"[1-9][0-9]*(?:ns|us|µs|ms|s|m|h)", value):
        raise ValueError("длительность укажите числом с единицей, например 30s")
    return value


def _parse_add_args(args: list[str]) -> tuple[str, list[str], bool]:
    options: list[str] = []
    positional: list[str] = []
    include_parent = False
    index = 0
    value_options = {"--id", "--interval", "--timeout"}
    flag_options = {"--dir", "--disabled", "--endless"}
    while index < len(args):
        arg = args[index]
        if arg in flag_options:
            if arg == "--dir":
                include_parent = True
            options.append(arg)
            index += 1
            continue
        if arg in value_options:
            if index + 1 >= len(args):
                raise ValueError(f"после {arg} укажите значение")
            value = args[index + 1]
            if not value or value.startswith("-"):
                raise ValueError(f"после {arg} укажите значение")
            if arg in {"--interval", "--timeout"}:
                value = _parse_duration(value)
            options.extend((arg, value))
            index += 2
            continue
        if arg.startswith("--id="):
            value = arg.partition("=")[2]
            if not value:
                raise ValueError("после --id укажите значение")
            options.extend(("--id", value))
            index += 1
            continue
        if arg.startswith(("--interval=", "--timeout=")):
            name, value = arg.split("=", 1)
            options.extend((name, _parse_duration(value)))
            index += 1
            continue
        if arg.startswith("-"):
            raise ValueError(f"неизвестный параметр add: {arg}")
        positional.append(arg)
        index += 1
    if len(positional) != 1:
        raise ValueError(
            "использование: ./control neo add FILE [--id NAME] [--dir] [--disabled] [--interval 30s] [--timeout 30s] [--endless]"
        )
    return positional[0], options, include_parent


def _parse_test_args(args: list[str]) -> tuple[str, str, str, bool]:
    positional: list[str] = []
    timeout = "30s"
    include_parent = False
    index = 0
    while index < len(args):
        arg = args[index]
        if arg == "--dir":
            include_parent = True
            index += 1
        elif arg == "--timeout":
            if index + 1 >= len(args):
                raise ValueError("после --timeout укажите длительность")
            timeout = _parse_duration(args[index + 1])
            index += 2
        elif arg.startswith("--timeout="):
            timeout = _parse_duration(arg.split("=", 1)[1])
            index += 1
        elif arg.startswith("-"):
            raise ValueError(f"неизвестный параметр test: {arg}")
        else:
            positional.append(arg)
            index += 1
    if len(positional) != 2:
        raise ValueError(
            "использование: ./control neo test FILE HOST [--timeout 30s] [--dir]"
        )
    return positional[0], positional[1], timeout, include_parent


def _neo_key(config: dict) -> str:
    try:
        value = config["services"]["neo"]["environment"]["NEO_GRPC_AUTH_KEY"]
    except (KeyError, TypeError) as exc:
        raise ValueError("в конфигурации Neo отсутствует ключ администратора") from exc
    if not isinstance(value, str) or len(value) < 32 or not value.isascii():
        raise ValueError("некорректный NEO_KEY")
    return value


def _run_neo_container(
    command: list[str],
    root: Path,
    env_file: str | Path | None,
    project_name: str | None,
    *,
    admin_key: str | None = None,
    stage: Path | None = None,
    test_mode: bool = False,
) -> int:
    if not (Path(root) / "compose.yaml").is_file():
        print(f"Не найден файл Compose: {Path(root) / 'compose.yaml'}", file=sys.stderr)
        return 2
    try:
        base = compose_base(root, env_file, project_name, ("worker",))
        args = [*base, "run", "--rm", "--no-deps", "-T"]
        if admin_key is not None:
            args.extend(("-e", "NEO_GRPC_AUTH_KEY"))
        if test_mode:
            args.extend(("-e", "NEO_GRPC_AUTH_KEY=", "-e", "NEO_HOST="))
        if stage is not None:
            target = "/input" if test_mode else "/jobs"
            args.extend(("-v", f"{stage}:{target}:ro"))
        args.extend(("neo-worker", "/usr/local/bin/neo", *command))
        environment = None
        if admin_key is not None:
            environment = dict(os.environ)
            environment["COMPOSE_PROFILES"] = ""
            environment["NEO_GRPC_AUTH_KEY"] = admin_key
        else:
            environment = _compose_environment()
        return subprocess.call(args, cwd=Path(root).resolve(), env=environment)
    except (OSError, ValueError) as exc:
        print(f"Не удалось запустить команду Neo: {exc}", file=sys.stderr)
        return 2


def neo_test(
    args: list[str],
    root: Path = ROOT,
    env_file: str | Path | None = None,
    project_name: str | None = None,
) -> int:
    try:
        raw_path, host, timeout, include_parent = _parse_test_args(args)
        if any(ord(char) < 0x20 for char in host) or not host:
            raise ValueError(
                "HOST должен быть непустым адресом без управляющих символов"
            )
        config = _compose_config(root, env_file, project_name, ("worker",))
        flag_format = config["services"]["farm"]["environment"].get(
            "FARM_FLAG_FORMAT", ""
        )
        if not isinstance(flag_format, str):
            raise TypeError("некорректный FARM_FLAG_FORMAT")
        flag_format = flag_format.strip() or DEFAULT_FLAG_FORMAT
        with staged_entrypoint(raw_path, root, include_parent) as (stage, name):
            command = [
                "test",
                f"/input/{name}",
                host,
                "--timeout",
                timeout,
                "--flag-regexp",
                flag_format,
            ]
            if include_parent:
                command.append("--dir")
            return _run_neo_container(
                command,
                root,
                env_file,
                project_name,
                stage=stage,
                test_mode=True,
            )
    except (
        FileNotFoundError,
        OSError,
        ValueError,
        KeyError,
        TypeError,
        subprocess.SubprocessError,
    ) as exc:
        print(f"Не удалось подготовить тест Neo: {exc}", file=sys.stderr)
        return 2


def neo_add(
    args: list[str],
    root: Path = ROOT,
    env_file: str | Path | None = None,
    project_name: str | None = None,
) -> int:
    try:
        raw_path, options, include_parent = _parse_add_args(args)
        config = _compose_config(root, env_file, project_name, ("worker",))
        key = _neo_key(config)
        with staged_entrypoint(raw_path, root, include_parent) as (stage, name):
            command = [
                "--config",
                "/etc/neo/client_config.yml",
                "add",
                "--yes",
                *options,
                f"/jobs/{name}",
            ]
            return _run_neo_container(
                command,
                root,
                env_file,
                project_name,
                admin_key=key,
                stage=stage,
            )
    except (
        FileNotFoundError,
        OSError,
        ValueError,
        KeyError,
        TypeError,
        subprocess.SubprocessError,
    ) as exc:
        print(f"Не удалось подготовить добавление Neo: {exc}", file=sys.stderr)
        return 2


def neo_admin(
    args: list[str],
    root: Path = ROOT,
    env_file: str | Path | None = None,
    project_name: str | None = None,
) -> int:
    if not args or args[0] in {"-h", "--help", "help"}:
        print(neo_usage())
        return 0
    command, *tail = args
    if command == "test" and tail in ([], ["-h"], ["--help"]):
        print(
            "Использование: ./control neo test FILE HOST [--timeout 30s] [--dir]. Серверы и ключ администратора не используются."
        )
        return 0
    if command == "add" and tail in ([], ["-h"], ["--help"]):
        print(
            "Использование: ./control neo add FILE [--id NAME] [--dir] [--disabled] [--interval 30s] [--timeout 30s] [--endless]"
        )
        return 0
    if command == "run":
        print(
            "Команда run запрещена через административный интерфейс Neo.",
            file=sys.stderr,
        )
        return 2
    if command == "test":
        return neo_test(tail, root, env_file, project_name)
    if command == "add":
        return neo_add(tail, root, env_file, project_name)
    if command not in ADMIN_COMMANDS:
        print(neo_usage(), file=sys.stderr)
        return 2

    try:
        config = _compose_config(root, env_file, project_name, ("worker",))
        key = _neo_key(config)
        command_args = ["--config", "/etc/neo/client_config.yml", command, *tail]
        return _run_neo_container(
            command_args,
            root,
            env_file,
            project_name,
            admin_key=key,
        )
    except (
        OSError,
        ValueError,
        KeyError,
        TypeError,
        subprocess.SubprocessError,
    ) as exc:
        print(
            f"Не удалось выполнить команду Neo. Проверьте .env и Compose: {exc}",
            file=sys.stderr,
        )
        return 2


def run_scan(command: str, args: list[str], root: Path = ROOT) -> int:
    script = (
        Path(root).resolve()
        / "scripts"
        / {
            "scan": "defense_scan.py",
            "scan-external": "external_scan.py",
        }.get(command, "")
    )
    if command not in {"scan", "scan-external"}:
        print(f"Неизвестная команда сканера: {command}", file=sys.stderr)
        return 2
    if not script.is_file():
        print(
            f"Не найден скрипт: {script.relative_to(Path(root).resolve())}",
            file=sys.stderr,
        )
        return 2
    try:
        return subprocess.call([sys.executable, "-I", "-S", str(script), *args])
    except OSError as exc:
        print(f"Не удалось запустить {command}: {exc}", file=sys.stderr)
        return 2


def farm_flag(
    args: list[str],
    root: Path = ROOT,
    env_file: str | Path | None = None,
    project_name: str | None = None,
) -> int:
    """Передать готовый флаг в локальную очередь Farm."""
    if args in ([], ["-h"], ["--help"]):
        print("Использование: ./control farm flag FLAG [--team NAME] [--script NAME]")
        return 0

    flag = args[0]
    if flag.startswith("-"):
        print("Сначала укажите флаг.", file=sys.stderr)
        return 2
    team, script = "*", "Manual"
    options = {"--team", "--script"}
    tail = args[1:]
    if len(tail) % 2 or any(
        tail[index] not in options or not tail[index + 1]
        for index in range(0, len(tail), 2)
    ):
        print(
            "Использование: ./control farm flag FLAG [--team NAME] [--script NAME]",
            file=sys.stderr,
        )
        return 2
    for option, value in zip(tail[::2], tail[1::2]):
        if option == "--team":
            team = value
        else:
            script = value
    if team == "*" and script != "Manual":
        print("Для имени скрипта укажите --team из FARM_TEAMS.", file=sys.stderr)
        return 2

    try:
        config = _compose_config(root, env_file, project_name)
        password = config["services"]["farm"]["environment"]["FARM_PASSWORD"]
        port = config["services"]["farm-front"]["ports"][0]
        if (
            not isinstance(password, str)
            or len(password) < 32
            or not password.isascii()
        ):
            raise ValueError("некорректный FARM_PASSWORD")
        host = port.get("host_ip") or "127.0.0.1"
        published = int(port["published"])
        if host in {"0.0.0.0", "::"}:
            host = "127.0.0.1"
        if ":" in host:
            host = f"[{host}]"
        if not 1 <= published <= 65535:
            raise ValueError("некорректный FARM_PORT")
        payload = json.dumps([{"flag": flag, "sploit": script, "team": team}]).encode(
            "utf-8"
        )
        request = Request(
            f"http://{host}:{published}/api/post_flags",
            data=payload,
            headers={
                "Authorization": password,
                "X-Token": password,
                "Content-Type": "application/json",
            },
            method="POST",
        )
        with build_opener(ProxyHandler({})).open(request, timeout=10) as response:
            if response.status != 200:
                raise ValueError(f"Farm вернул HTTP {response.status}")
    except HTTPError as exc:
        exc.close()
        print(f"Farm отклонил флаг: HTTP {exc.code}", file=sys.stderr)
        return 2
    except (
        OSError,
        URLError,
        ValueError,
        KeyError,
        TypeError,
        IndexError,
        subprocess.SubprocessError,
    ) as exc:
        print(f"Не удалось передать флаг в Farm: {exc}", file=sys.stderr)
        return 2

    print("Флаг поставлен в очередь Farm.")
    return 0


def main(argv: list[str] | None = None, root: Path = ROOT) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    root = Path(root).resolve()
    try:
        env_file, project_name, args = parse_global_options(args)
    except ValueError as exc:
        print(f"Ошибка параметров: {exc}\n\n{usage()}", file=sys.stderr)
        return 2

    if not args or args[0] in {"-h", "--help"}:
        print(usage())
        return 0

    command, *tail = args
    if command == "init":
        if tail:
            if tail in (["--help"], ["-h"]):
                print(init_usage())
                return 0
            print("Использование: ./control init", file=sys.stderr)
            return 2
        if env_file is not None:
            print(
                "Команда init создает только корневой .env; параметр --env-file здесь не применяется.",
                file=sys.stderr,
            )
            return 2
        try:
            return init(root)
        except OSError as exc:
            print(f"Не удалось создать .env: {exc}", file=sys.stderr)
            return 2

    if command in {"scan", "scan-external"}:
        return run_scan(command, tail, root)

    if command in {"all-up", "all-down"}:
        action = {"all-up": "up", "all-down": "down"}[command]
        return all_services(action, root, env_file, project_name, tail)

    if command in {"up", "down", "restart", "logs", "status"}:
        return all_services(command, root, env_file, project_name, tail)

    if command in {"farm", "pack", "packmate", "neo"}:
        if tail and tail[0] in {"-h", "--help", "help"}:
            print(component_usage(command))
            return 0
        if not tail:
            action, action_args = "up", []
        else:
            action, *action_args = tail
            if command == "neo" and action not in {
                "up",
                "down",
                "restart",
                "logs",
                "status",
                "test",
                "add",
                *ADMIN_COMMANDS,
                "-h",
                "--help",
                "help",
            }:
                if action == "run":
                    print(
                        "Команда run запрещена через административный интерфейс Neo.",
                        file=sys.stderr,
                    )
                else:
                    print(neo_usage(), file=sys.stderr)
                return 2
        if command == "neo" and action in {"-h", "--help", "help"}:
            print(neo_usage())
            return 0
        if command == "neo" and action in {"test", "add", *ADMIN_COMMANDS}:
            if action == "test" and action_args in ([], ["-h"], ["--help"]):
                print(
                    "Использование: ./control neo test FILE HOST [--timeout 30s] [--dir]. Серверы и ключ администратора не используются."
                )
                return 0
            if action == "add" and action_args in ([], ["-h"], ["--help"]):
                print(
                    "Использование: ./control neo add FILE [--id NAME] [--dir] [--disabled] [--interval 30s] [--timeout 30s] [--endless]"
                )
                return 0
            return neo_admin([action, *action_args], root, env_file, project_name)
        if command == "farm" and action == "flag":
            return farm_flag(action_args, root, env_file, project_name)
        return component_action(
            command, action, root, env_file, project_name, action_args
        )

    print(f"Неизвестная команда: {command}\n\n{usage()}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
