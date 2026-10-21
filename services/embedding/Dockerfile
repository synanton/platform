FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    EMBEDDING_PORT=8000

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py ./

RUN useradd --create-home --uid 10001 appuser
USER appuser

EXPOSE 8000

# The service is a placeholder until D2; /health is 503 by design here.
CMD ["sh", "-c", "exec uvicorn app:app --host 0.0.0.0 --port ${EMBEDDING_PORT}"]
