@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}

group = "dev.zxilly.lib"

fun getGitHash(): String {
    val process = Runtime.getRuntime().exec(arrayOf("git", "rev-parse", "--short", "HEAD"))
    process.waitFor()
    return process.inputStream.bufferedReader().readText().trim()
}

fun getKey(key: String): String {
    return System.getenv(key) ?: project.properties[key] as? String ?: ""
}


if (System.getenv("CI") != null) {
    // action type
    val type = System.getenv("GITHUB_EVENT_NAME")
    version = when (type) {
        "push" -> "nightly.${getGitHash()}"
        "release" -> System.getenv("GITHUB_REF").split("/").last()
        else -> "snapshot"
    }
}

android {
    namespace = "dev.zxilly.lib.upgrader"
    compileSdk = 33

    defaultConfig {
        minSdk = 25
        targetSdk = 33

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.9.0")

    val workVersion = "2.7.1"
    implementation("androidx.work:work-runtime:$workVersion")
    implementation("androidx.work:work-runtime-ktx:$workVersion")

    val ktorVersion = "2.2.1"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    testImplementation("junit:junit:4.13.2")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.7.21")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.4")

    testImplementation("org.mockito:mockito-core:4.10.0")
    testImplementation("org.mockito:mockito-inline:4.10.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:4.1.0")

    androidTestImplementation("androidx.test.ext:junit:1.1.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "upgrader"
            afterEvaluate {
                from(components["release"])
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Zxilly/upgrader")
            credentials {
                username = getKey("GITHUB_ACTOR")
                password = getKey("GITHUB_TOKEN")
            }
        }
    }
}