FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    AUDIO_DIR=/data/audio

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py clinical_app.py mcp_server.py ./

RUN mkdir -p /data/audio

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD curl -fsS http://127.0.0.1:${PORT:-8080}/health || exit 1

CMD ["sh", "-c", "uvicorn clinical_app:app --host 0.0.0.0 --port ${PORT:-8080}"]
