import com.android.build.api.artifact.SingleArtifact
import java.io.StringReader
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// --- Release signing -----------------------------------------------------------------------------------------------
// Local builds may provide keystore.properties; CI restores SIGNING_KEYSTORE_BASE64 to a file and exports
// SIGNING_KEYSTORE_PATH plus SIGNING_STORE_PASSWORD / SIGNING_KEY_ALIAS / SIGNING_KEY_PASSWORD.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: providers.environmentVariable(envKey).orNull

// CI sets UNSENDVAULT_REQUIRE_SIGNING=true for releases so a missing key fails instead of producing an unsigned APK.
val requireSigning = providers.environmentVariable("UNSENDVAULT_REQUIRE_SIGNING").orNull == "true"

// --- Versioning ----------------------------------------------------------------------------------------------------
// versionName = <major>.<minor>.<N>, versionCode = N where N = commit count on HEAD (monotonic on main).
val versionProps = Properties().apply {
    val text = providers.fileContents(rootProject.layout.projectDirectory.file("version.properties")).asText
    load(StringReader(checkNotNull(text.orNull) { "version.properties is missing" }))
}
val versionMajor = versionProps.getProperty("major").trim().toInt()
val versionMinor = versionProps.getProperty("minor").trim().toInt()
fun versionOverride(gradleProperty: String, envVar: String): String? =
    providers.gradleProperty(gradleProperty).orElse(providers.environmentVariable(envVar)).orNull?.trim()?.takeIf { it.isNotEmpty() }

val gitCommitCount: Int? by lazy {
    runCatching {
        providers.exec {
            workingDir = rootDir
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().toInt()
    }.getOrNull()?.takeIf { it > 0 }
}
val buildNumber: Int? = versionOverride("unsendvault.versionCode", "UNSENDVAULT_VERSION_CODE")?.toInt() ?: gitCommitCount
val appVersionCode: Int = buildNumber ?: 1
val appVersionName: String = versionOverride("unsendvault.versionName", "UNSENDVAULT_VERSION_NAME")
    ?: "$versionMajor.$versionMinor.${buildNumber ?: 0}"

tasks.register("printVersion") {
    group = "help"
    description = "Prints the versionName and versionCode this build uses."
    val line = "versionName=$appVersionName versionCode=$appVersionCode"
    doLast { println(line) }
}

/**
 * Fails the build when the merged manifest requests any permission outside the allowlist below — in particular any
 * network-state permission pulled in transitively by a library. INTERNET is allowed only for the opt-in update check.
 */
abstract class VerifyPermissionsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val text = mergedManifest.get().asFile.readText()
        val declared = Regex("""<uses-permission(?:-sdk-23)?[^>]*android:name="([^"]+)"""")
            .findAll(text).map { it.groupValues[1] }.toSet()
        val allowed = setOf(
            "android.permission.INTERNET", // update check only (opt-in)
            "android.permission.POST_NOTIFICATIONS", // opt-in saved-message notifications (Android 13+ runtime grant)
            "android.permission.USE_BIOMETRIC", // app lock (API 28+)
            "android.permission.USE_FINGERPRINT", // app lock on API 26-27, added by androidx.biometric
            "android.permission.WAKE_LOCK", // WorkManager (used by Glance + daily retention job)
            "android.permission.RECEIVE_BOOT_COMPLETED", // WorkManager reschedules the retention job after reboot
        )
        val dynamicReceiver = Regex(""".*\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION""")
        val unexpected = declared.filterNot { it in allowed || dynamicReceiver.matches(it) }
        check(unexpected.isEmpty()) { "Merged manifest declares permissions outside the allowlist: $unexpected" }
    }
}

android {
    namespace = "com.riyadm.unsendvault"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.riyadm.unsendvault"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        val storePath = signingValue("storeFile", "SIGNING_KEYSTORE_PATH")
        if (storePath != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
                // minSdk 26 verifies v2/v3 only; a v1 (JAR) signature would add ~100 KB of per-file digests.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
        if (requireSigning) {
            val release = findByName("release")
            check(
                release != null && release.storeFile?.isFile == true &&
                    !release.storePassword.isNullOrEmpty() && !release.keyAlias.isNullOrEmpty() &&
                    !release.keyPassword.isNullOrEmpty(),
            ) { "UNSENDVAULT_REQUIRE_SIGNING is set but the release signing key is missing or incomplete" }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "UnsendVault Debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            resValue("string", "app_name", "UnsendVault")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // The UI is English-only; drop the ~80 library translations (AppCompat, Compose, Biometric, Glance...).
        // Side effect: library-provided text such as BiometricPrompt's fallback strings is English on every device.
        localeFilters += listOf("en")
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
        resources.excludes += setOf(
            // kotlinx-coroutines-debug agent data; only read when the debug agent is installed (never in the app).
            "/DebugProbesKt.bin",
            // Metadata for kotlin-reflect, which the app does not ship (no kotlin.reflect.full usage).
            "/kotlin/**.kotlin_builtins",
            // AndroidX library version stamps; informational only, not read at runtime.
            "/META-INF/*.version",
        )
        // Compress native libraries (extracted at install, extractNativeLibs=true). The only libraries left are tiny
        // AndroidX helpers whose ELF segments are 16 KB-aligned, so they load on 16 KB-page devices; storing them
        // uncompressed would cost ~40 KB of lost compression plus up to 16 KB of zip-alignment padding per file.
        jniLibs.useLegacyPackaging = true
    }

    sourceSets {
        // Room exports schemas here so migrations can be verified by MigrationTestHelper.
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
        getByName("test").assets.directories.add("$projectDir/schemas")
    }
}

androidComponents {
    onVariants { variant ->
        val capName = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyPermissionsTask>("verifyPermissions$capName") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(verify) }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    // MainActivity is a FragmentActivity (BiometricPrompt) that also uses ActivityResult APIs (SAF backup pickers).
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.glance.appwidget.testing)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.glance.appwidget.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
