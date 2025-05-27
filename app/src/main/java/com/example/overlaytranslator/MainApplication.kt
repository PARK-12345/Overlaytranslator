package com.example.overlaytranslator

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import android.util.Log

// Hilt를 사용하기 위해 Application 클래스에 어노테이션 추가
@HiltAndroidApp
class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("MainApplication", "Application Created")
        // 애플리케이션 초기화 코드 (필요한 경우)
    }
}
