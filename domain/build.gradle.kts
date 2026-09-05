plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.moyeota.driver.domain"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // CallAlertBus 가 프로세스 전역 StateFlow 로 콜 알림을 브로드캐스트한다 (FCM 서비스 → 화면)
    api(libs.kotlinx.coroutines.core)
}
