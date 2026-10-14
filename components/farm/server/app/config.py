import json
import math
import os
import re
from urllib.parse import urlsplit


SUPPORTED_PROTOCOLS = frozenset({
    'ctfcup_tcp',
    'faust',
    'ructf_http',
    'ructf_tcp',
    'volgactf',
})


def load_config(environ=None):
    environ = os.environ if environ is None else environ
    password = environ.get('FARM_PASSWORD', '')
    if not password.isascii() or len(password) < 32:
        raise RuntimeError('FARM_PASSWORD должен содержать не менее 32 ASCII-символов')

    try:
        teams = json.loads(environ.get('FARM_TEAMS') or '{"Example":"192.0.2.1"}')
    except json.JSONDecodeError as exc:
        raise RuntimeError('FARM_TEAMS должен быть корректным JSON-объектом') from exc
    if not isinstance(teams, dict) or not 1 <= len(teams) <= 256:
        raise RuntimeError('FARM_TEAMS должен содержать от 1 до 256 команд')
    for name, address in teams.items():
        if (not isinstance(name, str) or not name.strip() or len(name) > 100
                or not isinstance(address, str) or not address.strip() or len(address) > 253
                or any(ord(char) < 32 or ord(char) == 127 for char in name + address)):
            raise RuntimeError('FARM_TEAMS содержит некорректное имя или адрес')

    flag_format = environ.get('FARM_FLAG_FORMAT') or r'[A-Z0-9]{31}='
    if len(flag_format) > 256:
        raise RuntimeError('FARM_FLAG_FORMAT слишком длинный')
    try:
        re.compile(flag_format)
    except re.error as exc:
        raise RuntimeError('FARM_FLAG_FORMAT содержит некорректное регулярное выражение') from exc

    protocol = environ.get('FARM_SYSTEM_PROTOCOL', '').strip() or None
    if protocol and protocol not in SUPPORTED_PROTOCOLS:
        raise RuntimeError('FARM_SYSTEM_PROTOCOL не поддерживается')

    try:
        port = int(environ.get('FARM_SYSTEM_PORT', '31337'))
        submit_limit = int(environ.get('FARM_SUBMIT_FLAG_LIMIT', '100'))
        submit_period = float(environ.get('FARM_SUBMIT_PERIOD', '2'))
        flag_lifetime = int(environ.get('FARM_FLAG_LIFETIME', '300'))
        info_limit = int(environ.get('FARM_INFO_FLAG_LIMIT', '10'))
    except ValueError as exc:
        raise RuntimeError('Параметры отправки флагов должны быть числами') from exc
    if not 1 <= port <= 65535 or not 1 <= submit_limit <= 10000:
        raise RuntimeError('Порт или лимит отправки флагов вне допустимого диапазона')
    if (not math.isfinite(submit_period) or not 0.1 <= submit_period <= 3600
            or not 1 <= flag_lifetime <= 86400 or not 1 <= info_limit <= 10000):
        raise RuntimeError('Параметр отправки флагов вне допустимого диапазона')

    validator = environ.get('FARM_SYSTEM_VALIDATOR', '').strip() or None
    if validator not in (None, 'volgactf'):
        raise RuntimeError('FARM_SYSTEM_VALIDATOR не поддерживается')
    server_key = environ.get('FARM_SYSTEM_SERVER_KEY', '').strip() or None
    if validator and not server_key:
        raise RuntimeError('Для FARM_SYSTEM_VALIDATOR=volgactf задайте FARM_SYSTEM_SERVER_KEY')

    return {
        'DEBUG': False,
        'TEAMS': teams,
        'FLAG_FORMAT': flag_format,
        'SYSTEM_PROTOCOL': protocol,
        'SYSTEM_URL': environ.get('FARM_SYSTEM_URL', '').strip() or None,
        'SYSTEM_TOKEN': environ.get('FARM_SYSTEM_TOKEN', '').strip() or None,
        'SYSTEM_HOST': environ.get('FARM_SYSTEM_HOST', '').strip() or None,
        'SYSTEM_PORT': port,
        'SYSTEM_VALIDATOR': validator,
        'SYSTEM_SERVER_KEY': server_key,
        'SUBMIT_FLAG_LIMIT': submit_limit,
        'SUBMIT_PERIOD': submit_period,
        'FLAG_LIFETIME': flag_lifetime,
        'INFO_FLAG_LIMIT': info_limit,
        'SERVER_PASSWORD': password,
        'TIMEZONE': environ.get('FARM_TIMEZONE', 'Europe/Moscow'),
    }


def validate_submission_config(config):
    protocol = config.get('SYSTEM_PROTOCOL')
    if not protocol:
        raise RuntimeError('Отправка отключена: задайте FARM_SYSTEM_PROTOCOL')
    if protocol == 'ructf_http':
        url = config.get('SYSTEM_URL')
        token = config.get('SYSTEM_TOKEN')
        parsed = urlsplit(url or '')
        try:
            parsed.port
        except ValueError:
            raise RuntimeError('Для ructf_http задайте корректный FARM_SYSTEM_URL')
        if (parsed.scheme not in {'http', 'https'} or not parsed.hostname
                or parsed.username is not None or parsed.password is not None or parsed.fragment):
            raise RuntimeError('Для ructf_http задайте корректный FARM_SYSTEM_URL')
        if not token:
            raise RuntimeError('Для ructf_http задайте FARM_SYSTEM_TOKEN')
        return
    if not config.get('SYSTEM_HOST'):
        raise RuntimeError(f'Для {protocol} задайте FARM_SYSTEM_HOST')


CONFIG = load_config()
