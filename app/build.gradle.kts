import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 正式版签名。
//
// 优先读 keystore.properties（放在仓库根，已被 .gitignore 忽略）；
// 没有就用本机的 debug 密钥 —— 这不是图省事：当前设备上装的就是 debug 签名的版本，
// 换密钥会导致覆盖安装失败、必须卸载重装从而丢数据。等确认要独立密钥时，
// 建好 keystore.properties 即可自动切换（那时需要先导出备份再卸载）。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.example.budgetapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.budgetapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 105
        versionName = "3.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // 三级来源，优先级从高到低：
            //   1) keystore.properties（本机开发，已被 .gitignore 忽略）
            //   2) 环境变量 TALLY_KEYSTORE_*（CI 用，值来自 GitHub Secrets）
            //   3) 本机 debug 密钥（兜底；当前设备装的就是它签的，换密钥会导致
            //      覆盖安装失败、必须卸载重装丢数据）
            val envStore = System.getenv("TALLY_KEYSTORE_FILE")
            when {
                keystoreProps.isNotEmpty() -> {
                    storeFile = file(keystoreProps.getProperty("storeFile"))
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias")
                    keyPassword = keystoreProps.getProperty("keyPassword")
                }
                !envStore.isNullOrBlank() -> {
                    storeFile = file(envStore)
                    storePassword = System.getenv("TALLY_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("TALLY_KEY_ALIAS")
                    keyPassword = System.getenv("TALLY_KEY_PASSWORD")
                }
                else -> {
                    storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // --- 默认生成的依赖 (不要删，如果报错就保留你原来的) ---
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    // --- 下面是手动添加的库 (直接用字符串，不要用 libs.xxx) ---

    // 1. Navigation (导航)
    implementation("androidx.navigation:navigation-fragment:2.7.7")
    implementation("androidx.navigation:navigation-ui:2.7.7")

    // 2. Room Database (数据库)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    annotationProcessor("androidx.room:room-compiler:$roomVersion") // Java 项目使用 annotationProcessor

    // 3. ViewModel & LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata:2.7.0")

    // 4. MPAndroidChart (图表库)
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("com.google.code.gson:gson:2.10.1")

    implementation("androidx.cardview:cardview:1.0.0")

    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("org.apache.poi:poi-ooxml:5.2.3")

    implementation("androidx.biometric:biometric:1.1.0")

    implementation("cn.6tail:lunar:1.3.15")

    implementation("com.google.android.flexbox:flexbox:3.0.0")

    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")

    implementation("com.caverock:androidsvg-aar:1.4")

}
