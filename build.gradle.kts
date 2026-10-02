plugins {
    kotlin("multiplatform") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("com.android.library") version "9.3.1" apply false
    id("com.android.application") version "9.3.1" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}
allprojects {
    group = "app.waus.tmark"
    version = "0.1.0"
}
