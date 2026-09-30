# Ayo Música para Android

Kotlin, Jetpack Compose (Material 3) e Media3 (ExoPlayer + MediaSession). Android 8.0+ (API 26), alvo API 37.

- `data/`: biblioteca pelo MediaStore, playlists (JSON no armazenamento do app), capas.
- `playback/`: `PlaybackService` (segundo plano, notificação, tela de bloqueio, retomar a fila) e `PlayerConnection`.
- `lyrics/`: LRC, LRCLIB e letras embutidas no MP3 (ID3 USLT/SYLT), com cache e atraso por música.
- `ui/`: telas em Compose.

```sh
export JAVA_HOME=~/.local/opt/jdk-21 ANDROID_HOME=~/Android/Sdk
./gradlew testDebugUnitTest assembleDebug       # APK de teste (io.github.atrzad.ayomusica.debug)
./gradlew assembleRelease                       # APK final; sem chave, assina com a de debug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Assinatura das versões finais: `AYO_KEYSTORE` (arquivo .jks) e `AYO_KEYSTORE_PASSWORD` no ambiente. No GitHub vêm
dos segredos `AYO_KEYSTORE_BASE64` e `AYO_KEYSTORE_PASSWORD`; a chave original fica em
`~/.local/share/ayo-musica-android/` (guarde uma cópia: sem ela, o celular não aceita atualizar o app).
