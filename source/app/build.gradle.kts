import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keys = Properties().apply {
    val f = rootProject.file("keys.properties")
    // Read as UTF-8 so values like non-English city names (é, ø, ü) survive.
    if (!providers.gradleProperty("veilShareable").isPresent && f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}
// Veil's shared Trakt app: the default when keys.properties has no Trakt client ID.
val sharedTrakt = Properties().apply {
    val f = rootProject.file("trakt.properties")
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}
val ownTrakt = keys.getProperty("TRAKT_CLIENT_ID", "").isNotBlank()
if (!ownTrakt) listOf("TRAKT_CLIENT_ID", "TRAKT_REDIRECT_URI").forEach { k ->
    sharedTrakt.getProperty(k)?.let { keys.setProperty(k, it) }
}
// Veil's shared Simkl, MDBList and WeTrakr apps (public device-code apps: a client ID only, no secret), used
// unless keys.properties has its own.
listOf(
    "simkl.properties" to "SIMKL_CLIENT_ID", "mdblist.properties" to "MDBLIST_CLIENT_ID",
    "wetrakr.properties" to "WETRAKR_CLIENT_ID",
).forEach { (file, k) ->
    if (keys.getProperty(k, "").isBlank()) {
        val f = rootProject.file(file)
        if (f.exists()) Properties().apply { f.reader(Charsets.UTF_8).use { load(it) } }
            .getProperty(k)?.let { keys.setProperty(k, it) }
    }
}
// When this source was last changed (git commit time of source/ or launcher/), for "update available".
// Falls back to the build time when there's no git (e.g. a ZIP download).
// For testing the notice: gradle assembleRelease -PveilSourceTime=<unix seconds> pretends to be older.
val sourceTime: Long = (findProperty("veilSourceTime") as String?)?.toLongOrNull() ?: runCatching {
    val p = ProcessBuilder("git", "log", "-1", "--format=%ct", "--", ".")
        .directory(rootProject.projectDir).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    p.waitFor()
    out.toLong()
}.getOrElse { System.currentTimeMillis() / 1000 }
// Escape non-ASCII as \uXXXX so the generated Java source is encoding-proof.
fun key(name: String) = "\"" + keys.getProperty(name, "").trim()
    .map { c -> if (c.code > 127) "\\u%04x".format(c.code) else c.toString() }.joinToString("") + "\""

android {
    namespace = "dev.veil.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.veil.launcher"
        minSdk = 28 // Android 9+. Newer-only features are guarded with version checks.
        targetSdk = 34
        versionCode = 3
        versionName = "2.1"
        buildConfigField("String", "TRAKT_CLIENT_ID", key("TRAKT_CLIENT_ID"))
        buildConfigField("String", "TRAKT_CLIENT_SECRET", key("TRAKT_CLIENT_SECRET"))
        buildConfigField("String", "TRAKT_REDIRECT_URI", key("TRAKT_REDIRECT_URI"))
        buildConfigField("String", "SIMKL_CLIENT_ID", key("SIMKL_CLIENT_ID"))
        buildConfigField("String", "MDBLIST_CLIENT_ID", key("MDBLIST_CLIENT_ID"))
        buildConfigField("String", "WETRAKR_CLIENT_ID", key("WETRAKR_CLIENT_ID"))
        buildConfigField("String", "TMDB_KEY", key("TMDB_KEY"))
        buildConfigField("String", "NEXTDNS_PROFILE", key("NEXTDNS_PROFILE"))
        buildConfigField("String", "NEXTDNS_API_KEY", key("NEXTDNS_API_KEY"))
        buildConfigField("String", "WEATHER_CITY", key("WEATHER_CITY"))
        // "Update available" in the Veil menu: one request a day to GitHub's public API. On unless
        // keys.properties has UPDATE_CHECK=false (it can also be switched off on the TV).
        buildConfigField("boolean", "UPDATE_CHECK", (keys.getProperty("UPDATE_CHECK", "true").trim() != "false").toString())
        buildConfigField("long", "SOURCE_TIME", "${sourceTime}L")
        // Lighter graphics for slow TVs (see AGENT_INSTALL.md). Off unless keys.properties has LITE_MODE=true.
        buildConfigField("boolean", "LITE_MODE", (keys.getProperty("LITE_MODE", "false").trim() == "true").toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3") // QR code on the Connect Trakt screen (made on the TV)
    testImplementation("junit:junit:4.13.2")
}
