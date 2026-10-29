# SnapAct setup (~10 min)

> **v4 update:** the cloud path is cut, so **skip §1 (Firebase) and the Firebase lines in §3**. Use **minSdk 34** and the dependencies in [PRD §7](PRD.md#7-dependencies). §4–5 (FileProvider) still apply. The starter `MainActivity.kt` is split per PRD §8, PR #0.

## 1. Firebase — ~~skip~~ (cloud cut)
1. console.firebase.google.com → new project → add Android app (your package name).
2. Download `google-services.json` → put in `app/`.
3. Build → **AI Logic** → Get started → choose **Gemini Developer API** (free tier).

## 2. New Android Studio project
"Empty Activity" (Compose). **minSdk 28**.

## 3. Gradle
Project `build.gradle.kts`:
```kotlin
plugins { id("com.google.gms.google-services") version "4.4.2" apply false }
```
`app/build.gradle.kts`:
```kotlin
plugins { id("com.google.gms.google-services") }

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.4.0")) // or latest
    implementation("com.google.firebase:firebase-ai")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
```

## 4. AndroidManifest.xml (inside `<application>`)
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fp"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

## 5. `app/src/main/res/xml/file_paths.xml`
```xml
<paths>
    <cache-path name="cache" path="." />
</paths>
```

## 6. Replace MainActivity.kt with the provided file (fix the package line).

## Demo script (2 min)
1. Problem: "We collect 50 cards at an event; 90% never get followed up."
2. Scan a real card, type note: "Met at booth, wants a demo next Tuesday".
3. Run agent → show the live trace: Gemini *decides* 4–5 tools.
4. Tap through: contact saved → personalized email → calendar invite on Tuesday → map.
5. Close: one photo → zero typing → follow-up done. Human approves every action.
