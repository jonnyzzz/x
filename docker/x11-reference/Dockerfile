FROM jonnyzzz-x/x11-client:latest

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        xvfb \
    && rm -rf /var/lib/apt/lists/*
