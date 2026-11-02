plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.moyeota.driver.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    // NetworkModule 이 로깅 레벨을 빌드 타입별로 가르기 위해 필요하다
    // (디버그 BODY / 릴리즈 NONE — 토큰·전화번호·인증번호가 릴리즈 logcat 에 남지 않게 한다).
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // RemoteDriverRepository 의 진단 로그(android.util.Log)가 JVM 단위 테스트에서 "Stub!" 로
    // 터지지 않게 한다 — 로그 한 줄 때문에 하트비트 스킵 테스트가 실패하는 걸 막는 용도.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
