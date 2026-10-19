#!/usr/bin/env python3
"""Заготовка задания Neo: запрос к сервису и вывод ответа, флаги ищет сам Neo."""

from __future__ import annotations

import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit, urlunsplit
from urllib.request import Request, urlopen

TARGET_SCHEME = "http"

# Порт и путь нужно указать в зависимоти от сервиса, типо может быть сервис крутится на 8081 и тогда этот порт передаем
SERVICE_PORT = 80

# SERVICE_PATH это путь в URL, по которому скрипт делает запрос
# В build_url он подставляется в конец адреса:
# при SERVICE_PORT = 8080 и SERVICE_PATH = "/" скрипт ходит на http://10.183.101.2:8080/
SERVICE_PATH = "/"
REQUEST_TIMEOUT = 5


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


def run_exploit(target: str) -> str:
    """Напишите здесь сплоит и верните текст, в котором могут быть флаги."""
    url = build_url(target)
    request = Request(url, headers={"User-Agent": "wahrwelt-example/1.0"})
    with urlopen(request, timeout=REQUEST_TIMEOUT) as response:
        body = response.read().decode("utf-8", errors="replace")

    # Замените этот запрос логикой своего задания. Формат флага здесь не нужен:
    # Neo ищет FARM_FLAG_FORMAT в stdout сам.
    return body


def main() -> int:
    if len(sys.argv) != 2:
        print(f"Использование: {sys.argv[0]} HOST", file=sys.stderr)
        return 2

    try:
        output = run_exploit(sys.argv[1])
    except (HTTPError, URLError, TimeoutError, ValueError, OSError) as exc:
        print(f"Ошибка запроса к цели: {exc}", file=sys.stderr)
        return 1

    # Neo читает stdout и передает совпавшие флаги в Farm.
    print(output, flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
