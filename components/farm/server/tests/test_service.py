import os
import sys
import unittest
from pathlib import Path
from unittest import mock

APP_DIR = Path(__file__).resolve().parents[1] / 'app'
sys.path.insert(0, str(APP_DIR))

os.environ.setdefault('FARM_PASSWORD', 'f' * 48)
os.environ.setdefault('FARM_TEAMS', '{"Example":"192.0.2.1"}')
os.environ.setdefault('FARM_FLAG_FORMAT', r'[A-Z0-9]{31}=')
os.environ.setdefault('POSTGRES_DSN', 'postgresql://farm:test@127.0.0.1/farm')

import config
import database
import sender
from factory import create_app

PASSWORD = os.environ['FARM_PASSWORD']
FLAG = 'A' * 31 + '='


class FakeCursor:
    def __init__(self):
        self.closed = False
        self.executed = []

    def execute(self, *args):
        self.executed.append(args)

    def executemany(self, *args):
        self.executed.append(args)

    def fetchone(self):
        return (True,)

    def close(self):
        self.closed = True


class FakeConnection:
    def __init__(self):
        self.cursors = []
        self.rollbacks = 0

    def cursor(self, **_kwargs):
        cursor = FakeCursor()
        self.cursors.append(cursor)
        return cursor

    def rollback(self):
        self.rollbacks += 1


class FakePool:
    def __init__(self, connection):
        self.connection = connection
        self.returned = []

    def getconn(self, **_kwargs):
        return self.connection

    def putconn(self, connection):
        self.returned.append(connection)


class ConfigTests(unittest.TestCase):
    def test_config_requires_a_long_password_and_disables_debug(self):
        with self.assertRaisesRegex(RuntimeError, 'FARM_PASSWORD'):
            config.load_config({'FARM_PASSWORD': 'short'})

        loaded = config.load_config({'FARM_PASSWORD': 'x' * 48})
        self.assertFalse(loaded['DEBUG'])
        self.assertEqual(loaded['TEAMS'], {'Example': '192.0.2.1'})

    def test_submission_requires_an_explicit_protocol(self):
        loaded = config.load_config({'FARM_PASSWORD': 'x' * 48})
        with self.assertRaisesRegex(RuntimeError, 'FARM_SYSTEM_PROTOCOL'):
            config.validate_submission_config(loaded)

    def test_period_must_be_finite_and_bounded(self):
        for period in ('nan', 'inf', '3601'):
            with self.subTest(period=period):
                with self.assertRaisesRegex(RuntimeError, 'Параметр отправки'):
                    config.load_config({
                        'FARM_PASSWORD': 'x' * 48,
                        'FARM_SUBMIT_PERIOD': period,
                    })

    def test_sender_validates_before_running_and_respects_stop(self):
        loaded = config.load_config({'FARM_PASSWORD': 'x' * 48})
        loaded.update(SYSTEM_PROTOCOL='ructf_tcp', SYSTEM_HOST='example.invalid')
        stop_event = mock.Mock()
        stop_event.is_set.return_value = True
        with mock.patch.object(sender, 'CONFIG', loaded):
            sender.run(stop_event)
        stop_event.wait.assert_not_called()


class ApiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = create_app().test_client()

    def headers(self, name='Authorization'):
        return {name: PASSWORD}

    def test_public_config_is_compatible_and_contains_no_secrets(self):
        response = self.client.get('/api/get_config', headers=self.headers())
        self.assertEqual(response.status_code, 200)
        self.assertEqual(set(response.json), {'FLAG_FORMAT', 'TEAMS'})
        self.assertNotIn(PASSWORD, response.get_data(as_text=True))

    def test_each_neo_auth_header_is_accepted(self):
        for name in ('Authorization', 'X-Token'):
            with self.subTest(header=name):
                response = self.client.get('/api/get_config', headers=self.headers(name))
                self.assertEqual(response.status_code, 200)

    def test_debug_does_not_bypass_authentication(self):
        app = create_app()
        app.config['DEBUG'] = True
        response = app.test_client().get('/api/get_config')
        self.assertEqual(response.status_code, 403)

    def test_valid_synthetic_flag_is_queued(self):
        connection = mock.Mock()
        cursor = mock.Mock()
        cursor.rowcount = 1
        context = mock.MagicMock()
        context.__enter__.return_value = (connection, cursor)

        with mock.patch('api.db_cursor', return_value=context):
            response = self.client.post(
                '/api/post_flags',
                headers=self.headers('X-Token'),
                json=[{'flag': FLAG, 'sploit': 'synthetic-test', 'team': 'Example'}],
            )

        self.assertEqual(response.status_code, 200)
        cursor.executemany.assert_called_once()
        connection.commit.assert_called_once()

    def test_invalid_payloads_are_rejected_before_database_access(self):
        invalid_payloads = (
            None,
            [{'flag': FLAG, 'sploit': 'synthetic-test', 'team': 'Unknown'}],
            [{'flag': 'bad', 'sploit': 'synthetic-test', 'team': 'Example'}],
            [{'flag': FLAG, 'sploit': 'synthetic-test', 'team': 'Example', 'extra': 'x'}],
        )
        with mock.patch('api.db_cursor') as db_cursor:
            for payload in invalid_payloads:
                with self.subTest(payload=payload):
                    response = self.client.post(
                        '/api/post_flags', headers=self.headers(), json=payload
                    )
                    self.assertEqual(response.status_code, 400)
            db_cursor.assert_not_called()

    def test_bad_credentials_are_rejected(self):
        for password in ('wrong', 'é'):
            with self.subTest(password=password):
                response = self.client.get(
                    '/api/get_config', headers={'Authorization': password}
                )
                self.assertEqual(response.status_code, 403)


class DatabasePoolTests(unittest.TestCase):
    def test_cursor_is_rolled_back_and_returned_after_success(self):
        connection = FakeConnection()
        pool = FakePool(connection)

        with mock.patch.object(database.DBPool, 'get', return_value=pool):
            with database.db_cursor():
                pass

        self.assertEqual(connection.rollbacks, 1)
        self.assertTrue(connection.cursors[0].closed)
        self.assertEqual(pool.returned, [connection])

    def test_cursor_is_rolled_back_and_returned_after_exception(self):
        connection = FakeConnection()
        pool = FakePool(connection)

        with mock.patch.object(database.DBPool, 'get', return_value=pool):
            with self.assertRaisesRegex(ValueError, 'synthetic failure'):
                with database.db_cursor():
                    raise ValueError('synthetic failure')

        self.assertEqual(connection.rollbacks, 1)
        self.assertTrue(connection.cursors[0].closed)
        self.assertEqual(pool.returned, [connection])

    def test_advisory_lock_is_released_on_exit(self):
        connection = FakeConnection()
        pool = FakePool(connection)

        with mock.patch.object(database.DBPool, 'get', return_value=pool):
            with database.advisory_lock(42) as acquired:
                self.assertTrue(acquired)

        self.assertEqual(len(connection.cursors[0].executed), 2)
        self.assertEqual(connection.rollbacks, 1)
        self.assertEqual(pool.returned, [connection])


if __name__ == '__main__':
    unittest.main()
