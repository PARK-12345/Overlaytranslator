// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.10.0" apply false // 최신 AGP 버전 (2025년 5월 기준 예상)
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false // 업데이트된 Kotlin 버전
    id("com.google.dagger.hilt.android") version "2.56.2" apply false // 최신 Hilt 버전 (2025년 5월 기준 예상)
    //id("com.google.gms.google-services") version "4.4.2" apply false // Google Services (ML Kit 등)
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Hilt Gradle 플러그인 (버전은 plugins 블록과 일치)
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.56.2")
    }
}
