# Audio Player

An Android edition of the Blue Purple music player. It scans local audio, reads embedded artwork, and includes playback, shuffle/repeat, artist and album browsing, folder selection, playlists, filters, and seven color themes. It can also download audio from YouTube and SoundCloud links as MP3, embed available cover art, show download progress, and add each finished track to a playlist.

## Build the APK

Pushes to `main` build separate debug APKs for ARM64 and 32-bit ARM devices and attach them to a GitHub Release. Most current phones should use `app-arm64-v8a-debug.apk`; older 32-bit devices should use `app-armeabi-v7a-debug.apk`.

The app asks for audio access when opened. Audio files stay on the device. Downloaded files are saved in Music/Audio Player by default, or in a folder you choose.

This project is distributed under GPL-3.0. The Android yt-dlp wrapper is licensed under GPL-3.0 as well.

