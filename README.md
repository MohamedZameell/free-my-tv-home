<div align="center">

<img src="app/res/drawable-nodpi/icon.png" width="96" alt="">

# Free My TV Home

**A calm, ad-free, tvOS-style home screen and a living weather screensaver for Google TV.**

A Dock of your own apps, a Top Shelf of what's worth watching, and a screensaver that shows the sky outside.<br>
No root. Nothing uninstalled. One button to undo.

[Install with Claude Code](#install-with-claude-code) · [Install by hand](#install-by-hand) · [Settings](#settings) · [Going back](#going-back) · [Credits](#credits)

![License: MIT](https://img.shields.io/badge/license-MIT-blue)
![Android 9+](https://img.shields.io/badge/Android%20TV-9%2B-3ddc84)
![No root](https://img.shields.io/badge/root-not%20needed-success)
![Made with Claude Code](https://img.shields.io/badge/made%20with-Claude%20Code-d97757)

</div>

> A fork of [**Free My TV**](https://github.com/simbasjungles/free-my-tv) by Simba and its contributors, rebuilt around
> a tvOS-inspired home screen and a new screensaver.

## What you get

**Home screen**
- **A Dock and a Top Shelf.** Your streaming apps on a smoked-glass Dock; above it, a full-screen carousel of trending
  titles that are on apps you already have. Scroll down for every app in a grid.
- **No ads.** No sponsored rows, banners or autoplaying promos.
- **Picks in your languages.** Say which languages you watch (for example Tamil, then Hindi), and those come first.
- **Back opens the screensaver**, like on an Apple TV.

**Weather screensaver** (60 fps on a TV's own chip)
- **The sky outside, drawn live.** Clouds that match the real cloud cover, rain at its real intensity, fog, snow and
  lightning; the sun and the moon (in its real phase) orbit a big temperature.
- **The day at a glance.** Feels-like, humidity, UV, wind, sunrise and sunset, and the next 24 hours along the bottom.
- **Optional: prayer times.** Today's five prayers with when each one ends, worked out on the TV from the sun's
  position: within seconds of NASA's ephemeris, no internet needed. Shafi'i or Hanafi, with your local timetable's
  margins.
- **Optional: a daily verse.** A short Quran verse or hadith in English each day, always whole (never cut mid-meaning).
- **Works offline.** Prayer times, the verse and the moon need no internet; the weather shows the last forecast for up
  to a day. If a power cut leaves the TV's clock wrong, the date and prayers hide until it's right again.

## Install with Claude Code

The easiest way: let [Claude Code](https://claude.com/claude-code) do it. About ten minutes.

1. **On the TV**, turn on developer mode: Settings → System → About → press *Android TV OS build* 7 times. Then
   Settings → System → Developer options → turn on **USB debugging**. Note the TV's IP address (Settings → Network &
   Internet → your Wi-Fi).
2. **On your computer** (same Wi-Fi as the TV), open Claude Code in any folder and say:

   > Install https://github.com/MohamedZameell/free-my-tv-home on my TV at 192.168.1.50. I live in Chennai.

   Swap in your TV's IP and your city. Want the prayer times or the daily verse? Say so too (*"with Hanafi prayer
   times and the daily verse"*). Claude clones the repo, reads [CLAUDE.md](CLAUDE.md), installs the tools it needs,
   builds the app with your settings and puts it on your TV, telling you each step before it does it.
3. **When the TV asks** *Allow USB debugging?*, tick *Always allow* and press OK.
4. **On the TV**, open *Free My TV* → *Home Screen* → *Free My TV*, and accept the debugging prompt once more.

## Install by hand

You need a Google TV or Android TV (Android 9 or newer) and a computer on the same Wi-Fi.

1. **On the TV**, turn on USB debugging (step 1 above).
2. **Tools:** a JDK (17+), the Android SDK command-line tools (build-tools 34+, platform android-34) and adb.
   - **macOS:**
     ```bash
     brew install --cask temurin android-commandlinetools android-platform-tools
     sdkmanager "build-tools;34.0.0" "platforms;android-34"
     ```
   - **Linux:** OpenJDK 17, Android's command-line tools and adb from your package manager, then the same
     `sdkmanager` line.
   - **Windows:** install [WSL](https://learn.microsoft.com/windows/wsl/install), then follow the Linux steps inside it.
3. **Get the code and build:**
   ```bash
   git clone https://github.com/MohamedZameell/free-my-tv-home && cd free-my-tv-home
   ./build.sh
   ```
   The first run creates `config.properties`. Set your city's `latitude` and `longitude`, your country in
   `tmdb_region`, your [TMDB key](#the-tmdb-key) and anything else from [Settings](#settings), then run `./build.sh`
   again. It ends with `Built FreeMyTV.apk`.
4. **Install:**
   ```bash
   adb connect 192.168.1.50:5555
   adb install -r FreeMyTV.apk
   ```
   On TCL TVs, also let it run in the background (TCL blocks it by default, and the switch never starts):
   ```bash
   adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"
   ```
5. **On the TV**, open *Free My TV* → *Home Screen* → *Free My TV*.

## Settings

All in `config.properties` (created by the first build, never committed). Some can also be changed on the TV in
*Free My TV* → *Home Settings*, and the TV's choice wins.

| Key | What it does | Default |
|---|---|---|
| `latitude`, `longitude` | Your city (decimal degrees): weather, sunset, prayer times | New York |
| `city` | The name on the weather card | `Weather` |
| `tmdb_key` | Your free TMDB key, for the picks | empty |
| `tmdb_region` | Your country (two letters): which titles are on which app | `US` |
| `picks_languages` | Languages first in the picks, e.g. `ta,hi,ml` (the first one leads) | empty |
| `prayer_times` | Prayer times on the screensaver | `false` |
| `prayer_school` | `shafii` or `hanafi` (the Asr time) | `shafii` |
| `prayer_minutes` | Minutes your timetable adds after noon, Asr, sunset and dusk, e.g. `15,2,7,15` | `0,0,0,0` |
| `daily_quote` | A short Quran verse or hadith on the screensaver each day | `false` |
| `language` | `auto` (follows the TV), `en`, `es` or `pt` | `auto` |
| `temperature` | `C`, `F` or `auto` | `auto` |
| `show_crypto` | Crypto prices from Binance | `true` |
| `currency` | Crypto prices in `USD`, `EUR`, `INR`… | `USD` |
| `disable_assistant` | Also turn off Google Assistant | `false` |

Changed something? Run `./build.sh` and install again. **Keep `freemytv.keystore`**: updates must be signed with it.

### The TMDB key

Free, and it takes two minutes: create an account at [themoviedb.org](https://www.themoviedb.org/signup), open
[Settings → API](https://www.themoviedb.org/settings/api) and request a developer key. Paste the *API Read Access
Token* after `tmdb_key=`. It's yours: `config.properties` stays out of git. Without it, everything works except the
picks.

## Going back

On the TV: *Free My TV* → *Home Screen* → *Original*. The factory home screen, screensaver and every app come back
exactly as they were. If the new home screen ever crashes three times in five minutes, it switches back by itself.

To remove it for good, switch to *Original* first, then uninstall *Free My TV* (Settings → Apps).

## What it changes

- It becomes the home screen and the screensaver.
- It turns off, without uninstalling: the stock home screen and screensaver, Google TV's recovery home, Play Games and
  YouTube Music; on TCL TVs, the TCL Channel, messages, store demo, hotel menu, manuals, mirroring and logging apps;
  Google Assistant only with `disable_assistant=true`.
- Picture, sound, inputs, remote pairing, Google services and the Play Store are on a protected list, checked before
  every change.
- Apps that saved a Wi-Fi network are never turned off, since Android would erase the network.

## Any TV

Google TV and Android TV: TCL, Sony, Hisense, Philips, Xiaomi and more, plus Chromecast with Google TV, the Google TV
Streamer, onn 4K and NVIDIA Shield. Built and tuned on a TCL 55Q6C (Android 14).

**Samsung, LG or any other TV:** plug a Google TV streamer into HDMI and set it up on that.

**A different brand:** open the folder in Claude Code and say *"set it up for my Sony"*. It reads your TV, finds its
home screen and promo apps, and walks you through a test. [CLAUDE.md](CLAUDE.md) tells it how.

## How it works

- **A plain Android app, no Gradle, no libraries.** [`build.sh`](build.sh) runs aapt2 → javac → d8 → zipalign →
  apksigner and makes a ~3 MB APK.
- **It uses the TV's own ADB, from inside the TV** (`127.0.0.1:5555`, its own key): the same commands you would run.
  Nothing listens to the network.
- **Drawn by hand on one Canvas** at 60 fps, with Android 13 shaders for the glass.

| File | What it is |
|---|---|
| [`HomeView.java`](app/src/io/freemytv/HomeView.java) | The home screen |
| [`WeatherView.java`](app/src/io/freemytv/WeatherView.java) | The weather screensaver |
| [`Prayers.java`](app/src/io/freemytv/Prayers.java) | Prayer times and sunrise/sunset, computed on the TV |
| [`HomeMode.java`](app/src/io/freemytv/HomeMode.java) | Switching between Free My TV and Original |
| [`Recs.java`](app/src/io/freemytv/Recs.java) | The picks (TMDB) |
| [`HomeData.java`](app/src/io/freemytv/HomeData.java) | Weather and prices |
| [`HomeApps.java`](app/src/io/freemytv/HomeApps.java) | The Dock, inputs and All Apps |
| [`Watchdog.java`](app/src/io/freemytv/Watchdog.java) | The safety net |

## Privacy

No accounts, no analytics, no tracking. The app talks to TMDB, Open-Meteo (with the coordinates you set), and Binance
only when `show_crypto=true`. Your settings stay on your computer and your TV.

## Credits

- **This edition** (tvOS-style home screen, weather screensaver, prayer times, daily verse, offline mode):
  designed by **Mohamed Zameel** ([@MohamedZameell](https://github.com/MohamedZameell)) and built together with
  **Claude** ([Claude Code](https://claude.com/claude-code), Anthropic).
- **The original [Free My TV](https://github.com/simbasjungles/free-my-tv)**: by **Simba**
  ([@simbasjungles](https://github.com/simbasjungles)), with Enner Pérez, Gabriel Vaz, Breno Nesto, Lucas Aureliano and Ismael Melgar. The home
  switch, ADB-on-the-TV, safety net and much more are theirs.
- **Quran translation:** Saheeh International, via [Tanzil](https://tanzil.net). **Hadith translations:** as published
  on [sunnah.com](https://sunnah.com) (Sahih al-Bukhari, Sahih Muslim). With thanks.
- This product uses the TMDB API but is not endorsed or certified by [TMDB](https://www.themoviedb.org/).
- Weather data by [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0). Prices from the [Binance](https://www.binance.com/) public API.
- Fonts: [Antonio](https://fonts.google.com/specimen/Antonio), [JetBrains Mono](https://www.jetbrains.com/lp/mono/)
  and [Inter](https://rsms.me/inter/) (SIL OFL 1.1). Icons: [Material Symbols](https://fonts.google.com/icons)
  (Apache 2.0). Coin logos: [cryptocurrency-icons](https://github.com/spothq/cryptocurrency-icons) (CC0).

## License

[MIT](LICENSE). Fork it, change it, ship it.

<sub>Made with ❤️ and Claude.</sub>
