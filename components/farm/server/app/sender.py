import logging
import signal
import time
from threading import Event

from config import CONFIG, validate_submission_config
from log import setup_logging
from tasks import submit_flags_task

logger = logging.getLogger(__name__)


def run(stop_event=None):
    validate_submission_config(CONFIG)
    stop_event = stop_event or Event()
    period = CONFIG['SUBMIT_PERIOD']

    logger.info('Запуск отправителя флагов с интервалом %.2f с', period)
    while not stop_event.is_set():
        started = time.monotonic()
        try:
            submit_flags_task()
        except Exception:
            logger.exception('Ошибка обработки очереди флагов')
        stop_event.wait(max(0, period - (time.monotonic() - started)))


def main():
    setup_logging('INFO')
    stop_event = Event()

    def request_stop(_signum, _frame):
        stop_event.set()

    signal.signal(signal.SIGTERM, request_stop)
    signal.signal(signal.SIGINT, request_stop)
    run(stop_event)


if __name__ == '__main__':
    main()
