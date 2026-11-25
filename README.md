<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="Tuvora" width="300" />
  <br />
  <br />

  [![Contributors][contributors-shield]][contributors-url]
  [![Forks][forks-shield]][forks-url]
  [![Stargazers][stars-shield]][stars-url]
  [![Issues][issues-shield]][issues-url]
  [![License][license-shield]][license-url]

  <p>
    Tuvora for Android TV and Fire TV.
    <br />
    IPTV • Live TV guide • Multiview • Sports Centre • Built for the remote
  </p>

</div>

## About

Tuvora TV is the Android TV and Fire TV edition of [Tuvora](https://tuvora.co): a media player built around a D-pad. Sign in with a QR code (no typing with the remote), and the sources you added on your phone or the web are already there.

It is written in Kotlin with Jetpack Compose for TV. The phone and tablet apps live in [TuvoraMobile](https://github.com/paradox-kush/TuvoraMobile) and the desktop app in [TuvoraDesktop](https://github.com/paradox-kush/TuvoraDesktop).

Tuvora is built on Nuvio by NuvioMedia. See [Credits](#credits).

## What's inside

- **IPTV**: Xtream Codes, Stalker portal and M3U playlists, with Live TV, VOD and series.
- **Live TV guide** with EPG and channel pinning.
- **Multiview**: watch several live channels at once, choose which one has the sound.
- **Sports Centre**: fixtures with automatic channel matching, game alerts you control, and an option to hide scores until you choose to look.
- **Media servers**: Jellyfin and Emby libraries next to your other sources.
- **Account sync**: profiles, library, watch progress, watched items, add-ons and IPTV playlists follow you across devices.
- **A customisable Home**: reorder or hide any section.

## Installation

Download the latest APK from [tuvora.co/tv](https://tuvora.co/tv) or [GitHub Releases](https://github.com/paradox-kush/TuvoraTV/releases/latest) and install it on your device.

## Development

### Prerequisites

- Android Studio (latest version)
- JDK 17
- Android SDK

### Setup

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17      # adjust to where your JDK 17 lives
export ANDROID_HOME=/path/to/android-sdk

git clone https://github.com/paradox-kush/TuvoraTV.git
cd TuvoraTV
```

### Build

```bash
./gradlew :app:compileFullDebugKotlin
./gradlew :app:assembleFullDebug
```

### Run on an emulator or device

```bash
adb install -r app/build/outputs/apk/full/debug/app-full-arm64-v8a-debug.apk
adb shell am start -n com.tuvora.tv.debug/com.nuvio.tv.MainActivity
```

Install the split APK that matches the device's ABI (`arm64-v8a` for most TVs and boxes, `armeabi-v7a` for some older ones).

### Test

```bash
./gradlew :app:testFullDebugUnitTest
```

See [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. Every focusable element on TV must show a visible focus highlight that differs from "selected".

## Legal

Tuvora is a client-side interface for browsing metadata and playing media from sources you add yourself. It is intended for content you own or are otherwise authorized to access. Tuvora does not host, store or distribute any media content, and is not affiliated with any third-party source, catalog or content provider.

Read the full [Terms of Use](https://tuvora.co/terms) and [Privacy Policy](https://tuvora.co/privacy).

## Built with

- Kotlin
- Jetpack Compose and TV Material3
- ExoPlayer / Media3 and libmpv
- Hilt (dependency injection)
- Supabase for account sync

## Credits

Tuvora is built on [Nuvio](https://github.com/tapframe/NuvioTV) by NuvioMedia. All upstream attribution and licenses are preserved.

## License

Tuvora TV is licensed under the [GNU General Public License v3.0](LICENSE).

<!-- MARKDOWN LINKS & IMAGES -->
[contributors-shield]: https://img.shields.io/github/contributors/paradox-kush/TuvoraTV.svg?style=for-the-badge
[contributors-url]: https://github.com/paradox-kush/TuvoraTV/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/paradox-kush/TuvoraTV.svg?style=for-the-badge
[forks-url]: https://github.com/paradox-kush/TuvoraTV/network/members
[stars-shield]: https://img.shields.io/github/stars/paradox-kush/TuvoraTV.svg?style=for-the-badge
[stars-url]: https://github.com/paradox-kush/TuvoraTV/stargazers
[issues-shield]: https://img.shields.io/github/issues/paradox-kush/TuvoraTV.svg?style=for-the-badge
[issues-url]: https://github.com/paradox-kush/TuvoraTV/issues
[license-shield]: https://img.shields.io/github/license/paradox-kush/TuvoraTV.svg?style=for-the-badge
[license-url]: https://github.com/paradox-kush/TuvoraTV/blob/main/LICENSE
