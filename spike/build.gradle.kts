// Throwaway spike fixture (docs/04 §2.2 S1/S2/S3/S5/S6). Standalone build: it is NOT part of the
// main `iptv-player` Gradle build, so it can never affect the production modules.
plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
}
