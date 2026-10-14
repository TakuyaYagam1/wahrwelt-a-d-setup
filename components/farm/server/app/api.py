import re
import time
from collections import defaultdict
from datetime import datetime

from flask import Blueprint, jsonify, request
from prometheus_client import Counter, Gauge

import auth
from config import CONFIG
from database import db_cursor
from models import FlagStatus
from validators.volgactf import validate_flags as validate_volgactf_flags

api = Blueprint('api', __name__, url_prefix='/api')

FLAGS_RECEIVED = Counter(
    'flags_received',
    'Число полученных флагов',
    ['sploit', 'team'],
)
TOTAL_TEAMS = Gauge('total_teams', 'Число команд')
TOTAL_TEAMS.set_function(lambda: len(CONFIG['TEAMS']))

PUBLIC_CONFIG_KEYS = (
    'FLAG_FORMAT',
    'TEAMS',
)
MAX_FLAGS_PER_REQUEST = 1000
MAX_FILTER_LENGTH = 256


def _bad_request(message):
    return jsonify({'error': message}), 400


def _query_conditions(filters):
    conditions = []
    for column in ('sploit', 'status', 'team'):
        value = filters.get(column, '').strip()
        if value:
            if len(value) > MAX_FILTER_LENGTH:
                raise ValueError('Значение фильтра слишком длинное')
            conditions.append((f'{column} = %s', value))

    for column in ('flag', 'checksystem_response'):
        value = filters.get(column, '').strip()
        if value:
            if len(value) > MAX_FILTER_LENGTH:
                raise ValueError('Значение фильтра слишком длинное')
            conditions.append((f'POSITION(%s in LOWER({column})) > 0', value.lower()))

    for column in ('since', 'until'):
        value = filters.get(column, '').strip()
        if value:
            try:
                timestamp = round(datetime.strptime(value, '%Y-%m-%d %H:%M').timestamp())
            except ValueError as exc:
                raise ValueError('Дата должна иметь формат ГГГГ-ММ-ДД ЧЧ:ММ') from exc
            sign = '>=' if column == 'since' else '<='
            conditions.append((f'time {sign} %s', timestamp))

    return conditions


def _parse_positive_int(value, name, default, maximum):
    try:
        result = int(value or default)
    except (TypeError, ValueError) as exc:
        raise ValueError(f'Параметр {name} должен быть целым числом') from exc
    if not 1 <= result <= maximum:
        raise ValueError(f'Параметр {name} вне допустимого диапазона')
    return result


def _validated_flags(payload):
    if not isinstance(payload, list) or len(payload) > MAX_FLAGS_PER_REQUEST:
        raise ValueError(f'Ожидается список не более чем из {MAX_FLAGS_PER_REQUEST} флагов')

    pattern = re.compile(CONFIG['FLAG_FORMAT'])
    validated = []
    team_names = set(CONFIG['TEAMS'])
    for item in payload:
        if not isinstance(item, dict) or set(item) != {'flag', 'sploit', 'team'}:
            raise ValueError('Каждый элемент должен содержать только flag, sploit и team')
        flag, sploit, team = item['flag'], item['sploit'], item['team']
        if not all(isinstance(value, str) for value in (flag, sploit, team)):
            raise ValueError('Поля flag, sploit и team должны быть строками')
        if (not flag or len(flag) > 4096 or not pattern.fullmatch(flag)
                or any(ord(char) < 32 for char in flag)):
            raise ValueError('Флаг не соответствует FARM_FLAG_FORMAT')
        if (not sploit.strip() or len(sploit) > 128
                or any(ord(char) < 32 for char in sploit)):
            raise ValueError('Некорректное имя sploit')
        if team == '*' and sploit == 'Manual':
            pass
        elif team not in team_names:
            raise ValueError('Команда отсутствует в FARM_TEAMS')
        validated.append({'flag': flag, 'sploit': sploit, 'team': team})
    return validated


@api.route('/get_config')
@auth.auth_required
def get_config():
    return jsonify({key: CONFIG[key] for key in PUBLIC_CONFIG_KEYS})


