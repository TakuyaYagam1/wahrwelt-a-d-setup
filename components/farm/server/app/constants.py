import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
RESOURCES_DIR = BASE_DIR / 'resources'

SCHEMA_PATH = RESOURCES_DIR / 'schema.sql'

POSTGRES_DSN = os.getenv('POSTGRES_DSN', '').strip()
if not POSTGRES_DSN:
    raise RuntimeError('Не задан POSTGRES_DSN')
