NativeProxy

A lightweight HTTP forward proxy + HTTPS CONNECT tunnel running as an
Android foreground service. Pure Kotlin, zero third-party networking
dependencies, and a Material 3 dark-themed control surface.

text

┌─────────────┐    ┌─────────────────┐    ┌──────────────┐
│  Phone app  │───▶│  NativeProxy    │───▶│  Upstream    │
│  (browser,  │◀───│  foreground Svc │◀───│  HTTP/HTTPS  │
│   curl)     │    │  ServerSocket   │    │              │
└─────────────┘    └─────────────────┘    └──────────────┘


The service:


    Accepts a configurable bind address (default 0.0.0.0) and port (default
    8080).

    Forwards plain HTTP requests, rewriting the absolute URL to a relative
    path before passing the bytes upstream.

    Handles CONNECT host:port for HTTPS by replying 200 Connection Established and then blind-piping bytes in both directions.

    Exposes a foreground notification with a "Stop" action and live status
    updates via LocalBroadcastManager-style explicit-package broadcasts.

    Persists last-used bind IP / port in SharedPreferences and validates
    input on the main thread.


The app shell is a single activity with a status card, a configuration
card, a primary start/stop button, and an "About" panel.


Project layout

This project follows the standard Android Studio layout and is wired up
to the GitLab CI / Fastlane template from
techv1/nativeproxy.

text

.
├── .gitlab-ci.yml          # CI pipeline (build → test → internal → beta → prod)
├── Dockerfile               # Android SDK + Fastlane image used by CI
├── Gemfile / Gemfile.lock   # Ruby deps for Fastlane
├── LICENSE
├── README.md
├── CONTRIBUTING.md
├── build.gradle.kts         # Root Gradle build (Kotlin DSL)
├── settings.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml   # Version catalog
│   └── wrapper/             # Gradle wrapper jar + properties
├── gradlew, gradlew.bat
├── fastlane/
│   ├── Appfile              # package_name, json_key_file
│   └── Fastfile             # buildDebug, buildRelease, test, internal, promote_*
└── app/
    ├── .gitignore
    ├── app.iml
    ├── build.gradle         # App-module build (Groovy DSL)
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/techpremium/miniproxy/
        │   ├── MainActivity.kt
        │   └── ProxyService.kt
        └── res/
            ├── drawable/    # ic_launcher_*, bg_card, bg_status_dot
            ├── layout/      # activity_main.xml
            ├── mipmap-anydpi-v26/   # adaptive icon descriptors
            ├── values/      # strings, colors, dimens, themes
            └── xml/         # network_security_config.xml


The two Java files in the original base.zip (proxyservice.java and
mainactivity.java in package com.example.miniproxy, plus their
companion mainactivity.xml) are preserved verbatim under
app/legacy-src/ and app/legacy-res/ so you can diff the two
implementations without breaking the active build.


Building locally

You need JDK 17 and the Android SDK 35 installed (set
ANDROID_HOME).

bash

./gradlew assembleDebug         # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease       # → app/build/outputs/apk/release/app-release.apk
./gradlew test                   # unit tests


The wrapper pins Gradle 8.6 and the Android Gradle Plugin 8.4.2; both are
resolved on the first run.

Running on a device

    1.
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    2.
    Open NativeProxy from the launcher.
    3.
    Grant the POST_NOTIFICATIONS prompt (Android 13+).
    4.
    Pick a bind IP and port, hit Start Proxy.
    5.
    Point your client at http://<device-ip>:<port> (or use
    adb reverse tcp:8080 tcp:8080 and point the client at
    http://127.0.0.1:8080).


Auto-versioning in CI

app/build.gradle reads the VERSION_SHA env var (defaulting to "1.0"
locally) and emits versionName "1.0-${VERSION_SHA}". The pipeline
populates it from ${CI_COMMIT_SHA:0:8}, and versionCode is
$CI_PIPELINE_IID — both come from the GitLab template.


CI / CD overview

Stage	Trigger	Job	Notes
environment	Dockerfile Δ	updateContainer	Rebuilds the SDK + Fastlane image
build	every commit	buildDebug	Runs fastlane buildDebug
build	every commit	buildRelease	Runs fastlane buildRelease
test	after build	testDebug	Runs fastlane test
internal	manual	publishInternal	fastlane internal → Play internal track
alpha	manual	promoteAlpha	fastlane promote_internal_to_alpha
beta	manual	promoteBeta	fastlane promote_alpha_to_beta
production	manual (master)	promoteProduction	fastlane promote_beta_to_production

You must upload a Play Store service-account JSON as a GitLab CI variable
(google_play_api_key.json) before the promote lanes can succeed. The
.promote_job template deliberately fails fast if the file is missing so
that the missing secret is obvious in the job log.


What was filled in

Coming from the placeholder template, these files were stubbed or missing
and have now been completed:


    Dockerfile — installs Android command-line tools, SDK platform 35,
    build-tools 35.0.0, and Fastlane via Bundler.

    .gitlab-ci.yml — environment / build / test / promote stages wired to
    the Fastfile.

    Gemfile + Gemfile.lock — pins Fastlane and its transitive gems.

    fastlane/Fastfile + fastlane/Appfile — lanes for build, test, and
    Play Store track promotion; package name matches the Android app.

    app/build.gradle (Groovy) + app/proguard-rules.pro — module build
    with the same com.techpremium.miniproxy namespace used by the
    Kotlin sources.

    gradle/wrapper/gradle-wrapper.jar + gradlew + gradlew.bat — the
    actual Gradle wrapper that matches gradle-wrapper.properties.

    Adaptive launcher icons (mipmap-anydpi-v26/ic_launcher{,_round}.xml).

    .gitignore (root + app/) and a stub android-template.iml /
    app/app.iml for IDE integration.


The application sources themselves — MainActivity.kt, ProxyService.kt,
AndroidManifest.xml, the layout, and the values/ resources — are
unchanged from base.zip.
```