@api.route('/post_flags', methods=['POST'])
@auth.auth_required
def post_flags():
    payload = request.get_json(silent=True)
    if payload is None:
        return _bad_request('Ожидается JSON-массив флагов')
    try:
        flags = _validated_flags(payload)
    except ValueError as exc:
        return _bad_request(str(exc))

    if CONFIG['SYSTEM_VALIDATOR']:
        try:
            flags = list(validate_volgactf_flags(flags, CONFIG))
        except Exception:
            return jsonify({'error': 'Не удалось проверить полученные флаги'}), 400

    cur_time = round(time.time())
    rows = [
        {
            'flag': flag['flag'],
            'sploit': flag['sploit'],
            'team': flag['team'],
            'time': cur_time,
            'status': FlagStatus.QUEUED.name,
        }
        for flag in flags
    ]

    if rows:
        with db_cursor() as (conn, curs):
            curs.executemany(
                """
                INSERT INTO flags (flag, sploit, team, time, status)
                VALUES (%(flag)s, %(sploit)s, %(team)s, %(time)s, %(status)s)
                ON CONFLICT DO NOTHING
                """,
                rows,
            )
            conn.commit()

        for flag in flags:
            FLAGS_RECEIVED.labels(sploit=flag['sploit'], team=flag['team']).inc()

    return '', 200


@api.route('/filter_flags', methods=['GET'])
@auth.auth_required
def get_filtered_flags():
    try:
        conditions = _query_conditions(request.args)
        page = _parse_positive_int(request.args.get('page'), 'page', 1, 100000)
        page_size = _parse_positive_int(request.args.get('page_size'), 'page_size', 30, 100)
    except ValueError as exc:
        return _bad_request(str(exc))

    if conditions:
        chunks, values = list(zip(*conditions))
        conditions_sql = 'WHERE ' + ' AND '.join(chunks)
        conditions_args = list(values)
    else:
        conditions_sql = ''
        conditions_args = []

    sql = 'SELECT * FROM flags ' + conditions_sql + ' ORDER BY time DESC LIMIT %s OFFSET %s'
    args = conditions_args + [page_size, page_size * (page - 1)]
    count_sql = 'SELECT COUNT(*) as cnt FROM flags ' + conditions_sql

    with db_cursor() as (_, curs):
        curs.execute(sql, args)
        flags = curs.fetchall()
        curs.execute(count_sql, conditions_args)
        total_count = curs.fetchone()['cnt']

    return jsonify({
        'flags': list(map(dict, flags)),
        'page_size': page_size,
        'page': page,
        'total': total_count,
    })


@api.route('/filter_config', methods=['GET'])
@auth.auth_required
def get_filter_config():
    distinct_values = {}
    with db_cursor() as (_, curs):
        for column in ('sploit', 'status', 'team'):
            curs.execute(f'SELECT DISTINCT {column} FROM flags ORDER BY {column}')
            distinct_values[column] = [item[column] for item in curs.fetchall()]

    server_tz_name = time.strftime('%Z')
    if server_tz_name.startswith('+'):
        server_tz_name = 'UTC' + server_tz_name

    return jsonify({
        'filters': distinct_values,
        'flag_format': CONFIG['FLAG_FORMAT'],
        'server_tz': server_tz_name,
    })


@api.route('/summary', methods=['GET'])
@auth.auth_required
def summary():
    try:
        conditions = _query_conditions(request.args)
        last = request.args.get('last', '').strip()
        if last:
            seconds = _parse_positive_int(last, 'last', 0, 31_536_000)
            conditions.append(('time >= %s', round(time.time() - seconds)))
    except ValueError as exc:
        return _bad_request(str(exc))

    if conditions:
        chunks, values = list(zip(*conditions))
        conditions_sql = 'WHERE ' + ' AND '.join(chunks)
        conditions_args = list(values)
    else:
        conditions_sql = ''
        conditions_args = []

    with db_cursor() as (_, curs):
        curs.execute('SELECT * FROM flags ' + conditions_sql, conditions_args)
        flags = curs.fetchall()

    result = defaultdict(lambda: defaultdict(lambda: defaultdict(int)))
    include_response = request.args.get('response', '')
    for item in flags:
        response = item.get('checksystem_response') or ''
        key = response[:64] if include_response else item['status']
        result[item['sploit']][item['team']][key] += 1

    sploits = []
    for sploit, teams in result.items():
        rows = []
        for team in CONFIG['TEAMS']:
            if team not in teams:
                rows.append({'team': team, 'team_status': 'нет данных'})
        for team, statuses in teams.items():
            output = ''.join(f'{key}: {value}\n' for key, value in statuses.items())
            rows.append({'team': team, 'team_status': output})
        sploits.append({'sploit_name': sploit, 'teams': rows})

    return jsonify(sploits)


@api.route('/teams', methods=['GET'])
@auth.auth_required
def get_teams():
    return jsonify([
        {'name': name, 'address': address}
        for name, address in CONFIG['TEAMS'].items()
    ])
