FROM python:alpine

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

COPY requirements.txt ./requirements.txt
RUN apk upgrade --no-cache \
    && python -m pip install --no-cache-dir -r requirements.txt \
    && python -m pip cache purge \
    && python -m pip uninstall -y setuptools pip

COPY app/ ./
RUN addgroup -S farm && adduser -S -D -H -G farm farm \
    && chown -R farm:farm /app

COPY --from=project /LICENSE /usr/share/licenses/wahrwelt/LICENSE

USER farm
EXPOSE 5000
CMD ["gunicorn", "--bind=0.0.0.0:5000", "--workers=2", "--worker-class=gthread", "--threads=4", "--timeout=30", "--access-logfile=-", "--error-logfile=-", "app_flask:app"]
