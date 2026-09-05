plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

/**
 * 네이버 지도 NCP client-id. 승객 앱과 동일한 로딩 순서:
 * local.properties(개발자 로컬) → 환경변수 NAVER_MAPS_CLIENT_ID(CI) → 빈 문자열 폴백.
 */
val naverMapsClientId: String = providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText.orNull
    ?.lineSequence()
    ?.map(String::trim)
    ?.firstOrNull { it.startsWith("NAVER_MAPS_CLIENT_ID=") }
    ?.substringAfter('=')
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: providers.environmentVariable("NAVER_MAPS_CLIENT_ID").orNull.orEmpty()

android {
    namespace = "com.moyeota.driver.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.moyeota.driver"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["naverMapsClientId"] = naverMapsClientId
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":presentation"))
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
