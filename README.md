# TMark for Kotlin

Kotlin implementation of [TMark](https://tmark.waus.app), a format for structured rich documents. This repository provides a parser and serializer, plus an optional native Android renderer. 

## Modules

| Dependency | Use | Platforms |
| --- | --- | --- |
| `app.waus.tmark:core:0.1.0` | Parse, inspect, create and serialize documents | JVM, JS, iOS device and simulator |
| `app.waus.tmark:android:0.1.0` | Render documents with Android Views; includes `core` | Android API 23+ |

`example` is a demo app. 

## Install

Version `0.1.0` is available on Maven Central, add `mavenCentral()` to your Gradle repositories and choose a dependency:

```kotlin
// Kotlin Multiplatform commonMain
implementation("app.waus.tmark:core:0.1.0")

// Android app
implementation("app.waus.tmark:android:0.1.0")
```


## Parse and write

```kotlin
import app.tmark.Tmark

val document = Tmark.decodeDocument(source)
val title = document.title
val encoded = Tmark.encode(document)
```

Parsing is strict by default. Use `Tmark.decodeDocument(source, soft = true)` to preserve unknown tags as `Unknown` nodes. Invalid fields on known tags still fail. `Tmark.decode` accepts any single TMark value; `decodeDocument` requires a document.

To add application-specific tags, register a `NodeType` in a `TmarkRegistry`, then use a `TmarkCodec` with that registry. The built-in `Tmark` object always uses the standard registry.

## Render on Android

Use an Android theme based on `Theme.Material3`. Render and update views on the main thread; parse large input on a background thread.

```kotlin
import android.widget.ScrollView
import app.tmark.Tmark
import app.tmark.android.TmarkView

val document = Tmark.decodeDocument(source)
val view = TmarkView(context).apply { render(document) }
val scrollView = ScrollView(context).apply { addView(view) }
```

`TmarkView.config` exposes link, task, error and custom-renderer callbacks. The [example app](example/src/main/kotlin/app/tmark/example/RaTeXWidgets.kt) shows how to add RaTeX math widgets through `TmarkWidgets`; without custom widgets, math shows its source expression. The Android module adds the `INTERNET` permission for its default HTTP(S) media loader; pass a `MediaContentLoader` to `render` for other media sources.

## Build

Use the included Gradle wrapper, JDK 17 and Android SDK platforms 35 and 36. On macOS with Xcode, iOS targets can also be compiled.

```sh
./gradlew :core:jvmTest :core:jsNodeTest :android:lintDebug \
  :android:assembleRelease :example:assembleDebug
```

Android device tests live in `android/src/androidTest` and `example/src/androidTest`; run them with a connected emulator or device. See the [TMark format site](https://tmark.waus.app) for format documentation and [`example`](example) for a working Android app.

