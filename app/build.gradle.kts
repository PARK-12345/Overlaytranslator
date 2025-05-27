plugins {
    id("com.android.application") // 여기에 버전을 명시하거나, 프로젝트 레벨 버전을 따르도록 합니다.
    // 일반적으로 프로젝트 레벨에서 버전을 관리하고 모듈에서는 버전을 생략해도 되지만,
    // 문제가 발생한다면 명시적으로 버전을 동일하게 맞춰주는 것이 좋습니다.
    // 예: id("com.android.application") version "8.10.0" // 프로젝트 레벨과 동일하게
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt") // Hilt 사용을 위한 kapt
    id("com.google.dagger.hilt.android")
    //id("com.google.gms.google-services") // ML Kit 사용

}

android {
    namespace = "com.example.overlaytranslator"
    compileSdk = 35 // 타겟 API 35 (Android 15)

    defaultConfig {
        applicationId = "com.example.overlaytranslator"
        minSdk = 26 // Android 8.0 (Oreo) - MediaProjection, ML Kit 등을 고려
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17 // Java 17 사용
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17" // Kotlin JVM 타겟
    }
    buildFeatures {
        compose = false // XML 기반 UI
        viewBinding = true // ViewBinding 사용
    }
    // Hilt 사용을 위한 kapt 설정
    kapt {
        correctErrorTypes = true
    }
}

dependencies {
    // Core Library Desugaring 의존성
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5") // 2025년 5월 기준 최신 안정 버전 확인 필요

    // Core & UI
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("androidx.constraintlayout:constraintlayout:2.2.1") // libs 사용 가능하면 libs.androidx.constraintlayout 으로 변경 고려
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0") // libs 사용 가능하면 libs.androidx.lifecycle.viewmodel.ktx 으로 변경 고려
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.9.0") // libs 사용 가능하면 libs.androidx.lifecycle.livedata.ktx 으로 변경 고려
    implementation("androidx.activity:activity-ktx:1.10.1") // libs 사용 가능하면 libs.androidx.activity.ktx 으로 변경 고려
    implementation("androidx.fragment:fragment-ktx:1.8.7") // libs 사용 가능하면 libs.androidx.fragment.ktx 으로 변경 고려

    // Import the Firebase BoM
    //implementation(platform("com.google.firebase:firebase-bom:33.14.0")) // libs 사용 가능하면 libs.firebase.bom 으로 변경 고려
    //implementation("com.google.firebase:firebase-analytics") // libs 사용 가능하면 libs.firebase.analytics 으로 변경 고려

    // Hilt for Dependency Injection
    implementation("com.google.dagger:hilt-android:2.56.2") // libs 사용 가능하면 libs.hilt.android 으로 변경 고려
    kapt("com.google.dagger:hilt-compiler:2.56.2") // libs 사용 가능하면 libs.hilt.compiler 으로 변경 고려

    // Google Play Services Tasks (for await() extension)
    implementation("com.google.android.gms:play-services-tasks:18.3.0") // libs 사용 가능하면 libs.play.services.tasks 으로 변경 고려

    // Google ML Kit Text Recognition (On-device)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1") // libs 사용 가능하면 libs.mlkit.text.recognition 으로 변경 고려
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1") // libs 사용 가능하면 libs.mlkit.text.recognition.japanese 으로 변경 고려
    implementation("com.google.mlkit:text-recognition-korean:16.0.1") // libs 사용 가능하면 libs.mlkit.text.recognition.korean 으로 변경 고려

    // Gemini API (Google AI Generative Language Client)
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0") // 예시 버전, 최신 버전으로 업데이트 필요. libs 사용 가능하면 libs.google.ai.generativeai 으로 변경 고려

    // Coroutines for asynchronous programming
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") // libs 사용 가능하면 libs.kotlinx.coroutines.core 으로 변경 고려
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2") // libs 사용 가능하면 libs.kotlinx.coroutines.android 으로 변경 고려
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2") // libs 사용 가능하면 libs.kotlinx.coroutines.play.services 으로 변경 고려


    // Gson for JSON Serialization/Deserialization
    implementation("com.google.code.gson:gson:2.13.1") // libs 사용 가능하면 libs.gson 으로 변경 고려

    // 단위 테스트 (Unit Testing) 관련 의존성
    testImplementation("junit:junit:4.13.2") // libs 사용 가능하면 libs.junit 으로 변경 고려
    testImplementation("org.mockito:mockito-core:5.18.0") // libs 사용 가능하면 libs.mockito.core 으로 변경 고려
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0") // libs 사용 가능하면 libs.mockito.kotlin 으로 변경 고려
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2") // 코루틴 테스트 유틸리티 (libs 사용 가능하면 libs.kotlinx.coroutines.test 으로 변경 고려)
    testImplementation("com.google.android.gms:play-services-tasks:18.3.0") // ML Kit Task Mocking에 필요 (libs 사용 가능하면 libs.play.services.tasks 으로 변경 고려)
    testImplementation("androidx.test:core:1.6.1") // libs 사용 가능하면 libs.androidx.test.core 으로 변경 고려
    testImplementation("org.robolectric:robolectric:4.14.1") // libs 사용 가능하면 libs.robolectric 으로 변경 고려

    // UI 테스트 (Instrumentation Testing) - 에뮬레이터/실제 기기에서 실행
    androidTestImplementation("androidx.test.ext:junit:1.2.1") // libs 사용 가능하면 libs.androidx.test.ext.junit 으로 변경 고려
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1") // libs 사용 가능하면 libs.androidx.test.espresso.core 으로 변경 고려
    // === 여기를 추가/수정해주세요! ===
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2") // 코루틴 테스트 유틸리티 (libs 사용 가능하면 libs.kotlinx.coroutines.test 으로 변경 고려)
    // ===========================
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.56.2") // 프로젝트 레벨 Hilt 버전과 다를 수 있습니다. (libs 사용 가능하면 libs.hilt.android.testing 으로 변경 고려)
    kaptAndroidTest("com.google.dagger:hilt-android-compiler:2.56.2") // Hilt 테스트 컴파일러 (libs 사용 가능하면 libs.hilt.compiler 으로 변경 고려)
    androidTestImplementation("androidx.test:core-ktx:1.6.1") // libs 사용 가능하면 libs.androidx.test.core.ktx 으로 변경 고려
}
