# مراقب النت الذكي v4.4 — Android (Android 14)

Foreground service that checks internet reachability and plays a notification
sound when the connection drops / returns. Works with the screen off.

## Build the APK

### Option A — GitHub (no install needed, ~5 min)
1. Create a new empty repository on github.com.
2. Upload ALL files of this folder (keep the `.github` folder).
3. Open the **Actions** tab → **Build APK** → wait for the green check.
4. Open the run → **Artifacts** → download `Morakeb_v4.4-Mahmoud_Kamal` → unzip → `Morakeb_v4.4-Mahmoud_Kamal.apk`.

### Option B — Android Studio
File ▸ Open this folder ▸ wait for Gradle sync ▸ Build ▸ Build APK(s).
Output: `app/build/outputs/apk/debug/app-debug.apk`.

## First run on the phone
1. Allow "install unknown apps" for the app you use to open the APK.
2. Open the app ▸ **ابدأ المراقبة** ▸ allow notifications.
3. Accept the "run in background / battery" prompt (essential so Android doesn't freeze it).
4. On Xiaomi / Oppo / Vivo / Samsung also enable *Autostart* and set battery to *No restrictions*.
