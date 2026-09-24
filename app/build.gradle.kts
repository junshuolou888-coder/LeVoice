import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Override with -PspeechModel=2025 (or baseline). Only the selected model enters the APK.
val speechModel = providers.gradleProperty("speechModel").orElse("2023").get()
require(speechModel in setOf("2023", "2025", "baseline")) { "speechModel must be 2023, 2025, or baseline" }
val speechRuntime = providers.gradleProperty("speechRuntime").orElse("android6").get()
require(speechRuntime in setOf("android6", "modern")) { "speechRuntime must be android6 or modern" }
val sherpaLibrary = if (speechRuntime == "modern") "sherpa-onnx-1.13.8.aar" else "sherpa-onnx-1.12.39-android6.aar"
// The STV package must be signed by the television vendor before installation.
val tvIntegration = providers.gradleProperty("tvIntegration").orElse("standard").get()
require(tvIntegration in setOf("standard", "stv")) { "tvIntegration must be standard or stv" }
val stvIntegration = tvIntegration == "stv"
// The observed vendor signing portal emits V1 only, including for pre-signed V2 inputs.
// Android 12 accepts V1 for target 29; target 30+ requires a vendor V2+ signature.
val stvTargetSdk = providers.gradleProperty("stvTargetSdk").orElse("29").get().toInt()
require(stvTargetSdk in setOf(29, 31)) { "stvTargetSdk must be 29 (V1 portal) or 31 (vendor V2+ required)" }
val stvVersionCode = providers.gradleProperty("stvVersionCode").orElse("33022825").get().toInt()
require(!stvIntegration || stvVersionCode > 33022820) { "stvVersionCode must exceed the installed OEM version 33022820" }
val modelDirectory = rootProject.file("models/zipformer-zh-$speechModel")
// Provisioned by the developer, never requested from the TV user.
val weatherConfig = rootProject.file("weather.local.json")
val weatherAssets = layout.buildDirectory.dir("generated/weatherAssets")
val prepareWeatherAssets by tasks.registering {
    inputs.file(weatherConfig)
    outputs.dir(weatherAssets)
    doLast {
        check(weatherConfig.isFile) { "Developer weather.local.json is required for packaging" }
        val config = JsonSlurper().parse(weatherConfig) as Map<*, *>
        val key = config["apiKey"] as? String
        check(!key.isNullOrBlank() && key.none { it.isWhitespace() || it.isISOControl() }) {
            "Developer weather credential is missing or invalid"
        }
        val output = weatherAssets.get().file("weather-defaults.json").asFile
        output.parentFile.mkdirs()
        output.writeText(groovy.json.JsonOutput.toJson(config))
    }
}
val preparedAssets = layout.buildDirectory.dir("generated/speechAssets/$speechModel")
val prepareSpeechAssets by tasks.registering(Sync::class) {
    into(preparedAssets)
    from("src/main/assets") {
        if (speechModel != "baseline") exclude("sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23-mobile/**")
    }
    if (speechModel != "baseline") {
        inputs.file(File(modelDirectory, "checksums.json"))
        from(modelDirectory) {
            include("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")
            into("speech-model")
        }
        doFirst {
            @Suppress("UNCHECKED_CAST")
            val expected = JsonSlurper().parse(File(modelDirectory, "checksums.json")) as Map<String, String>
            expected.forEach { (name, hash) ->
                val file = File(modelDirectory, name)
                check(file.isFile) { "Missing model file: $file. See models/README.md" }
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                check(actual == hash) { "Model checksum mismatch: $file" }
            }
        }
    }
}

android {
    namespace = "com.localvoicetv"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = if (stvIntegration) "com.stv.voice" else "com.localvoicetv"
        minSdk = if (speechRuntime == "modern") 24 else 23
        // This compatibility package targets the observed Android 12 vendor firmware.
        targetSdk = if (stvIntegration) stvTargetSdk else 36
        versionCode = if (stvIntegration) stvVersionCode else 1
        versionName = if (stvIntegration) "0.1.0-stv" else "0.1.0"
        buildConfigField("String", "SPEECH_MODEL", "\"$speechModel\"")
        buildConfigField("boolean", "STV_INTEGRATION", stvIntegration.toString())

        testInstrumentationRunner = "com.localvoicetv.CommandEngineDeviceChecks"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += listOf("onnx", "txt")
    }

    sourceSets.getByName("main").assets.setSrcDirs(listOf(preparedAssets, weatherAssets))
    if (stvIntegration) {
        sourceSets.getByName("main").java.srcDir("src/stv/java")
        sourceSets.getByName("test").java.srcDir("src/stvTest/java")
        listOf("debug", "release").forEach {
            sourceSets.getByName(it).manifest.srcFile("src/stv/AndroidManifest.xml")
        }
    }

    // Plain JVM matching tests stub Log; executor tests use Android classes via Robolectric.
    testOptions.unitTests.isReturnDefaultValues = true
    testOptions.unitTests.isIncludeAndroidResources = true
    sourceSets.getByName("test").resources.apply {
        srcDir("src/main/assets")
        include("default_commands.json")
    }
}

dependencies {
    implementation(files("libs/$sherpaLibrary"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

tasks.named("preBuild") { dependsOn(prepareSpeechAssets, prepareWeatherAssets) }
