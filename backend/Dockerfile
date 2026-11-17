FROM denoland/deno:bin-2.2.4 AS deno_bin

FROM python:3.13-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

# Install ffmpeg and curl for healthchecks; deno provides modern JS runtime for yt-dlp EJS
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=deno_bin /deno /usr/local/bin/deno

# Create non-root droplink user
RUN groupadd --gid 10001 droplink && \
    useradd --uid 10001 --gid 10001 --create-home --shell /usr/sbin/nologin droplink

WORKDIR /app

COPY requirements.txt /app/requirements.txt
COPY requirements.lock /app/requirements.lock
RUN pip install --no-cache-dir -r /app/requirements.lock

COPY app /app/app
COPY migrations /app/migrations
COPY pyproject.toml /app/pyproject.toml

RUN mkdir -p /app/data /app/storage /app/tmp /app/config && \
    chown -R droplink:droplink /app

USER droplink

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
