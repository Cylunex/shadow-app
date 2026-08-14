import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val samsungSdk = file("libs/samsung-health-data-api-1.1.0.aar")
val samsungHealthEnabled = samsungSdk.exists()
        && !providers.gradleProperty("withoutSamsung").isPresent

android {
    namespace = "com.shadow.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shadow.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("boolean", "SAMSUNG_HEALTH_AVAILABLE", samsungHealthEnabled.toString())
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main").java.srcDir(
            if (samsungHealthEnabled) "src/samsung/kotlin" else "src/noSamsung/kotlin"
        )
    }
}

dependencies {
    if (samsungHealthEnabled) {
        implementation(files(samsungSdk))
        implementation("com.google.code.gson:gson:2.13.2")
    }

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")

    testImplementation("junit:junit:4.13.2")
}

val validateModules by tasks.registering {
    val catalog = file("src/main/assets/modules.json")
    inputs.file(catalog)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parseText(catalog.readText()) as Map<String, Any?>
        require((root["schemaVersion"] as Number).toInt() == 1) {
            "modules.json: unsupported schemaVersion"
        }
        val modules = root["modules"] as? List<*>
            ?: error("modules.json: modules must be an array")
        val ids = mutableSetOf<String>()
        modules.forEachIndexed { index, raw ->
            @Suppress("UNCHECKED_CAST")
            val module = raw as? Map<String, Any?>
                ?: error("modules.json: modules[$index] must be an object")
            val id = module["id"] as? String ?: error("modules[$index].id is required")
            require(id.matches(Regex("[a-z][a-z0-9-]{1,31}"))) {
                "modules.json: invalid id $id"
            }
            require(ids.add(id)) { "modules.json: duplicate id $id" }
            for (field in listOf("startPath", "healthPath")) {
                val path = module[field] as? String ?: error("modules[$index].$field is required")
                require(path.isEmpty() || path.startsWith("/")) {
                    "modules.json: $id.$field must be empty or start with /"
                }
            }
            val color = module["color"] as? String ?: error("modules[$index].color is required")
            require(color.matches(Regex("#[0-9a-fA-F]{6}"))) {
                "modules.json: invalid color for $id"
            }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(validateModules)
}
