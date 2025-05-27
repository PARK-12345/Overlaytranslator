package com.example.overlaytranslator.di

import android.content.Context
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.view.WindowManager
import com.example.overlaytranslator.data.SettingsRepository
import com.example.overlaytranslator.data.SettingsRepositoryImpl
import com.example.overlaytranslator.domain.ocr.OcrManager
import com.example.overlaytranslator.domain.ocr.OcrManagerImpl
import com.example.overlaytranslator.domain.screencapture.ScreenCaptureManager
import com.example.overlaytranslator.domain.screencapture.ScreenCaptureManagerImpl
import com.example.overlaytranslator.domain.translation.TranslationManager
import com.example.overlaytranslator.domain.translation.TranslationManagerImpl
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions // 기본 라틴 문자 인식기
// 필요에 따라 일본어, 한국어 인식기 옵션 import
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
// 로거 관련 import 추가
import com.example.overlaytranslator.domain.ocr.AppLogger
import com.example.overlaytranslator.domain.ocr.AndroidLogger
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val SHARED_PREFS_NAME = "overlay_translator_prefs"

    @Singleton
    @Provides
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Singleton
    @Provides
    fun provideSettingsRepository(sharedPreferences: SharedPreferences): SettingsRepository {
        return SettingsRepositoryImpl(sharedPreferences)
    }

    @Singleton
    @Provides
    fun provideWindowManager(@ApplicationContext context: Context): WindowManager {
        return context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    @Singleton
    @Provides
    fun provideMediaProjectionManager(@ApplicationContext context: Context): MediaProjectionManager {
        return context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    @Singleton
    @Provides
    fun provideScreenCaptureManager(
        @ApplicationContext context: Context,
        mediaProjectionManager: MediaProjectionManager
    ): ScreenCaptureManager {
        return ScreenCaptureManagerImpl(context, mediaProjectionManager)
    }

    @Singleton
    @Provides
    @Named("LatinRecognizer")
    fun provideTextRecognizer(): TextRecognizer {
        return TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    @Singleton
    @Provides
    @Named("JapaneseRecognizer")
    fun provideJapaneseTextRecognizer(): TextRecognizer {
        return TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    @Singleton
    @Provides
    @Named("KoreanRecognizer")
    fun provideKoreanTextRecognizer(): TextRecognizer {
        return TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    // AppLogger를 제공하는 @Provides 메소드 추가
    @Singleton
    @Provides
    fun provideAppLogger(): AppLogger {
        return AndroidLogger() // AppLogger의 실제 구현체인 AndroidLogger 반환
    }

    @Singleton
    @Provides
    fun provideOcrManager(
        @Named("LatinRecognizer") latinRecognizer: TextRecognizer,
        @Named("JapaneseRecognizer") japaneseRecognizer: TextRecognizer,
        @Named("KoreanRecognizer") koreanRecognizer: TextRecognizer,
        settingsRepository: SettingsRepository, // ✨ 수정: SettingsRepository 의존성 추가
        appLogger: AppLogger
    ): OcrManager {
        val recognizers = mapOf(
            "latin" to latinRecognizer,
            "ja" to japaneseRecognizer,
            "ko" to koreanRecognizer
        )
        // ✨ 수정: OcrManagerImpl 생성 시 settingsRepository와 appLogger를 올바른 순서로 주입
        return OcrManagerImpl(recognizers, settingsRepository, appLogger)
    }

    @Singleton
    @Provides
    fun provideTranslationManager(settingsRepository: SettingsRepository): TranslationManager {
        return TranslationManagerImpl(settingsRepository)
    }

    @Provides
    @Singleton
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}

// Qualifier 어노테이션 정의 (여러 TextRecognizer 인스턴스를 구분하기 위해)
// 이 어노테이션은 사용자님의 기존 코드에 이미 정의되어 있으므로 그대로 유지합니다.
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Named(val value: String)

