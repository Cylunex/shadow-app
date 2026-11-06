plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
tasks.test {
    providers.gradleProperty("lifeRepo").orNull?.let { systemProperty("lifeRepo", it) }
    val losnaRepo = providers.gradleProperty("losnaRepo")
    if (losnaRepo.isPresent) {
        systemProperty("losnaRepo", losnaRepo.get())
        systemProperty("fixtureScript", rootProject.file("scripts/losna-contract-fixture.mjs").absolutePath)
    } else exclude("**/LosnaIntegrationTest*")
}
