PiP Browser (Android)
=====================
Open this folder in Android Studio (Koala or newer), let Gradle sync, then
Build > Build APK(s)  (or just press Run with your phone connected).
APK: app/build/outputs/apk/debug/app-debug.apk

Engine: Android System WebView = the open-source Chromium engine.
Use: open a video / Shorts / Reels / TikTok page, start playing, then press Home
(or tap the PiP button). The PiP window has Previous / Play-Pause / Next.
Next/Previous on short feeds scroll to the next/previous short. On normal pages
they use the site's playlist, otherwise skip +/-10 s.
Requires Android 8.0+ (auto-PiP on gesture navigation: Android 12+).
