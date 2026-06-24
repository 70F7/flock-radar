# Flock Radar (Android app)

One installable app that does the whole job: passively detects nearby Flock /
Raven / Penguin / PigVision cameras over **BLE + WiFi**, shows them on an offline
map with 110k+ known ALPR cameras, and hosts a one-tap **local hotspot** so your
MacBook (or anything) can join and open the same dashboard in a browser. No
file shuffling, no terminal, no root.

It is **passive only** — it listens to what devices broadcast. Nothing transmits,
jams, deauths, or attacks. Signatures come from
[NSM-Barii/flock-back](https://github.com/NSM-Barii/flock-back).

## Get the APK (no toolchain needed)

The included GitHub Action builds the APK in the cloud:

1. Make a new empty GitHub repo and push these files (or drag-and-drop upload
   via the web UI — include the `.github` folder).
2. The **Build APK** action runs automatically. Open the run → download the
   `flock-radar-debug-apk` artifact. (Or publish a Release to get a permanent
   APK download link — the action attaches it.)
3. Copy `app-debug.apk` to the phone, enable "install unknown apps", tap to
   install.

Prefer local? Open the folder in Android Studio and hit Run — same APK at
`app/build/outputs/apk/debug/app-debug.apk`.

## Use it

1. Open **Flock Radar**, grant the permissions it asks for (location, nearby
   devices / Bluetooth, notifications). Location must be **on** — Android ties
   BLE/WiFi scanning and the hotspot to it.
2. The map + live RF feed show immediately on the phone. Red = Flock, orange =
   other ALPR, cyan pulse = a camera detected live right now.
3. Tap **Start hotspot**. The bar shows the Wi-Fi name, password, and the URL.
4. On the **MacBook**: join that Wi-Fi network, open the shown URL
   (`http://192.168.49.1:8080`). Same dashboard, live.

## Make it work fully offline

Cameras (110k) are baked into the APK — always offline. For the **map
background**, cache tiles once while you still have internet:

- Pan/zoom to your area, tap **Cache this view**. Tiles are stored on the phone
  and served to every device afterward, no internet required.

Important: turning on the hotspot uses the Wi-Fi radio, so the phone drops its
own Wi-Fi internet while hosting. Cache your tiles **before** starting the
hotspot (or cache over mobile data).

## What it can and can't detect

| | Detected by the app? |
|---|---|
| Flock/Raven advertising over **BLE** | yes (native BLE scan) |
| Flock **beaconing a WiFi SSID** | yes (native WiFi scan, matched by SSID/MAC) |
| Flock sending **probe requests only** (hidden SSID) | **no** — needs monitor mode |

Monitor-mode probe-request sniffing can't run in a normal app; it needs root + a
monitor-capable adapter. If you run that separately (e.g. the `flock-radar`
Python detector on NetHunter with your Alfa), point it at the app with
`--server http://<phone-ip>:8080` and its hits drop straight onto this map — no
file transfer.

## Notes

- `minSdk 26`, `targetSdk 34`. Built with AGP 8.7 / Gradle 8.9 / Kotlin 1.9.22.
- Embedded server is NanoHTTPD on port 8080.
- This project couldn't be compiled in the environment it was generated in, so
  the first CI build may surface a small fix — the Action log points right at it.
- Tune detection in `Signatures.kt` (keep it in sync with flock-back upstream).
