import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

val platformCatalogTemplate = rootProject.file("config/modules.template.json")
val platformLocalConfig = rootProject.file("config/platform.local.properties")
val generatedPlatformAssets = layout.buildDirectory.dir("generated/platformCatalog/assets")
val generatedPlatformRes = layout.buildDirectory.dir("generated/platformCatalog/res")

val samsungSdk = file("libs/samsung-health-data-api-1.1.0.aar")
val samsungHealthEnabled = samsungSdk.exists()
        && !providers.gradleProperty("withoutSamsung").isPresent
val releaseKeystore = file(providers.environmentVariable("SHADOW_KEYSTORE_PATH")
    .orElse(rootProject.file("config/shadow-release.keystore").absolutePath).get())
val releasePasswordFile = file(providers.environmentVariable("SHADOW_KEYSTORE_PASSWORD_FILE")
    .orElse(rootProject.file("config/shadow-release.password").absolutePath).get())
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
        versionCode = 13
        versionName = "0.6.0"

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
        getByName("main").assets.srcDir(generatedPlatformAssets)
        getByName("main").res.srcDir(generatedPlatformRes)
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
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")

    testImplementation("junit:junit:4.13.2")
}

val generatePlatformCatalog by tasks.registering {
    inputs.file(platformCatalogTemplate)
    if (platformLocalConfig.isFile) {
        inputs.file(platformLocalConfig)
    }
    outputs.file(generatedPlatformAssets.map { it.file("modules.json") })
    outputs.file(generatedPlatformRes.map { it.file("xml/network_security_config.xml") })
    // Environment variables may supply the same values in CI, so always render before validation.
    outputs.upToDateWhen { false }

    doLast {
        val local = Properties()
        if (platformLocalConfig.isFile) {
            platformLocalConfig.inputStream().use(local::load)
        }

        fun optionalDeploymentValue(propertyName: String, environmentName: String): String? {
            return System.getenv(environmentName)?.trim()?.takeIf(String::isNotEmpty)
                ?: local.getProperty(propertyName)?.trim()?.takeIf(String::isNotEmpty)
        }

        fun deploymentValue(propertyName: String, environmentName: String): String {
            return optionalDeploymentValue(propertyName, environmentName)
                ?: throw GradleException(
                    "Missing Platform endpoint '$propertyName'. Copy " +
                            "config/platform.local.properties.example to " +
                            "config/platform.local.properties or set $environmentName."
                )
        }

        fun deploymentBoolean(propertyName: String, environmentName: String,
                              defaultValue: Boolean): Boolean {
            val raw = optionalDeploymentValue(propertyName, environmentName)
                ?: return defaultValue
            return when (raw.lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw GradleException("$propertyName must be true or false")
            }
        }

        fun checkedUri(label: String, value: String, requireHttps: Boolean): URI {
            val uri = try {
                URI(value)
            } catch (error: Exception) {
                throw GradleException("Invalid Platform URL for $label", error)
            }
            require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "Invalid Platform URL for $label"
            }
            require(uri.scheme == "https" || !requireHttps && uri.scheme == "http") {
                "$label must ${if (requireHttps) "use HTTPS" else "use HTTP or HTTPS"}"
            }
            return uri
        }

        val compiledRuntimePath = optionalDeploymentValue(
            "platform.runtimeFile", "SHADOW_APP_RUNTIME_FILE"
        )
        if (compiledRuntimePath != null) {
            val runtimeFile = rootProject.file(compiledRuntimePath)
            require(runtimeFile.isFile) {
                "Compiled App runtime does not exist: $compiledRuntimePath"
            }
            @Suppress("UNCHECKED_CAST")
            val runtime = JsonSlurper().parseText(runtimeFile.readText()) as? Map<String, Any?>
                ?: error("Compiled App runtime must be an object")
            require((runtime["schemaVersion"] as? Number)?.toInt() in setOf(4, 5)) {
                "Compiled App runtime must use schemaVersion 4 or 5"
            }
            val catalogOutput = generatedPlatformAssets.get().file("modules.json").asFile
            catalogOutput.parentFile.mkdirs()
            catalogOutput.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(runtime)))

            val modules = runtime["modules"] as? List<*> ?: error("Compiled App runtime modules are required")
            val cleartextHosts = modules.flatMap { raw ->
                val module = raw as? Map<*, *> ?: return@flatMap emptyList()
                val aliases = module["aliases"] as? List<*> ?: emptyList<Any?>()
                aliases.mapNotNull { it as? String }
            }.map { checkedUri("module alias", it, false) }
                .filter { it.scheme == "http" }
                .mapNotNull { it.host }
                .distinct()
            val domainConfig = if (cleartextHosts.isEmpty()) "" else {
                val domains = cleartextHosts.joinToString("\n") {
                    "        <domain includeSubdomains=\"false\">$it</domain>"
                }
                """
    <domain-config cleartextTrafficPermitted="true">
$domains
    </domain-config>"""
            }
            val networkSecurityOutput = generatedPlatformRes.get()
                .file("xml/network_security_config.xml").asFile
            networkSecurityOutput.parentFile.mkdirs()
            networkSecurityOutput.writeText(
                """<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />$domainConfig
</network-security-config>
"""
            )
            return@doLast
        }

        val endpoints = linkedMapOf(
            "IDENTITY_ISSUER" to deploymentValue(
                "platform.identityIssuer", "SHADOW_PLATFORM_IDENTITY_ISSUER"
            ),
            "NEXUS_CANONICAL_URL" to deploymentValue(
                "nexus.canonicalUrl", "SHADOW_NEXUS_CANONICAL_URL"
            ),
            "HEALTH_CANONICAL_URL" to deploymentValue(
                "health.canonicalUrl", "SHADOW_HEALTH_CANONICAL_URL"
            ),
            "STOCK_CANONICAL_URL" to deploymentValue(
                "stock.canonicalUrl", "SHADOW_STOCK_CANONICAL_URL"
            ),
            "GARDEN_CANONICAL_URL" to deploymentValue(
                "garden.canonicalUrl", "SHADOW_GARDEN_CANONICAL_URL"
            ),
            "TRAVEL_CANONICAL_URL" to deploymentValue(
                "travel.canonicalUrl", "SHADOW_TRAVEL_CANONICAL_URL"
            ),
            "LEDGER_CANONICAL_URL" to deploymentValue(
                "ledger.canonicalUrl", "SHADOW_LEDGER_CANONICAL_URL"
            ),
            "NOTIFICATIONS_CANONICAL_URL" to deploymentValue(
                "notifications.canonicalUrl", "SHADOW_NOTIFICATIONS_CANONICAL_URL"
            )
        )
        val aliasEndpoints = linkedMapOf(
            "NEXUS_ALIASES" to listOfNotNull(optionalDeploymentValue(
                "nexus.aliasUrl", "SHADOW_NEXUS_ALIAS_URL"
            )),
            "HEALTH_ALIASES" to listOfNotNull(optionalDeploymentValue(
                "health.aliasUrl", "SHADOW_HEALTH_ALIAS_URL"
            )),
            "STOCK_ALIASES" to listOfNotNull(optionalDeploymentValue(
                "stock.aliasUrl", "SHADOW_STOCK_ALIAS_URL"
            )),
            "GARDEN_ALIASES" to listOfNotNull(optionalDeploymentValue(
                "garden.aliasUrl", "SHADOW_GARDEN_ALIAS_URL"
            )),
            "TRAVEL_ALIASES" to listOfNotNull(optionalDeploymentValue(
                "travel.aliasUrl", "SHADOW_TRAVEL_ALIAS_URL"
            )),
            "LEDGER_ALIASES" to listOfNotNull(optionalDeploymentValue(
                "ledger.aliasUrl", "SHADOW_LEDGER_ALIAS_URL"
            )),
            "NOTIFICATIONS_ALIASES" to listOfNotNull(optionalDeploymentValue(
                "notifications.aliasUrl", "SHADOW_NOTIFICATIONS_ALIAS_URL"
            ))
        )
        val enabledValues = mapOf(
            "NOTIFICATIONS_ENABLED" to deploymentBoolean(
                "notifications.enabled", "SHADOW_NOTIFICATIONS_ENABLED", false
            )
        )

        checkedUri("Platform Identity issuer", endpoints.getValue("IDENTITY_ISSUER"), true)
        endpoints.filterKeys { it != "IDENTITY_ISSUER" }.forEach { (token, value) ->
            checkedUri(token.lowercase().replace('_', ' '), value, true)
        }
        val aliasUris = aliasEndpoints.flatMap { (token, values) ->
            values.map { checkedUri(token.lowercase().replace('_', ' '), it, false) }
        }

        var renderedCatalog = platformCatalogTemplate.readText()
        endpoints.forEach { (token, value) ->
            renderedCatalog = renderedCatalog.replace("@$token@", JsonOutput.toJson(value))
        }
        aliasEndpoints.forEach { (token, values) ->
            renderedCatalog = renderedCatalog.replace("@$token@", JsonOutput.toJson(values))
        }
        enabledValues.forEach { (token, value) ->
            renderedCatalog = renderedCatalog.replace("@$token@", value.toString())
        }
        require(!Regex("@[A-Z_]+@").containsMatchIn(renderedCatalog)) {
            "Unresolved token in Platform Catalog template"
        }
        val catalogOutput = generatedPlatformAssets.get().file("modules.json").asFile
        catalogOutput.parentFile.mkdirs()
        catalogOutput.writeText(renderedCatalog)

        val cleartextHosts = aliasUris
            .filter { it.scheme == "http" }
            .map { it.host }
            .distinct()
        val domainConfig = if (cleartextHosts.isEmpty()) {
            ""
        } else {
            val domains = cleartextHosts.joinToString("\n") {
                "        <domain includeSubdomains=\"false\">$it</domain>"
            }
            """
    <domain-config cleartextTrafficPermitted="true">
$domains
    </domain-config>"""
        }
        val networkSecurityOutput = generatedPlatformRes.get()
            .file("xml/network_security_config.xml").asFile
        networkSecurityOutput.parentFile.mkdirs()
        networkSecurityOutput.writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />$domainConfig
