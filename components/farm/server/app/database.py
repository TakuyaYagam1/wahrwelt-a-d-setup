"""Пул соединений PostgreSQL и блокировка отправителя."""

import logging
import threading
from contextlib import contextmanager

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from constants import SCHEMA_PATH, POSTGRES_DSN

logger = logging.getLogger(__name__)


class DBPool:
    _lock = threading.RLock()
    _value = None

    @staticmethod
    def create():
        p = ConnectionPool(
            conninfo=POSTGRES_DSN,
            min_size=1,
            max_size=10,
            open=False,
        )
        logger.info('Инициализация схемы базы данных')
        try:
            p.open(wait=True, timeout=10)
            with p.connection() as conn:
                with conn.cursor() as curs:
                    curs.execute('SELECT pg_advisory_xact_lock(1128774993, 1)')
                    for statement in SCHEMA_PATH.read_text().split(';'):
                        if statement.strip():
                            curs.execute(statement)
        except Exception:
            p.close()
            raise
        return p

    @classmethod
    def get(cls):
        with cls._lock:
            if cls._value is None:
                cls._value = cls.create()
        return cls._value


@contextmanager
def db_cursor(dict_cursor: bool = True):
    db_pool = DBPool.get()
    conn = db_pool.getconn(timeout=10)

    if dict_cursor:
        curs = conn.cursor(row_factory=dict_row)
    else:
        curs = conn.cursor()
    try:
        yield conn, curs
    finally:
        try:
            curs.close()
        finally:
            try:
                conn.rollback()
            finally:
                db_pool.putconn(conn)


@contextmanager
def advisory_lock(lock_id: int):
    db_pool = DBPool.get()
    conn = db_pool.getconn()
    curs = conn.cursor()
    acquired = False
    try:
        curs.execute('SELECT pg_try_advisory_lock(%s)', (lock_id,))
        acquired = bool(curs.fetchone()[0])
        yield acquired
    finally:
        try:
            if acquired:
                curs.execute('SELECT pg_advisory_unlock(%s)', (lock_id,))
        finally:
            try:
                curs.close()
            finally:
                try:
                    conn.rollback()
                finally:
                    db_pool.putconn(conn)
