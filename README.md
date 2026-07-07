# Polar H10 → WiZ HR lighting MVP

The interface uses the SarahVS dark visual system: near-black background, layered charcoal cards, lavender controls, rose HR metrics, cyan lighting accents, and a companion heart/ECG/light app icon.

Local-only Android app: Polar H10 BLE heart rate in, WiZ UDP commands out. There is no backend, login, database, cloud API, analytics, or AI integration.

Philips Hue Bridge bulbs are also supported over the bridge's local HTTPS API. Enter the bridge IP, press its physical link button, and tap **Pair** once. The generated bridge credential remains in Android private preferences; no Hue cloud account is used. Selected Hue bulbs participate in manual colors, brightness/off commands, HR themes, heartbeat reactions, and sleep/wake state restoration. For heartbeat reactions the app maintains one bridge-side `Polar WiZ HR` light group, allowing each brightness dip and restore to reach all selected Hue bulbs with two group commands rather than separate requests per bulb.

## Requirements

- Android 13 or newer (API 33; this is the current Polar BLE SDK minimum)
- Android Studio JBR / Java 17
- Android SDK platform 36 and build tools
- Phone and WiZ lights on the same non-guest Wi-Fi LAN

## Build and install

From this directory in PowerShell:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testDebugUnitTest assembleDebug
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r .\app\build\outputs\apk\debug\app-debug.apk
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell am start -n com.pulsepointlabs.polarwiz/.MainActivity
```

APK output: `C:\PulsePoint-Standalone\polar-wiz-hr\app\build\outputs\apk\debug\app-debug.apk`

## Tonight's test sequence

1. Wet the H10 strap contacts, snap the sensor onto the strap, and wear it. The H10 is awake only while attached to the strap/worn.
2. Open the app, tap **Scan**, and grant **Nearby devices**. Tap the discovered `Polar H10 ...` row. Live BPM and the latest RR interval should appear.
3. Put the phone on the same Wi-Fi network as the WiZ lights. Avoid guest/client-isolated Wi-Fi.
4. In WiZ V2, open **Settings → Security** under the current home and ensure **Local communication** is enabled. WiZ enables it by default.
5. Tap **Discover lights**. If broadcast discovery is blocked by the router, enter a bulb's IPv4 address and tap **Add IP**.
6. Leave a light checked, choose brightness, and tap **Warm**, **Violet**, **Red**, or **Off**.
7. Enable **HR-controlled lighting**. The app uses a ~5-second rolling average, 3 BPM hysteresis, at least 3 seconds between commands, and sends only when the zone changes.
8. To test without wearing the H10, enable **Demo BPM simulation**. It sweeps across all five zones.

Choose **Pulse**, **Ocean**, **Ember**, or **Daylight Tint** under Lighting theme. Daylight Tint remains bright at 80–100%, beginning at 5000 K neutral daylight and adding pale lavender, violet, blush, and coral as HR rises. Long-press any discovered light to replace `WiZ Light 1` with a local name such as `Bedroom Lamp`; aliases are stored only on the phone and keyed to the light's IP address.

**Daylight + colored heartbeat** keeps every selected WiZ and Hue bulb at bright 5000 K daylight between beats. Each heartbeat briefly changes to the current HR-zone color—gold under 80, lavender at 80–99, violet at 100–119, pink/red at 120–139, and red at 140+—then restores daylight. The heartbeat reaction slider controls the brightness depth of that colored beat.

Heartbeat scheduling uses a monotonic absolute beat clock and light RR smoothing. WiZ UDP pulses and Hue HTTPS pulses run independently, so a slow Hue bridge response cannot delay the next WiZ beat. Each platform also allows only one pulse operation at a time, preventing overlapping restore commands and visible stutter.

**Low-latency mode** is enabled by default while heartbeat reactions are active. It keeps one WiZ UDP socket open, requests Android high-performance and low-latency Wi-Fi modes, requests high-priority H10 BLE connection timing, and supports predicted beats down to 300 ms intervals (200 BPM). Disable it to reduce battery use. Standard BLE HR data reports RR timing after the electrical event, so this is predictive RR synchronization rather than raw-ECG R-wave triggering.

Every discovered light also has explicit **Name** and **Identify** buttons. Name stores a local alias. Identify sends three deep, half-second brightness dips to that bulb only, making it easy to match an IP/list row to the physical fixture without changing its saved automation color.

On narrow portrait screens these controls render as compact pencil and bulb icon buttons on the same row as a two-line light label, avoiding wrapped or clipped action text.

The brightness slider is also an automation-wide override. Once adjusted, its value is persisted locally and applied to every theme and every later HR zone instead of being replaced by that theme's built-in brightness. **Theme default** clears the override and restores each palette's normal zone brightness.

Releasing the brightness slider immediately sends a brightness-only command to every selected light, preserving its current color. The app also persists light IPs, friendly names, selections, lighting theme, brightness override, automation switches, and the last H10 Bluetooth address. On launch it restores saved lights immediately, refreshes LAN discovery, and reconnects the H10 when Bluetooth permission is already available.

## v0.2 reliability and control

- The application-owned runtime, rather than the Activity, owns BLE, WiZ automation, health checks, and background jobs.
- WiZ lights use their MAC as stable identity while DHCP IP addresses remain replaceable connection details.
- Saved lights are probed every 30 seconds for real online/offline status.
- Create rooms/groups directly from the control-group picker, then tap the group icon beside any light to assign it without retyping names. The selected group limits both manual and automated commands.
- Pause/resume freezes automation without disconnecting devices.
- Disabling automation restores the captured pre-automation WiZ state when the bulbs reported it.
- Heartbeat pulses prefer live RR timing over BPM-derived timing.
- Diagnostics exports a local, bounded event/command log through Android's share sheet.
- A signature-protected explicit broadcast accepts live HR/RR from SarahVS. Shared HR takes priority and releases this app's H10 connection; direct H10 automatically returns when the SarahVS feed goes stale.

Enable **Subtle heartbeat pulse** together with HR automation for a brightness dip paced from smoothed BPM. The **Heartbeat reaction** slider adjusts the dip from 2% (barely visible) to 40% (pronounced), persists across launches, and uses WiZ's native local `pulse` operation so the bulb restores itself without a second command. Pulse pacing is capped at two commands per second; normal zone commands remain throttled to one every three seconds.

## Sleep / wake lighting

Enable **Sleep / wake light automation** to combine the phone accelerometer with smoothed H10 heart rate. Sleep requires at least 20 minutes without meaningful movement, a stable five-minute HR window, and a plausible sleeping HR. On sleep, the app captures each selected bulb's exact local WiZ state before turning it off. Sustained movement, Android's significant-motion wake sensor, or a sustained HR increase wakes the lighting.

**Restore previous light state on wake** is enabled by default. It restores power, color/temperature, brightness, scene, and speed values reported by each bulb before sleep. If disabled, wake simply turns selected lights on at the current manual/theme brightness while retaining their existing color. Normal HR zone commands and heartbeat pulses are suppressed while sleep is active.

Sleep detection remains best-effort because Android manufacturers differ in how continuously they deliver ordinary accelerometer events with the screen off. The app runs its existing foreground service and also uses the low-power significant-motion wake sensor when the phone provides one. Keep the phone on the mattress or stable bedside surface where movement can be measured; aggressive battery restrictions may still need to be disabled for overnight use.

## Precision and automation controls

- **ECG R-wave precision mode** uses the official Polar BLE SDK to stream H10 ECG at 130 Hz, detects R peaks locally, and feeds H10 chest accelerometer motion into sleep/wake detection. If ECG streaming stalls, RR prediction resumes; if the precision connection produces no heart data, the app reconnects through standard BLE automatically.
- **Heartbeat shape** offers single beat, lub-dub, soft swell, and sharp ECG flash envelopes.
- **Auto-calibrate WiZ / Hue timing** measures median LAN round-trip time and delays the faster lighting path. Separate 0–300 ms sliders allow visual fine tuning afterward.
- **Circadian daylight schedule** applies 2400 K/25% overnight, a gradual 06:00 sunrise, bright daylight through the day, and progressively warmer evening light whenever HR automation is off.
- The foreground notification includes **Pause/Resume** and **Lights off** actions. Android's Quick Settings editor can add the **HR Lights** tile for one-tap automation control.
- The **signal watchdog** reports healthy, delayed, or lost HR/ECG state, stops free-running pulses after eight seconds without heart data, and records failures in Diagnostics.

Connecting the H10 or enabling automation starts a foreground `connectedDevice` service. Its persistent notification keeps the BLE GATT session, zone mapping, and heartbeat pulses running when the screen locks or another app is in front. Explicitly disconnect the H10 and disable automation to stop the service. Android 13+ may ask for notification permission; declining hides the drawer notification but does not prevent the foreground service from running.

## Permissions by Android version

- Android 12+ BLE scanning/connection uses runtime `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` (shown as Nearby devices). The app requests these when Scan is tapped and handles denial without crashing.
- The manifest retains legacy Bluetooth and location permissions capped at API 30. They are not requested on this app's supported API 33+ devices.
- WiZ control uses `INTERNET`, network/Wi-Fi state, and `CHANGE_WIFI_MULTICAST_STATE`. These are normal permissions and have no runtime dialog. A short-lived multicast lock is held only during discovery.
- Target SDK is 35, so Android 16's future local-network runtime permission behavior is not opted into by this MVP.

## Source layout

- `settings.gradle.kts` — repositories/modules
- `build.gradle.kts` — Android/Kotlin plugins
- `gradle.properties` — Gradle settings
- `gradlew`, `gradlew.bat`, `gradle/wrapper/*` — pinned Gradle 8.14.3 wrapper
- `app/build.gradle.kts` — app SDK levels and dependencies
- `app/proguard-rules.pro` — intentionally empty because debug/release minification is disabled
- `app/src/main/AndroidManifest.xml` — permissions and launcher activity
- `app/src/main/java/com/pulsepointlabs/polarwiz/MainActivity.kt` — single-screen UI and permission request
- `app/src/main/java/com/pulsepointlabs/polarwiz/MainViewModel.kt` — state, simulation, and command throttling
- `app/src/main/java/com/pulsepointlabs/polarwiz/ble/PolarH10Manager.kt` — standard BLE HR scan/connect/notifications and bounded reconnect
- `app/src/main/java/com/pulsepointlabs/polarwiz/wiz/WizLanManager.kt` — WiZ UDP discovery and `setPilot` control
- `app/src/main/java/com/pulsepointlabs/polarwiz/hr/HeartRateProcessor.kt` — rolling average and hysteretic zones
- `app/src/main/java/com/pulsepointlabs/polarwiz/model/Models.kt` — simple models and default zones
- `app/src/main/res/layout/activity_main.xml` — UI layout
- `app/src/main/res/values/{colors,styles,themes}.xml` — UI resources
- `app/src/test/java/com/pulsepointlabs/polarwiz/hr/HeartRateProcessorTest.kt` — smoothing/hysteresis tests

## Dependencies

- AndroidX Core, AppCompat, Activity, Lifecycle ViewModel/Runtime
- Official Polar BLE SDK 7.0.1 for optional ECG and H10 accelerometer streaming
- Kotlin coroutines Android
- JUnit 4 for local tests

WiZ uses `java.net.DatagramSocket` and `org.json`; no WiZ/cloud dependency is used.

Polar H10 uses Android's standard BLE Heart Rate Service (`0x180D`) and Heart Rate Measurement characteristic (`0x2A37`). This keeps the MVP independent of vendor-specific streaming and still provides BPM and RR intervals from the H10.

If another app/device already uses the H10, enable its dual-Bluetooth mode in Polar Flow or Polar Beat. The H10 supports at most two simultaneous BLE receivers when that option is enabled; this app reports and retries a rejected connection without crashing.
