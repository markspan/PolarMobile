# PolarMobile

**PolarMobile** is an Android app that connects to a **Polar H10** chest strap over Bluetooth Low
Energy and relays its data to **[Lab Streaming Layer (LSL)](https://labstreaminglayer.org)**.
It is the Android counterpart of [PolarBLE](https://github.com/markspan/PolarBLE) (Windows) and
publishes the same streams, so existing LabRecorder setups and analysis scripts keep working.

Pair it with the NeuropsyOL apps
([SENDA](https://github.com/NeuropsyOL/SENDA), [RECORDA](https://github.com/NeuropsyOL/RECORDA),
[VIEWA](https://github.com/NeuropsyOL/VIEWA)) to record or view on the phone itself, or record from
any machine on the same network with LabRecorder.

## Features

- Scan for nearby Polar devices and pick the H10 to stream
- **ECG**: 130 Hz, µV, one channel (`<name>_ecg`, type `ECG`)
- **Accelerometer**: 25/50/100/200 Hz, ±2/4/8 g, mG, X/Y/Z (`<name>_acc`, type `Accelerometer`)
- Optional **heart rate** (`<name>_hr`, bpm) and **RR intervals** (`<name>_rr`, ms), irregular rate
- Runs as a foreground service: keeps streaming with the screen off, reconnects automatically
  after a BLE drop-out while the LSL outlets stay up
- Live ECG/ACC preview, battery level, and a per-outlet "consumer connected" indicator
- Time stamping is left to liblsl (see below)

`<name>` is the advertised device name, e.g. `Polar H10 A1B2C3D4`, exactly as in PolarBLE.

## Install

Download the APK from the GitHub Actions artifacts / Releases, or build it yourself (below), then:

```bash
adb install -r PolarMobile-1.0.0-release.apk
```

or copy the APK to the phone and open it (allow "install unknown apps").

Requires Android 8.0 (API 26) or newer with Bluetooth LE.

## Use

1. Moisten the H10 electrodes and put the strap on; it only advertises while worn.
2. Open PolarMobile, tap **Scan**, grant the Bluetooth (and notification) permissions.
3. Select your H10, choose the streams, tap **Start streaming**.
4. On the recording machine (same Wi-Fi/LAN), open LabRecorder and refresh; the streams appear.

### Networking notes

- The phone and the LSL consumers must be on the same network segment. Guest/eduroam networks
  often block multicast and client-to-client traffic; a dedicated router or the phone's own
  hotspot works reliably.
- The app holds a Wi-Fi multicast lock while streaming (Android drops multicast otherwise,
  which would make the streams invisible to LSL resolvers).
- If discovery still fails, configure LSL's `KnownPeers` with the phone's IP (shown in the
  status card) on the consumer side.
- Exclude the app from battery optimisation on aggressive OEM ROMs for long recordings.

## Timing

PolarMobile does not compute timestamps. Each PMD packet is pushed to its outlet as one chunk,
and liblsl assigns the times: the last sample gets `lsl_local_clock()` at push time and earlier
samples are spaced back by the nominal rate. Heart-rate and RR values (irregular rate) are
stamped at arrival. Correct for BLE delivery jitter and clock drift on the consumer side, as with
any LSL stream, e.g. with the dejittering in pyxdf / `load_xdf`.

## Build

Requirements: JDK 17, Android SDK (platform 35), NDK `28.2.13676358`, CMake `3.31.6`
(install the latter two with Android Studio's SDK Manager or
`sdkmanager "ndk;28.2.13676358" "cmake;3.31.6"`). The first build downloads liblsl 1.17.5 and
pugixml and compiles them for every ABI, which takes a few minutes.

```bash
./gradlew assembleDebug
```

```bash
./gradlew assembleRelease
```

APKs land in `app/build/outputs/apk/<variant>/`. Useful options:

- `-Pabis=arm64-v8a` builds a single ABI (much faster; default is arm64-v8a, armeabi-v7a, x86_64)
- `-Pliblsl.dir=/path/to/liblsl` (or `liblsl.dir=` in `local.properties`) builds against a local
  liblsl checkout instead of the downloaded release
- Release builds are signed with the project key when it is configured, otherwise with the debug
  key. Locally, set `polarmobile.keystore`, `polarmobile.keystore.password`,
  `polarmobile.key.alias` and `polarmobile.key.password` in `local.properties` (or the
  `POLARMOBILE_KEYSTORE`, `POLARMOBILE_KEYSTORE_PASSWORD`, `POLARMOBILE_KEY_ALIAS`,
  `POLARMOBILE_KEY_PASSWORD` environment variables). CI uses the repository secrets
  `POLARMOBILE_KEYSTORE_BASE64` (the base64-encoded keystore) and the three password/alias
  secrets. APKs signed with different keys cannot be installed over each other.

## Project layout

| Path | Contents |
| --- | --- |
| `app/src/main/cpp/` | CMake build of liblsl (static) + a small JNI bridge (`lsl_jni.cpp`) |
| `.../lsl/Lsl.kt` | Kotlin bindings: `LslNative`, `LslOutlet` |
| `.../ble/Pmd.kt` | Polar PMD protocol: UUIDs, start commands, frame and heart-rate parsers |
| `.../ble/PolarH10Client.kt` | GATT client with a serialized operation queue |
| `.../stream/PolarStreamService.kt` | Foreground service: BLE ↔ LSL, locks, reconnects |
| `.../MainActivity.kt`, `.../ui/SignalView.kt` | UI and live preview |

## Protocol reference

The PMD start commands are the same bytes PolarBLE writes:

- ECG: `02 00 00 01 82 00 01 01 0E 00` (type ECG, 130 Hz, 14-bit)
- ACC: `02 02 00 01 C8 00 01 01 10 00 02 01 08 00` (type ACC, 200 Hz, 16-bit, ±8 g)

Data frames: byte 0 measurement type, bytes 1–8 timestamp (ns, little endian), byte 9 frame type,
then samples (ECG: signed 24-bit µV; ACC frame type 1: 3 × signed 16-bit mG).

## License

LGPL-2.1, like PolarBLE. liblsl is MIT licensed.
