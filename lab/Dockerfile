# syntax=docker/dockerfile:1
# The lab image: the soundcheck-lab CLI for one-off tasks (`make aws-lab`). Built from the
# repository root by `make images`, because the lab reads contracts/ranking/ next to lab/. The root
# .dockerignore lets in only what is copied below.
FROM ghcr.io/astral-sh/uv:0.7.8 AS uv

FROM python:3.13-slim
COPY --from=uv /uv /uvx /usr/local/bin/
# The demo seed shells out to ffmpeg.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --system --gid 10001 soundcheck \
    && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin soundcheck \
    && chown -R 10001:10001 /tmp
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    UV_NO_CACHE=1
# The code finds contracts/ranking/ three directories above its own source (schema.py), so the
# repository's layout is kept: /repo/lab and /repo/contracts/ranking.
WORKDIR /repo/lab
# Dependencies first: that layer changes only with uv.lock. --locked refuses a lock that has drifted.
COPY lab/pyproject.toml lab/uv.lock lab/README.md ./
RUN uv sync --locked --no-dev --no-install-project
COPY lab/src ./src
COPY contracts/ranking /repo/contracts/ranking
RUN uv sync --locked --no-dev
USER 10001
# Nothing is written outside /tmp at run time, so a read-only root filesystem works here too.
ENV PYTHONDONTWRITEBYTECODE=1 \
    UV_CACHE_DIR=/tmp/uv-cache \
    MPLCONFIGDIR=/tmp/matplotlib
VOLUME /tmp
ENTRYPOINT ["uv", "run", "--no-sync", "soundcheck-lab"]
