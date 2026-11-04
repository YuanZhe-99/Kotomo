import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing reads the ignored key.properties at the repository root (storeFile,
// storePassword, keyAlias, keyPassword). Without it, assembleRelease still builds but leaves the
// APK unsigned rather than falling back to the debug key.
val keyProperties = rootProject.file("key.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use(::load) }
}

android {
    namespace = "com.yuanzhe.kotomo"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.yuanzhe.kotomo"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_static") } }
    }

    externalNativeBuild {
        cmake {
            path = file("../core/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Dictionaries are memory-mapped straight from the APK, so they must stay uncompressed.
    androidResources { noCompress += "dict" }

    signingConfigs {
        if (keyProperties != null) {
            create("release") {
                storeFile = file(keyProperties.getProperty("storeFile"))
                storePassword = keyProperties.getProperty("storePassword")
                keyAlias = keyProperties.getProperty("keyAlias")
                keyPassword = keyProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }
}

/**
 * Builds the English dictionary asset with tool/BuildEnglishDict.java (pinned, hash-checked inputs
 * under .deps/).
 * Notes: runs from the repository root because the tool resolves .deps/ there; the output is
 * build output and never committed.
 */
abstract class EnglishDictTask : DefaultTask() {
    @get:InputFile abstract val tool: RegularFileProperty
    @get:Internal abstract val root: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        exec.exec {
            workingDir = root.get().asFile
            commandLine(java, tool.get().asFile.path, outputDir.file("main_en_us.dict").get().asFile.path)
        }
    }
}

val englishDict = tasks.register<EnglishDictTask>("englishDict") {
    tool = rootProject.layout.projectDirectory.file("tool/BuildEnglishDict.java")
    root = rootProject.layout.projectDirectory
    outputDir = layout.buildDirectory.dir("generated/dict")
}

/**
 * Puts librime's data into the assets under `rime/`: the prebuilt data from
 * scripts/prebuild-rime.sh (staged data plus the compiled `build/`) when it exists, otherwise only
 * the staged text data from core/rime/stage-data.cmake, which the phone then compiles itself.
 * Notes: prebuilding needs a Linux host (CI, or WSL on Windows); with KOTOMO_REQUIRE_RIME_PREBUILT
 * set (CI) a missing prebuild fails the build instead of shipping uncompiled data.
 */
abstract class RimeDataTask : DefaultTask() {
    @get:InputFile abstract val script: RegularFileProperty
    @get:InputFile abstract val cmake: RegularFileProperty
    @get:Internal abstract val root: DirectoryProperty
    @get:Internal abstract val prebuilt: DirectoryProperty
    @get:Input abstract val requirePrebuilt: Property<Boolean>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations
    @get:Inject abstract val files: FileSystemOperations

    @TaskAction
    fun stage() {
        val compiled = prebuilt.get().asFile
        if (compiled.resolve("build/default.yaml").isFile) {
            files.sync {
                from(compiled)
                into(outputDir.dir("rime"))
            }
            return
        }
        check(!requirePrebuilt.get()) { "no prebuilt librime data in $compiled; run scripts/prebuild-rime.sh" }
        logger.warn("rimeData: no prebuilt librime data; the phone will compile it on first use")
        files.delete { delete(outputDir.dir("rime")) }
        exec.exec {
            commandLine(
                cmake.get().asFile.path,
                "-DKOTOMO_ROOT=${root.get().asFile.path}",
                "-DOUT=${outputDir.dir("rime").get().asFile.path}",
                "-P", script.get().asFile.path,
            )
        }
    }
}

val rimeData = tasks.register<RimeDataTask>("rimeData") {
    script = rootProject.layout.projectDirectory.file("core/rime/stage-data.cmake")
    cmake = androidComponents.sdkComponents.sdkDirectory.map {
        it.file("cmake/3.22.1/bin/" + if (System.getProperty("os.name").startsWith("Windows")) "cmake.exe" else "cmake")
    }
    root = rootProject.layout.projectDirectory
    prebuilt = rootProject.layout.projectDirectory.dir("build/rime-prebuilt")
    requirePrebuilt = providers.environmentVariable("KOTOMO_REQUIRE_RIME_PREBUILT").map { it == "1" }.orElse(false)
    outputDir = layout.buildDirectory.dir("generated/rime")
    // The staged data follows the submodules; the script rewrites only changed files, so it always runs.
    outputs.upToDateWhen { false }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(englishDict, EnglishDictTask::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(rimeData, RimeDataTask::outputDir)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.savedstate.ktx)
    testImplementation(libs.junit)
}
