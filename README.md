# Polar H10 → WiZ HR lighting MVP

Local-only Android app: Polar H10 BLE heart rate in, WiZ UDP commands out. There is no backend, login, database, cloud API, analytics, or AI integration.

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

Choose **Pulse**, **Ocean**, or **Ember** under Lighting theme. Long-press any discovered light to replace `WiZ Light 1` with a local name such as `Bedroom Lamp`; aliases are stored only on the phone and keyed to the light's IP address.

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
- Kotlin coroutines Android
- JUnit 4 for local tests

WiZ uses `java.net.DatagramSocket` and `org.json`; no WiZ/cloud dependency is used.

Polar H10 uses Android's standard BLE Heart Rate Service (`0x180D`) and Heart Rate Measurement characteristic (`0x2A37`). This keeps the MVP independent of vendor-specific streaming and still provides BPM and RR intervals from the H10.

If another app/device already uses the H10, enable its dual-Bluetooth mode in Polar Flow or Polar Beat. The H10 supports at most two simultaneous BLE receivers when that option is enabled; this app reports and retries a rejected connection without crashing.
