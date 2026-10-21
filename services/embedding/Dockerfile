FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    EMBEDDING_PORT=8000 \
    MODEL_LOCAL_FILES_ONLY=true

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py config.py model.py ./

RUN useradd --create-home --uid 10001 appuser
USER appuser

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=3 \
    CMD sh -c "python -c \"import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:${EMBEDDING_PORT}/health').status == 200 else 1)\""

# The deployment mounts the model and sets EMBED_MODEL/EMBED_DEVICE.
CMD ["sh", "-c", "exec uvicorn app:app --host 0.0.0.0 --port ${EMBEDDING_PORT}"]
