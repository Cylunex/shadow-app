import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val samsungSdk = file("libs/samsung-health-data-api-1.1.0.aar")
val samsungHealthEnabled = samsungSdk.exists()
        && !providers.gradleProperty("withoutSamsung").isPresent
val releaseKeystore = file(providers.environmentVariable("SHADOW_KEYSTORE_PATH")
    .orElse("/data/project/.secrets/shadow-app/shadow-release.jks").get())
val releasePasswordFile = file(providers.environmentVariable("SHADOW_KEYSTORE_PASSWORD_FILE")
    .orElse("/data/project/.secrets/shadow-app/shadow-release.password").get())
val releaseSigningAvailable = releaseKeystore.isFile && releasePasswordFile.isFile
val releaseRequested = gradle.startParameter.taskNames.any {
    val taskName = it.substringAfterLast(":")
    taskName.contains("release", ignoreCase = true)
            || taskName.equals("assemble", ignoreCase = true)
            || taskName.equals("build", ignoreCase = true)
            || taskName.equals("bundle", ignoreCase = true)
}

if (releaseRequested && !releaseSigningAvailable) {
    throw GradleException(
        "Release signing material is unavailable. Use scripts/build-release.sh " +
                "or set SHADOW_KEYSTORE_PATH and SHADOW_KEYSTORE_PASSWORD_FILE."
    )
}

android {
    namespace = "com.shadow.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shadow.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"

        buildConfigField("boolean", "SAMSUNG_HEALTH_AVAILABLE", samsungHealthEnabled.toString())
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("shadowRelease") {
                val password = releasePasswordFile.readText().trim()
                storeFile = releaseKeystore
                storePassword = password
                keyAlias = "shadow"
                keyPassword = password
            }
        }
    }

    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
        getByName("release") {
            isMinifyEnabled = false
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("shadowRelease")
            }
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
        require((root["schemaVersion"] as Number).toInt() == 2) {
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
            val routes = module["routes"] as? List<*>
                ?: error("modules[$index].routes must be an array")
            require(routes.isNotEmpty()) { "modules.json: $id.routes must not be empty" }
            require(routes.size <= 2) { "modules.json: $id.routes supports at most two routes" }
            val routeServers = mutableSetOf<String>()
            routes.forEachIndexed { routeIndex, rawRoute ->
                @Suppress("UNCHECKED_CAST")
                val route = rawRoute as? Map<String, Any?>
                    ?: error("modules[$index].routes[$routeIndex] must be an object")
                val server = route["server"] as? String
                    ?: error("modules[$index].routes[$routeIndex].server is required")
                require(server == "nas" || server == "cloud") {
                    "modules.json: invalid route server for $id"
                }
                require(routeServers.add(server)) {
                    "modules.json: duplicate $server route for $id"
                }
                val port = (route["port"] as? Number)?.toInt()
                require(!route.containsKey("port") || port != null) {
                    "modules.json: port must be a number for $id.$server"
                }
                require(port == null || port in 1..65535) {
                    "modules.json: invalid port for $id.$server"
                }
                val startPath = route["startPath"] as? String
                    ?: error("modules[$index].routes[$routeIndex].startPath is required")
                require(startPath.startsWith("/")) {
                    "modules.json: $id.$server.startPath must start with /"
                }
                val probePath = route["probePath"] as? String
                    ?: error("modules[$index].routes[$routeIndex].probePath is required")
                require(probePath.isEmpty() || probePath.startsWith("/")) {
                    "modules.json: $id.$server.probePath must be empty or start with /"
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
