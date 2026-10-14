import hmac
from functools import wraps

from flask import request, abort

from config import CONFIG


def auth_required(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        config = CONFIG
        auth = request.headers.get('Authorization') or request.headers.get('X-Token') or ''
        try:
            supplied = auth.encode('ascii')
        except UnicodeEncodeError:
            abort(403)
        expected = config['SERVER_PASSWORD'].encode('ascii')
        if not hmac.compare_digest(supplied, expected):
            abort(403)

        return f(*args, **kwargs)

    return decorated
