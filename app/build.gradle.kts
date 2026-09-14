import java.util.Properties

val configuredVersionCode = providers.environmentVariable("HZZS_VERSION_CODE").orNull?.toIntOrNull()
    ?: providers.gradleProperty("hzzsVersionCode").orNull?.toIntOrNull()
    ?: 1
val configuredVersionName = providers.environmentVariable("HZZS_VERSION_NAME").orNull
    ?: providers.gradleProperty("hzzsVersionName").orNull
    ?: "0.1.0"
require(configuredVersionCode > 0) { "HZZS versionCode must be positive" }
require(configuredVersionName.isNotBlank() && configuredVersionName.length <= 64) {
    "HZZS versionName is invalid"
}

/**
 * Release 签名解析顺序（后者仅在前者缺失时生效）：
 * 1. 当前环境变量 ANDROID_KEYSTORE_*（CI / 推荐）
 * 2. 历史环境变量 AZEK431_RELEASE_*（本机旧脚本兼容）
 * 3. 仓库根目录 gitignore 的本地文件：keystore.properties / signing.properties / local.secrets.properties
 *
 * 密钥库文件本身永不入库；见 keystore.properties.example。
 */
val localSigningProperties = Properties().apply {
    listOf("keystore.properties", "signing.properties", "local.secrets.properties").forEach { name ->
        val file = rootProject.file(name)
        if (file.isFile) {
            file.inputStream().use { load(it) }
        }
    }
}

fun envFirst(vararg names: String): String? {
    for (name in names) {
        val value = providers.environmentVariable(name).orNull
        if (!value.isNullOrBlank()) return value.trim()
    }
    return null
}

fun signingValue(propertyKeys: List<String>, vararg envNames: String): String? {
    envFirst(*envNames)?.let { return it }
    for (key in propertyKeys + envNames) {
        val value = localSigningProperties.getProperty(key)?.trim()
        if (!value.isNullOrBlank()) return value
    }
    return null
}

val releaseStoreFilePath = signingValue(
    listOf("storeFile", "store.file", "keystore.path"),
    "ANDROID_KEYSTORE_PATH",
    "AZEK431_RELEASE_STORE_FILE",
)
val releaseStorePassword = signingValue(
    listOf("storePassword", "store.password"),
    "ANDROID_STORE_PASSWORD",
    "AZEK431_RELEASE_STORE_PASSWORD",
)
val releaseKeyAlias = signingValue(
    listOf("keyAlias", "key.alias"),
    "ANDROID_KEY_ALIAS",
    "AZEK431_RELEASE_KEY_ALIAS",
)
val releaseKeyPassword = signingValue(
    listOf("keyPassword", "key.password"),
    "ANDROID_KEY_PASSWORD",
    "AZEK431_RELEASE_KEY_PASSWORD",
)
val releaseSigningConfigured = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Clean Base：HZZS 原游戏视觉算法的原生视觉库（app/src/main/cpp + jni_bridge）已整体清退，
// 本模块不声明 NDK / CMake 原生构建；构建不再需要 Android NDK。
android {
    namespace = "top.azek431.hzzs"
    compileSdk = 37

    defaultConfig {
        applicationId = "top.azek431.hzzs"
        minSdk = 24
        targetSdk = 37
        versionCode = configuredVersionCode
        versionName = configuredVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        buildConfigField("String", "GITEE_OWNER", "\"Azek431\"")
        buildConfigField("String", "GITEE_REPO", "\"hzzs\"")
        buildConfigField("String", "GITHUB_OWNER", "\"Azek431\"")
        buildConfigField("String", "GITHUB_REPO", "\"hzzs\"")

    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                storeType = "PKCS12"
                // minSdk 24：不需要 V1；启用 V2/V3 以覆盖现代设备与签名轮换能力
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Debug 保留可调试符号（无原生库时该开关不影响构建）
            isJniDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isJniDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // 显式关闭未使用能力，减少 AGP 配置与资源管线工作
        aidl = false
        resValues = false
        shaders = false
        viewBinding = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = false
    }
    lint {
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = false
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        // 现代压缩 .so，减小 APK 与安装 I/O
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

// Hilt 走 KSP（不再 kapt 双编译 stub），日常 Kotlin 增量更稳。
ksp {
    arg("dagger.fastInit", "enabled")
    arg("dagger.hilt.android.internal.disableAndroidSuperclassValidation", "true")
}

tasks.matching {
    it.name.contains("Release", ignoreCase = true) &&
        it.name.contains("assemble", ignoreCase = true)
}.configureEach {
    doFirst {
        val missing = buildList {
            if (releaseStoreFilePath.isNullOrBlank()) {
                add("storeFile (ANDROID_KEYSTORE_PATH / AZEK431_RELEASE_STORE_FILE / keystore.properties)")
            }
            if (releaseStorePassword.isNullOrBlank()) {
                add("storePassword (ANDROID_STORE_PASSWORD / AZEK431_RELEASE_STORE_PASSWORD)")
            }
            if (releaseKeyAlias.isNullOrBlank()) {
                add("keyAlias (ANDROID_KEY_ALIAS / AZEK431_RELEASE_KEY_ALIAS)")
            }
            if (releaseKeyPassword.isNullOrBlank()) {
                add("keyPassword (ANDROID_KEY_PASSWORD / AZEK431_RELEASE_KEY_PASSWORD)")
            }
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing incomplete: ${missing.joinToString()}. " +
                    "See README「Release 构建与签名」or keystore.properties.example. " +
                    "Never commit the real keystore or passwords.",
            )
        }
        val store = file(releaseStoreFilePath!!)
        if (!store.isFile) {
            throw GradleException("Release keystore not found: ${store.absolutePath}")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.bouncycastle.bcprov)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
