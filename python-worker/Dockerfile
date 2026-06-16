FROM python:3.11-slim

WORKDIR /app

# Install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy source code
COPY main.py .

# Run the worker (unbuffered so Python print statements show up immediately in Docker logs)
ENV PYTHONUNBUFFERED=1
CMD ["python", "main.py"]