import sys
import types
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

APP_DIR = Path(__file__).resolve().parents[1] / 'app'
sys.path.insert(0, str(APP_DIR))

requests_stub = types.ModuleType('requests')
requests_stub.put = mock.Mock()
sys.modules['requests'] = requests_stub

from models import FlagStatus
from protocols import ructf_http


class FakeResponse:
    def __init__(self, status_code, payload, headers=None, error=None):
        self.status_code = status_code
        self.payload = payload
        self.headers = headers or {}
        self.error = error

    def json(self):
        return self.payload

    def raise_for_status(self):
        if self.error:
            raise self.error


def flags(count):
    return [SimpleNamespace(flag=f'{chr(65 + index)}' + '0' * 30 + '=')
            for index in range(count)]


def result(flag, message):
    return {'flag': flag.flag, 'msg': f'[{flag.flag}] {message}'}


class RuctfHttpTests(unittest.TestCase):
    def setUp(self):
        ructf_http._retry_after_until = 0.0
        requests_stub.put.reset_mock()
        requests_stub.put.return_value = None
        requests_stub.put.side_effect = None
        self.config = {
            'SYSTEM_URL': 'http://skipper.test/api/v1/flags',
            'SYSTEM_TOKEN': 'token',
            'SUBMIT_PERIOD': 2,
        }

    def test_skipper_messages_have_terminal_or_retry_status(self):
        batch = flags(8)
        messages = (
            'Flag accepted! Earned 15.75 flag points!',
            'Flag already stolen',
            'Flag is your own',
            'Flag is too old',
            'Flag is invalid or too old.',
            'Cannot submit flags while service is down',
            'game is paused',
            'Game is temporarily unavailable.',
        )
        expected = (
            FlagStatus.ACCEPTED,
            FlagStatus.ACCEPTED,
            FlagStatus.REJECTED,
            FlagStatus.REJECTED,
            FlagStatus.REJECTED,
            FlagStatus.QUEUED,
            FlagStatus.QUEUED,
            FlagStatus.QUEUED,
        )
        requests_stub.put.return_value = FakeResponse(
            200,
            [result(flag, message) for flag, message in zip(batch, messages)],
        )

        actual = list(ructf_http.submit_flags(batch, self.config))

        self.assertEqual(tuple(item.status for item in actual), expected)

    def test_retry_after_stops_requests_until_deadline(self):
        batch = flags(1)
        rate_limited = FakeResponse(
            429,
            {'error': 'rate limited'},
            headers={'Retry-After': '5'},
        )
        accepted = FakeResponse(200, [result(batch[0], 'Flag accepted! Earned 1 flag points!')])
        requests_stub.put.side_effect = [rate_limited, accepted]

        with mock.patch.object(ructf_http.time, 'monotonic', side_effect=(100, 100, 103, 106)):
            first = list(ructf_http.submit_flags(batch, self.config))
            second = list(ructf_http.submit_flags(batch, self.config))
            third = list(ructf_http.submit_flags(batch, self.config))

        self.assertEqual(first[0].status, FlagStatus.QUEUED)
        self.assertEqual(second[0].status, FlagStatus.QUEUED)
        self.assertEqual(third[0].status, FlagStatus.ACCEPTED)
        self.assertEqual(requests_stub.put.call_count, 2)

    def test_invalid_success_payload_is_rejected(self):
        batch = flags(1)
        requests_stub.put.return_value = FakeResponse(200, {'error': 'unexpected'})

        with self.assertRaisesRegex(ValueError, 'неожиданном формате'):
            list(ructf_http.submit_flags(batch, self.config))

    def test_request_error_is_propagated(self):
        batch = flags(1)
        requests_stub.put.return_value = FakeResponse(
            503,
            {'error': 'maintenance'},
            error=RuntimeError('503 Service Unavailable'),
        )

        with self.assertRaisesRegex(RuntimeError, '503 Service Unavailable'):
            list(ructf_http.submit_flags(batch, self.config))


if __name__ == '__main__':
    unittest.main()
