import requests
import logging

from models import FlagStatus, SubmitResult

logger = logging.getLogger(__name__)

RESPONSES = {
    FlagStatus.QUEUED: ['timeout', 'game not started', 'try again later', 'game over', 'is not up',
                        'no such flag'],
    FlagStatus.ACCEPTED: ['accepted', 'congrat'],
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


def submit_flags(flags, config):
    r = requests.put(config['SYSTEM_URL'],
                     headers={'X-Team-Token': config['SYSTEM_TOKEN']},
                     json=[item.flag for item in flags], timeout=TIMEOUT)

    unknown_responses = set()
    for item in r.json():
        response = item['msg'].strip()
        response = response.replace('[{}] '.format(item['flag']), '')

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
