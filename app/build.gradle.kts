import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// 서명 키와 비밀번호는 저장소 밖에 둔다. 파일이 없으면 서명 없이 빌드된다.
val signingFile = file(System.getProperty("user.home") + "/.config/my-car-routine/keystore.properties")
val signingProps = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }

android {
    namespace = "io.github.tanmankeum"
    compileSdk = 35
    useLibrary("android.car")
    defaultConfig {
        applicationId = "io.github.tanmankeum"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        if (signingFile.exists()) create("release") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("com.google.zxing:core:3.5.3")
}
