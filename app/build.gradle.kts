import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.unico.hk5mcore"
    // 本机已安装 Platform 36；运行目标仍对齐 Android 13
    compileSdk = 36

    defaultConfig {
        applicationId = "com.unico.hk5mcore"
        minSdk = 24
        // Android 13 (API 33)
        targetSdk = 33
        versionCode = 1
        versionName = "1.1.0"

        // 火山引擎豆包流式 ASR：新版控制台用 apiKey；旧版用 appKey + accessKey
        fun prop(key: String, default: String = ""): String =
            localProperties.getProperty(key, default).replace("\"", "\\\"")
        buildConfigField("String", "VOLC_API_KEY", "\"${prop("volc.apiKey")}\"")
        buildConfigField("String", "VOLC_APP_KEY", "\"${prop("volc.appKey")}\"")
        buildConfigField("String", "VOLC_ACCESS_KEY", "\"${prop("volc.accessKey")}\"")
        buildConfigField(
            "String",
            "VOLC_RESOURCE_ID",
            "\"${prop("volc.resourceId", "volc.seedasr.sauc.duration")}\"",
        )
        buildConfigField(
            "String",
            "VOLC_WS_URL",
            "\"${prop("volc.wsUrl", "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async")}\"",
        )

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-frtti", "-fexceptions")
                // NDK r27：启用 16 KB 页对齐（Google Play / Android 15+ 要求）
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("**/librk_dl_jni.so")
        }
        resources {
            // 避免 .rknn 被压缩导致模型拷贝损坏
            excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }

    androidResources {
        noCompress += "rknn"
    }
    ndkVersion = "27.0.12077973"
}

/** 每次 CMake 编完 JNI 后，同步一份到 jniLibs，避免设备上装到空 so */
val syncRkDlJniLib = tasks.register<Copy>("syncRkDlJniLib") {
    description = "Copy CMake-built librk_dl_jni.so into jniLibs/arm64-v8a"
    // Debug / RelWithDebInfo / Release 产物都扫，避免 assembleRelease 仍用旧 so
    from(layout.buildDirectory.dir("intermediates/cxx"))
    include("**/obj/arm64-v8a/librk_dl_jni.so")
    into("src/main/jniLibs/arm64-v8a")
    eachFile {
        path = name
    }
    includeEmptyDirs = false
    onlyIf {
        layout.buildDirectory.dir("intermediates/cxx").get().asFile
            .walkTopDown()
            .any { it.name == "librk_dl_jni.so" && it.length() > 4096L }
    }
}

val verifyMergedNativeLibs = tasks.register("verifyMergedNativeLibs") {
    description = "Fail fast if librk_dl_jni.so in APK merge output is empty/corrupt"
    dependsOn("mergeDebugNativeLibs")
    doLast {
        val libDir = layout.buildDirectory.dir(
            "intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a",
        ).get().asFile
        val jni = File(libDir, "librk_dl_jni.so")
        check(jni.isFile && jni.length() > 4096L) {
            "librk_dl_jni.so missing or corrupt (${jni.length()} bytes). " +
                "Run Build > Clean Project, then Rebuild."
        }
        val magic = jni.inputStream().use { it.readNBytes(4) }
        check(magic.size == 4 && magic[0] == 0x7f.toByte() && magic[1] == 'E'.code.toByte()) {
            "librk_dl_jni.so is not a valid ELF (magic=${magic.joinToString("") { "%02x".format(it) }})"
        }
    }
}

tasks.matching {
    it.name == "externalNativeBuildDebug" || it.name == "externalNativeBuildRelease"
}.configureEach {
    finalizedBy(syncRkDlJniLib)
}
tasks.matching { it.name == "assembleDebug" }.configureEach {
    dependsOn(verifyMergedNativeLibs)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.guava)
    implementation(libs.androidx.exifinterface)
    implementation(libs.okhttp)

    testImplementation(kotlin("test-junit"))
    debugImplementation(libs.androidx.compose.ui.tooling)
}
