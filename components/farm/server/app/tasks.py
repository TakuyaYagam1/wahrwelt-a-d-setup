import logging
import time
from collections import defaultdict

from prometheus_client import Counter, Gauge

from config import CONFIG
from database import advisory_lock, db_cursor
from models import Flag, FlagStatus
from utils import get_fair_share, submit_flags

logger = logging.getLogger(__name__)

FLAGS_QUEUED = Gauge(
    'flags_queued',
    'Число флагов в очереди',
    ['sploit', 'team'],
)

FLAGS_SUBMITTED = Counter(
    'flags_submitted',
    'Число отправленных флагов',
    ['sploit', 'team', 'status'],
)

FLAGS_TIMED_OUT = Counter(
    'flags_timed_out',
    'Число флагов с истекшим сроком',
)


def submit_flags_task():
    with advisory_lock(1128774994) as acquired:
        if not acquired:
            logger.info('Пропуск отправки: предыдущая задача еще выполняется')
            return
        _submit_queued_flags()


def _submit_queued_flags():
    logger.info('Обработка очереди флагов')
    config = CONFIG
    now = time.time()
    skip_time = round(now - config['FLAG_LIFETIME'])

    with db_cursor(True) as (conn, curs):
        curs.execute(
            """
            UPDATE flags SET status = %s WHERE status = %s AND time < %s
            """,
            (FlagStatus.SKIPPED.name, FlagStatus.QUEUED.name, skip_time),
        )
        skipped_flags = curs.rowcount
        conn.commit()
        curs.execute(
            """
            SELECT * FROM flags WHERE status = %s
            """,
            (FlagStatus.QUEUED.name,),
        )
        queued_flags = [Flag(**item) for item in curs.fetchall()]

    logger.info('В очереди: %s, просрочено: %s', len(queued_flags), skipped_flags)
    FLAGS_TIMED_OUT.inc(skipped_flags)

    queued_by_labels: dict[(str, str), int] = defaultdict(int)
    for item in queued_flags:
        queued_by_labels[item.sploit, item.team] += 1
    for key, value in queued_by_labels.items():
        FLAGS_QUEUED.labels(sploit=key[0], team=key[1]).set(value)

    if queued_flags:
        grouped_flags: dict[(str, str), list[Flag]] = defaultdict(list)
        for item in queued_flags:
            grouped_flags[item.sploit, item.team].append(item)
        flags = get_fair_share(list(grouped_flags.values()), config['SUBMIT_FLAG_LIMIT'])

        flag_by_text = {item.flag: item for item in queued_flags}

        logger.info('Отправка флагов: %s из %s', len(flags), len(queued_flags))

        results = submit_flags(flags, config)

        rows = []
        for submit_result in results:
            rows.append((
                submit_result.status.name,
                submit_result.checksystem_response,
                submit_result.flag,
            ))

            flag = flag_by_text[submit_result.flag]
            FLAGS_SUBMITTED.labels(
                sploit=flag.sploit,
                team=flag.team,
                status=submit_result.status.name,
            ).inc()

        with db_cursor(True) as (conn, curs):
            curs.executemany(
                """
                UPDATE flags SET status = %s, checksystem_response = %s WHERE flag = %s
                """,
                rows,
            )
            conn.commit()
