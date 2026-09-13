# Feedback Android

Native Android-App fuer Feedback.

## Technik

- Kotlin 2.3.21
- Jetpack Compose
- Material 3
- Android Gradle Plugin 9.4
- compileSdk/targetSdk 37
- minSdk 24 (Android 7.0)
- Java 17

## Aktueller Stand

Das Grundprojekt und eine erste native Compose-Oberflaeche sind vorhanden. Noch nicht implementiert sind Pairing, Keystore-Identitaet, Hintergrund-Agent, Dateien, Bildschirmfreigabe und Remote-Control.

## Lokaler Build

Voraussetzungen:

- JDK 17
- Android SDK Platform 37
- Gradle 9.6+

Aus `android/`:

```bash
gradle :app:assembleDebug
```

Der CI-Workflow fuehrt denselben Debug-Build aus.

## Sicherheitsprinzip

Neue Android-Berechtigungen werden erst hinzugefuegt, wenn das zugehoerige Feature implementiert und dokumentiert ist. Das Manifest startet deshalb bewusst minimal.
