FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 TZ=Asia/Kolkata
WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app app
RUN useradd -m jarvis && mkdir -p /srv/data && chown -R jarvis /srv
USER jarvis
# ONE worker on purpose: the call scheduler lives in-process and must not be duplicated.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--proxy-headers", "--forwarded-allow-ips", "*"]
