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
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
// 필요에 따라 일본어, 한국어 인식기 옵션 import
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
// import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions // 중국어 관련 주석 처리
// 로거 관련 import 추가
import com.example.overlaytranslator.domain.ocr.AppLogger
import com.example.overlaytranslator.domain.ocr.AndroidLogger
// import dagger.Binds // Binds 사용하지 않으므로 주석 처리 또는 제거 가능
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
    fun provideLatinTextRecognizer(): TextRecognizer {
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

    /* // 중국어 인식기 제공 함수 주석 처리
    @Singleton
    @Provides
    @Named("ChineseRecognizer")
    fun provideChineseTextRecognizer(): TextRecognizer {
        return TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    */

    @Singleton
    @Provides
    fun provideAppLogger(): AppLogger {
        return AndroidLogger()
    }

    @Singleton
    @Provides
    fun provideOcrManager(
        @Named("LatinRecognizer") latinRecognizer: TextRecognizer,
        @Named("JapaneseRecognizer") japaneseRecognizer: TextRecognizer,
        @Named("KoreanRecognizer") koreanRecognizer: TextRecognizer,
        // @Named("ChineseRecognizer") chineseRecognizer: TextRecognizer, // 중국어 인식기 주입 부분 주석 처리
        // settingsRepository: SettingsRepository, // OcrManagerImpl 생성자에서 제거됨
        appLogger: AppLogger
    ): OcrManager {
        val recognizers = mutableMapOf( // mutableMap으로 변경하여 조건부 추가 가능하도록 함
            "latin" to latinRecognizer,
            "japanese" to japaneseRecognizer,
            "korean" to koreanRecognizer
            // "chinese" 키로 chineseRecognizer를 추가하는 부분은 주석 처리된 상태 유지
        )
        // 만약 ChineseRecognizer가 주입된다면 여기에 추가하는 로직이 있었을 것임
        // 예: if (::chineseRecognizer.isInitialized) recognizers["chinese"] = chineseRecognizer

        return OcrManagerImpl(recognizers.toMap(), appLogger) // toMap()으로 불변 맵 전달
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

// Qualifier 어노테이션 정의
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Named(val value: String)
