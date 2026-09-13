// Versions pinned to what is already in the Gradle cache on this machine, so
// a first build does not have to download a new AGP or Kotlin toolchain.
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
