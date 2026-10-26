plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val serverUrl = (project.findProperty("serverUrl") as String?)?.trim() ?: ""
// PIN da manutenção (Menu no controle). TROQUE o padrão em gradle.properties.
val adminPin = ((project.findProperty("adminPin") as String?) ?: "").trim().ifEmpty { "1234" }

android {
    namespace = "br.com.guaraplay.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.com.guaraplay.player"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "ADMIN_PIN", "\"$adminPin\"")
    }
    // Chave de assinatura fixa (criada pelo build-apk.sh). Mantê-la é o que permite ATUALIZAR o app
    // em aparelhos já instalados e travados no modo administrador.
    val ks = file("../guara.keystore")
    if (ks.exists()) {
        signingConfigs {
            create("guara") {
                storeFile = ks; storePassword = "guaraplay"; keyAlias = "guara"; keyPassword = "guaraplay"
            }
        }
        buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("guara") } }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
