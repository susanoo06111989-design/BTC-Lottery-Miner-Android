# BTC Lottery Miner Android v2.2.1 — Alpha 7

Experimental Bitcoin SOLO miner prototype. Not production ready.

## Alpha 7 changes
- GitHub Actions workflow for JUnit tests and Android debug APK build.
- Android thermal status polling (API 29+): stop benchmark and mining at SEVERE or above.
- Stop mining when activity leaves foreground; avoid running benchmark and mining simultaneously.
- Gradle version configured by CI, so no Gradle wrapper is included.

## Build
Upload the **contents** of this project directory to a GitHub repository, branch `main` or `master`. Open **Actions > Build Android APK > Run workflow**. If the build succeeds, download the debug APK artifact. It is a development build, not a Play Store release.

## Important limitations
- No APK has been built or installed during creation of this archive.
- No accepted share or confirmed Bitcoin block has been demonstrated.
- Stratum mining and share difficulty implementation require independent testing against a real pool before trusting results.
- Android thermal status is not a direct battery or CPU temperature measurement; device support varies.
- Mining stops when the app goes into background. Do not run unattended.
- CPU mining on a phone has negligible probability of winning a Bitcoin block and may damage battery longevity under sustained heat.
