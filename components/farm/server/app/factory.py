import logging
import time

from flask import Flask, Response, g, request
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest

from api import api
from config import CONFIG

HTTP_REQUESTS = Counter(
    'http_requests_total',
    'Количество обработанных HTTP-запросов',
    ['method', 'endpoint', 'status'],
)
HTTP_REQUEST_DURATION = Histogram(
    'http_request_duration_seconds',
    'Время обработки HTTP-запросов в секундах',
    ['method', 'endpoint'],
)


def create_app():
    app = Flask('s4d_farm')
    app.config.update(CONFIG)
    app.config['MAX_CONTENT_LENGTH'] = 1024 * 1024
    app.logger.setLevel(logging.INFO)
    for handler in app.logger.handlers:
        handler.setLevel(logging.INFO)

    app.register_blueprint(api)


    @app.before_request
    def start_request_timer():
        g.request_started_at = time.perf_counter()

    @app.after_request
    def record_request(response):
        if request.endpoint != 'metrics':
            endpoint = request.endpoint or 'unknown'
            HTTP_REQUESTS.labels(request.method, endpoint, str(response.status_code)).inc()
            HTTP_REQUEST_DURATION.labels(request.method, endpoint).observe(
                time.perf_counter() - g.request_started_at
            )
        return response

    @app.route('/metrics')
    def metrics():
        return Response(generate_latest(), mimetype=CONTENT_TYPE_LATEST)

    @app.route('/healthz')
    def healthz():
        return {'status': 'ok'}

    return app
