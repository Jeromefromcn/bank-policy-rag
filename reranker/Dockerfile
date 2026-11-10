# Cross-encoder rerank sidecar (docs/specs/04-rerank-rewrite.md §3). CPU only; multi-arch base (arm64 and amd64).
FROM python:3.12-slim@sha256:5e2bdb1b0d542d5039cffb8181c65930093949194a65c365dfb1b0fe13a4fa5b
# Every package is pinned in requirements.txt and installed without dependency resolution; pip check proves the set
# is complete and consistent.
COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir --root-user-action=ignore --no-deps -r /app/requirements.txt \
 && pip install --no-cache-dir --root-user-action=ignore --no-deps --index-url https://download.pytorch.org/whl/cpu torch==2.9.1 \
 && pip check
COPY server.py /app/server.py
ENV HF_HOME=/models PYTHONUNBUFFERED=1
EXPOSE 8080
CMD ["python", "/app/server.py"]
