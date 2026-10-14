import logging.config
from typing import Any


def get_logging_config(level: str = 'DEBUG') -> dict[str, Any]:
    return {
        'version': 1,

        'formatters': {
            'brief': {
                'format': '%(message)s',
            },
            'default': {
                'format': f'[%(asctime)s.%(msecs)03d] [%(levelname)s] [%(name)s] %(message)s',
                'datefmt': '%Y-%m-%d %H:%M:%S',
            },
        },

        'handlers': {
            'console': {
                'class': 'logging.StreamHandler',
                'level': level,
                'formatter': 'default',
                'stream': 'ext://sys.stdout',
            },
        },

        'root': {
            'handlers': ['console'],
            'level': level,
        },

        'disable_existing_loggers': False,

    }


def setup_logging(level: str = 'DEBUG') -> dict[str, Any]:
    cfg = get_logging_config(level=level)
    logging.config.dictConfig(cfg)
    return cfg
