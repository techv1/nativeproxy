
# ─────────────────────────────────────────────────────────────────────────────
# Dockerfile — NativeProxy CI build environment
#
# This image is built by the GitLab `environment` stage and cached in the
# project's container registry. Every subsequent build / test / promote job
# pulls this image, which gives us a reproducible Android SDK + Fastlane
# toolchain without re-installing the SDK on every pipeline run.
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
        ruby ruby-dev \
        vim-common && \
    rm -rf /var/lib/apt/lists/*

# vim-common provides `xxd` (hex -> binary) used by some Fastlane actions.

# ── Android command-line tools ──────────────────────────────────────────────
RUN wget --quiet --output-document=android-sdk.zip \
        https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && \
    unzip -q android-sdk.zip -d android-sdk-linux/ && \
    mkdir -p android-sdk-linux/cmdline-tools/latest && \
    mv android-sdk-linux/cmdline-tools/* android-sdk-linux/cmdline-tools/latest/ 2>/dev/null || true && \
    rm android-sdk.zip

# Accept SDK licenses non-interactively.
RUN yes | sdkmanager --licenses > /dev/null

# Install the SDK platform, build-tools, platform-tools, and Google m2
# repositories that the Fastlane supply action needs.
RUN sdkmanager "platforms;android-${ANDROID_COMPILE_SDK}" && \
    sdkmanager "build-tools;${ANDROID_BUILD_TOOLS}" && \
    sdkmanager "platform-tools" && \
    sdkmanager "extras;android;m2repository" && \
    sdkmanager "extras;google;m2repository" && \
    sdkmanager "extras;google;google_play_services"

# ── Ruby + Fastlane (via Bundler) ──────────────────────────────────────────
COPY Gemfile.lock Gemfile ./
RUN gem install --no-document bundler && \
    bundle config set --local without 'development' && \
    bundle install --jobs 4 --retry 3 && \
    bundle update fastlane

# Default working directory — GitLab CI mounts the repo at /builds/...
WORKDIR /builds/techv1/nativeproxy
