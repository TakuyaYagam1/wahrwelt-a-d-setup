#!/usr/bin/env python3
"""Заготовка задания Neo: запрос к сервису и передача найденных флагов в Farm."""

from __future__ import annotations

import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit, urlunsplit
from urllib.request import Request, urlopen

TARGET_SCHEME = "http"
SERVICE_PORT = 80
SERVICE_PATH = "/"
REQUEST_TIMEOUT = 5
FLAG_PATTERN = re.compile(r"[A-Z0-9]{31}=")


def build_url(target: str) -> str:
    """Собрать URL из адреса команды и настроек сервиса."""
    raw_target = target.strip()
    if not raw_target:
        raise ValueError("пустой адрес цели")

    if "://" in raw_target:
        raw_url = raw_target
    else:
        authority = raw_target
        if authority.count(":") > 1 and not authority.startswith("["):
            authority = f"[{authority}]"
        raw_url = f"{TARGET_SCHEME}://{authority}"
    parsed = urlsplit(raw_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("ожидается адрес HTTP-сервиса")
    if parsed.username or parsed.password or parsed.path not in {"", "/"}:
        raise ValueError("укажите только адрес цели без учетных данных и пути")
    if parsed.query or parsed.fragment:
        raise ValueError("адрес цели не должен содержать query или fragment")

    port = parsed.port
    if port is None:
        if "://" in raw_target:
            port = 443 if parsed.scheme == "https" else 80
        else:
            port = SERVICE_PORT
    if not 1 <= port <= 65535:
        raise ValueError("порт должен быть от 1 до 65535")

    hostname = parsed.hostname
    host = f"[{hostname}]" if ":" in hostname else hostname
    return urlunsplit((parsed.scheme, f"{host}:{port}", SERVICE_PATH, "", ""))


def run_exploit(target: str) -> list[str]:
    """Напишите здесь сплоит и верните список найденных флагов."""
    url = build_url(target)
    request = Request(url, headers={"User-Agent": "wahrwelt-example/1.0"})
    with urlopen(request, timeout=REQUEST_TIMEOUT) as response:
        body = response.read().decode("utf-8", errors="replace")

    # Замените этот запрос логикой своего задания и верните найденные флаги.
    # Измените FLAG_PATTERN, если формат отличается от FARM_FLAG_FORMAT в .env.
    return list(dict.fromkeys(FLAG_PATTERN.findall(body)))


def main() -> int:
    if len(sys.argv) != 2:
        print(f"Использование: {sys.argv[0]} HOST", file=sys.stderr)
        return 2

    try:
        flags = run_exploit(sys.argv[1])
    except (HTTPError, URLError, TimeoutError, ValueError, OSError) as exc:
        print(f"Ошибка запроса к цели: {exc}", file=sys.stderr)
        return 1

    # Neo читает stdout и передает совпавшие флаги в Farm.
    for flag in flags:
        print(flag, flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