</network-security-config>
"""
        )
    }
}

val validateModules by tasks.registering {
    val catalog = generatedPlatformAssets.map { it.file("modules.json") }
    val iconDirectory = file("src/main/assets/icons")
    val supportedIcons = setOf(
        "app", "archive", "garden", "health", "ledger",
        "platform", "stock", "travel", "verse", "wingman"
    )
    val supportedCapabilities = setOf(
        "web", "health.scale", "health.samsung", "notification",
        "map", "media", "finance", "inbox", "operations"
    )
    dependsOn(generatePlatformCatalog)
    inputs.file(catalog)
    inputs.dir(iconDirectory)
    doLast {
        supportedIcons.forEach { icon ->
            require(iconDirectory.resolve("$icon.png").isFile) {
                "Missing bundled project icon: $icon.png"
            }
        }
        val catalogFile = catalog.get().asFile
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parseText(catalogFile.readText()) as Map<String, Any?>
        val schemaVersion = (root["schemaVersion"] as Number).toInt()
        require(schemaVersion in setOf(3, 4, 5)) {
            "modules.json: unsupported schemaVersion"
        }
        @Suppress("UNCHECKED_CAST")
        val platform = root["platform"] as? Map<String, Any?>
            ?: error("modules.json: platform must be an object")
        require((platform["catalogVersion"] as Number).toInt() == 1) {
            "modules.json: unsupported Platform Catalog version"
        }
        if (schemaVersion >= 4) {
            require((platform["deploymentId"] as? String)?.isNotBlank() == true) {
                "modules.json: platform.deploymentId is required"
            }
            require((platform["buildId"] as? String)?.matches(Regex("[a-f0-9]{64}")) == true) {
                "modules.json: platform.buildId must be a SHA-256 digest"
            }
        }
        val identityIssuer = platform["identityIssuer"] as? String
            ?: error("modules.json: platform.identityIssuer is required")
        require(identityIssuer.startsWith("https://")) {
            "modules.json: Platform Identity issuer must use HTTPS"
        }

        val modules = root["modules"] as? List<*>
            ?: error("modules.json: modules must be an array")
        val ids = mutableSetOf<String>()
        val enabledIds = mutableSetOf<String>()
        val catalogUrls = mutableSetOf<String>()
        modules.forEachIndexed { index, raw ->
            @Suppress("UNCHECKED_CAST")
            val module = raw as? Map<String, Any?>
                ?: error("modules.json: modules[$index] must be an object")
            val id = module["id"] as? String ?: error("modules[$index].id is required")
            require(id.matches(Regex("[a-z][a-z0-9-]{1,31}"))) {
                "modules.json: invalid id $id"
            }
            require(ids.add(id)) { "modules.json: duplicate id $id" }
            if ((module["enabled"] as? Boolean) == true) enabledIds.add(id)
            if (schemaVersion >= 4) {
                val productId = module["product_id"] as? String
                    ?: error("modules.json: $id.product_id is required")
                require(productId.matches(Regex("shadow-[a-z][a-z0-9-]{1,56}"))) {
                    "modules.json: invalid product_id for $id"
                }
                require(module["order"] is Number) {
                    "modules.json: $id.order is required"
                }
            }

            val canonicalUrl = module["canonical_url"] as? String
                ?: error("modules.json: $id.canonical_url is required")
            require(canonicalUrl.startsWith("https://")) {
                "modules.json: $id.canonical_url must use HTTPS"
            }
            require(catalogUrls.add(canonicalUrl.trimEnd('/'))) {
                "modules.json: duplicate Platform URL $canonicalUrl"
            }
            val aliases = module["aliases"] as? List<*>
                ?: error("modules.json: $id.aliases must be an array")
            aliases.forEachIndexed { aliasIndex, rawAlias ->
                val alias = rawAlias as? String
                    ?: error("modules.json: $id.aliases[$aliasIndex] must be a string")
                require(alias.startsWith("http://") || alias.startsWith("https://")) {
                    "modules.json: invalid alias URL for $id"
                }
                require(catalogUrls.add(alias.trimEnd('/'))) {
                    "modules.json: duplicate Platform URL $alias"
                }
            }

            @Suppress("UNCHECKED_CAST")
            val auth = module["auth"] as? Map<String, Any?>
                ?: error("modules.json: $id.auth must be an object")
            require(auth["mode"] in setOf(
                "public", "public-with-protected-paths", "forward-auth", "oidc", "service-bearer"
            )) { "modules.json: invalid auth mode for $id" }
            val groups = auth["groups"] as? List<*>
                ?: error("modules.json: $id.auth.groups must be an array")
            require(groups.all { it is String && it.matches(Regex("[a-z][a-z0-9-]{1,63}")) }) {
                "modules.json: invalid auth group for $id"
            }
            val healthPath = module["health_path"]
            require(healthPath == null || healthPath is String && healthPath.startsWith("/")) {
                "modules.json: $id.health_path must start with / or be null"
            }
            val color = module["color"] as? String ?: error("modules[$index].color is required")
            require(color.matches(Regex("#[0-9a-fA-F]{6}"))) {
                "modules.json: invalid color for $id"
            }
            val icon = module["icon"] as? String ?: error("modules[$index].icon is required")
            require(icon in supportedIcons) {
                "modules.json: unsupported icon for $id: $icon"
            }
            val capabilities = module["capabilities"] as? List<*>
                ?: error("modules.json: $id.capabilities must be an array")
            require(capabilities.all { it is String && it in supportedCapabilities }) {
                "modules.json: unsupported capability for $id"
            }
            require(capabilities.distinct().size == capabilities.size) {
                "modules.json: duplicate capability for $id"
            }
        }
        if (schemaVersion >= 5) {
            val homeModuleId = platform["homeModuleId"] as? String
                ?: error("modules.json: platform.homeModuleId is required")
            require(homeModuleId in enabledIds) {
                "modules.json: platform.homeModuleId must reference an enabled module"
            }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(validateModules)
}
