FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        fontconfig \
        fonts-dejavu \
        git \
        gzip \
        libasound2t64 \
        libatk-bridge2.0-0 \
        libatk1.0-0 \
        libcairo2 \
        libcups2t64 \
        libdbus-1-3 \
        libdrm2 \
        libfontconfig1 \
        libfreetype6 \
        libgbm1 \
        libgl1 \
        libgtk-3-0 \
        libnss3 \
        libpango-1.0-0 \
        libx11-6 \
        libx11-xcb1 \
        libxaw7 \
        libxcomposite1 \
        libxcursor1 \
        libxdamage1 \
        libxext6 \
        libxfixes3 \
        libxi6 \
        libxinerama1 \
        libxmu6 \
        libxpm4 \
        libxrandr2 \
        libxrender1 \
        libxss1 \
        libxt6t64 \
        libxtst6 \
        procps \
        tar \
        twm \
        x11-apps \
        x11-utils \
    && rm -rf /var/lib/apt/lists/*

COPY run-intellij.sh /usr/local/bin/run-intellij
COPY run-x11-apps.sh /usr/local/bin/run-x11-apps

RUN chmod +x /usr/local/bin/run-intellij /usr/local/bin/run-x11-apps

CMD ["sleep", "infinity"]
