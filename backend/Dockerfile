# Production image for the Podium API.
#
# The dev server (manage.py runserver) is single-threaded, prints stack traces
# to clients and refuses to serve static files with DEBUG off, so production
# runs gunicorn. Migrations and collectstatic happen in entrypoint.sh, once per
# start, before the workers accept traffic.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

# No system packages on purpose: psycopg-binary ships libpq inside its wheel,
# and the healthcheck below uses the Python that is already here. That keeps the
# image small and removes an external apt mirror from the build path -- a
# mirror being unreachable should not stop a deploy.
WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# Uploads and recordings live on mounted volumes, never inside the image:
# rebuilding must not throw away a term's recordings. The paths match the
# defaults in docker-compose.prod.yml.
RUN mkdir -p /app/staticfiles /data/recordings /data/materials /data/avatars \
    && useradd --create-home --uid 10001 podium \
    && chown -R podium:podium /app/staticfiles /data

USER podium

EXPOSE 8000

# `sh` rather than a direct exec: it works regardless of the file's exec bit or
# line endings, both of which are easy to lose when the repo is cloned on
# Windows.
ENTRYPOINT ["sh", "./entrypoint.sh"]

CMD ["gunicorn", "config.wsgi:application", \
     "--bind", "0.0.0.0:8000", \
     "--workers", "3", \
     "--threads", "2", \
     "--timeout", "60", \
     "--graceful-timeout", "30", \
     "--access-logfile", "-", \
     "--error-logfile", "-"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD ["python", "-c", "import sys,urllib.request; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health/live', timeout=4).status == 200 else 1)"]
