import logging
import math
import time

import requests

from models import FlagStatus, SubmitResult

logger = logging.getLogger(__name__)

RESPONSES = {
    FlagStatus.QUEUED: ['timeout', 'game not started', 'game is paused', 'temporarily unavailable',
                        'cannot submit flags while service is down', 'try again later', 'game over',
                        'is not up', 'no such flag'],
    FlagStatus.ACCEPTED: ['accepted', 'congrat', 'already stolen'],
    FlagStatus.REJECTED: ['bad', 'wrong', 'expired', 'unknown', 'your own',
                          'too old', 'not in database', 'already submitted', 'invalid flag'],
}
# Чекер RuCTF добавляет подпись ко всем правильным флагам. Он возвращает
# "invalid flag", если подпись неверна, и "no such flag", если подпись верна,
# но флаг пока отсутствует в базе чекера.
#
# Так бывает, когда сервис публикует флаг раньше, чем чекер сохраняет его у себя.
# В этом случае флаг нужно отправить повторно позже.


TIMEOUT = 5
_retry_after_until = 0.0


def _response_error(response):
    try:
        payload = response.json()
    except ValueError:
        return ''
    if isinstance(payload, dict) and isinstance(payload.get('error'), str):
        return payload['error'].strip()
    return ''


def _retry_after(response, config):
    fallback = max(float(config.get('SUBMIT_PERIOD', 2)), 0.1)
    try:
        return max(float(response.headers.get('Retry-After', '')), 0.0)
    except (TypeError, ValueError):
        return fallback


def _queued(flags, message):
    for item in flags:
        yield SubmitResult(item.flag, FlagStatus.QUEUED, message)


def submit_flags(flags, config):
    global _retry_after_until

    now = time.monotonic()
    if now < _retry_after_until:
        remaining = math.ceil(_retry_after_until - now)
        yield from _queued(flags, f'Ограничение частоты, повтор через {remaining} с')
        return

    r = requests.put(config['SYSTEM_URL'],
                     headers={'X-Team-Token': config['SYSTEM_TOKEN']},
                     json=[item.flag for item in flags], timeout=TIMEOUT)

    if r.status_code == 429:
        delay = _retry_after(r, config)
        _retry_after_until = max(_retry_after_until, time.monotonic() + delay)
        message = _response_error(r) or 'Превышен лимит отправки флагов'
        logger.warning('%s; повтор не раньше чем через %.2f с', message, delay)
        yield from _queued(flags, message)
        return

    r.raise_for_status()
    payload = r.json()
    if not isinstance(payload, list):
        raise ValueError('Skipper вернул ответ в неожиданном формате')

    unknown_responses = set()
    for item in payload:
        if (not isinstance(item, dict) or not isinstance(item.get('flag'), str)
                or not isinstance(item.get('msg'), str)):
            raise ValueError('Skipper вернул некорректный результат сдачи флага')
        response = item['msg'].strip()
        response = response.removeprefix('[{}] '.format(item['flag']))

        response_lower = response.lower()
        for status, substrings in RESPONSES.items():
            if any(s in response_lower for s in substrings):
                found_status = status
                break
        else:
            found_status = FlagStatus.QUEUED
            if response not in unknown_responses:
                unknown_responses.add(response)
                logger.warning('Неизвестный ответ чекера (флаг будет отправлен повторно): %s', response)

        yield SubmitResult(item['flag'], found_status, response)
