FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

ENV DEBIAN_FRONTEND=noninteractive

ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV GRADLE_USER_HOME=/home/vscode/.gradle

ENV PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH

RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-21-jdk-headless \
        unzip \
        wget \
        curl \
        git \
        jq \
        ripgrep \
        tree \
        file \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

ARG CMDLINE_TOOLS_VERSION=14742923

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && wget -q \
        "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
        -O /tmp/cmdline-tools.zip \
    && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
    && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" \
        "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses > /dev/null 2>&1 || true \
    && sdkmanager \
        "platform-tools" \
        "platforms;android-36" \
        "build-tools;36.0.0" \
    > /dev/null

RUN chown -R vscode:vscode "${ANDROID_HOME}"

USER vscode

# Optional CLI tooling — intentionally NOT baked in: the installers are
# remote pipe-to-bash / unpinned npm packages, and a supply-chain change at
# image build time would affect every developer container. Install them
# manually when needed, e.g.:
#   curl -fsSL https://opencode.ai/install | bash
#   sudo npm install -g freebuff

WORKDIR /workspaces
