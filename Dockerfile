# ─────────────────────────────────────────────────────────────────────────────
# Dockerfile — NativeProxy CI build environment
#
# This image provides a reproducible Android SDK environment for GitLab CI.
# ─────────────────────────────────────────────────────────────────────────────

FROM eclipse-temurin:17

# Match `app/build.gradle` (compileSdk = 35, build-tools = 35.0.0).
ENV ANDROID_COMPILE_SDK "35"
ENV ANDROID_BUILD_TOOLS "35.0.0"

ENV ANDROID_HOME /android-sdk-linux
ENV PATH="${PATH}:/android-sdk-linux/platform-tools/:/android-sdk-linux/cmdline-tools/latest/bin/"

# ── OS packages ────────────────────────────────────────────────────────────
RUN apt-get --quiet update --yes && \
    apt-get --quiet install --yes \
        wget apt-utils tar unzip \
        lib32stdc++6 lib32z1 \
        build-essential \
        vim-common && \
    rm -rf /var/lib/apt/lists/*

# ── Android command-line tools ──────────────────────────────────────────────
RUN wget --quiet --output-document=android-sdk.zip \
        https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && \
    unzip -q android-sdk.zip -d android-sdk-linux/ && \
    mkdir -p android-sdk-linux/cmdline-tools/latest && \
    mv android-sdk-linux/cmdline-tools/* android-sdk-linux/cmdline-tools/latest/ 2>/dev/null || true && \
    rm android-sdk.zip

# Accept SDK licenses non-interactively.
RUN yes | sdkmanager --licenses > /dev/null

# Install the SDK platform, build-tools, and platform-tools.
RUN sdkmanager "platforms;android-${ANDROID_COMPILE_SDK}" && \
    sdkmanager "build-tools;${ANDROID_BUILD_TOOLS}" && \
    sdkmanager "platform-tools" && \
    sdkmanager "extras;android;m2repository" && \
    sdkmanager "extras;google;m2repository"

# Default working directory — GitLab CI mounts the repo here.
WORKDIR /builds
