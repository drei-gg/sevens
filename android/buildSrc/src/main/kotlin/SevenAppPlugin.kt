import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

class SevenAppPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            // Shared identity/build contract. Branding is an external resource overlay, never a
            // source mutation.
            val app = extra["sevenApp"] as String
            val genericName = extra["sevenGenericName"] as String
            fun option(key: String, fallback: String = "") =
                providers.gradleProperty("sevens.$key").orElse(fallback).get()
            val brandRoot = option("brandDir")
            val brand = if (brandRoot.isEmpty()) null else file("$brandRoot/$app/android")
            val identity =
                java.util.Properties().apply {
                    brand
                        ?.resolve("identity.properties")
                        ?.takeIf { it.isFile }
                        ?.inputStream()
                        ?.use { load(it) }
                }
            fun identity(key: String, fallback: String = "") = identity.getProperty(key, fallback)
            val storeId = identity("applicationId")
            val productId = identity("unlockProductId")
            val personalUnlock = option("personalUnlock", "false")
            require(personalUnlock in listOf("true", "false")) {
                "sevens.personalUnlock must be true or false"
            }
            val version = option("versionName", "0.1.0")
            val code = option("versionCode", "1").toInt().also { require(it > 0) }
            fun literal(value: String): String {
                require(value.none { it == '\n' || it == '\r' })
                return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            }
            val uploadPath = providers.environmentVariable("SEVENS_UPLOAD_KEYSTORE").orNull
            val uploadAlias = providers.environmentVariable("SEVENS_UPLOAD_KEY_ALIAS").orNull
            val uploadStorePassword =
                providers.environmentVariable("SEVENS_UPLOAD_STORE_PASSWORD").orNull
            val uploadKeyPassword =
                providers.environmentVariable("SEVENS_UPLOAD_KEY_PASSWORD").orNull
            val signingReady =
                listOf(uploadPath, uploadAlias, uploadStorePassword, uploadKeyPassword).all {
                    !it.isNullOrBlank()
                }
            configure<ApplicationExtension> {
                compileSdk = 37
                defaultConfig {
                    minSdk = 30
                    targetSdk = 36
                    versionCode = code
                    versionName = version
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    buildConfigField("String", "UNLOCK_PRODUCT_ID", literal(""))
                    buildConfigField("String", "BILLING_PUBLIC_KEY", literal(""))
                }
                buildFeatures {
                    compose = true
                    buildConfig = true
                    resValues = true
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                flavorDimensions += "distribution"
                productFlavors {
                    create("generic") {
                        dimension = "distribution"
                        applicationId = "org.example.sevens.$app"
                        resValue("string", "app_name", genericName)
                        buildConfigField("boolean", "PERSONAL_UNLOCK", "false")
                    }
                    create("personal") {
                        dimension = "distribution"
                        applicationId = "${storeId.ifEmpty { "gg.drei.sevens.$app" }}.personal"
                        resValue(
                            "string",
                            "app_name",
                            identity("displayName", genericName) + " Personal",
                        )
                        buildConfigField("boolean", "PERSONAL_UNLOCK", personalUnlock)
                    }
                    create("store") {
                        buildConfigField("String", "UNLOCK_PRODUCT_ID", literal(productId))
                        buildConfigField(
                            "String",
                            "BILLING_PUBLIC_KEY",
                            literal(identity("billingPublicKey")),
                        )
                        dimension = "distribution"
                        applicationId = storeId.ifEmpty { "invalid.unconfigured.$app" }
                        resValue("string", "app_name", identity("displayName", genericName))
                        buildConfigField("boolean", "PERSONAL_UNLOCK", "false")
                    }
                }
                if (brand != null) {
                    sourceSets.getByName("personal").res.srcDir(brand.resolve("res"))
                    sourceSets.getByName("store").res.srcDir(brand.resolve("res"))
                }
                signingConfigs {
                    if (signingReady)
                        create("upload") {
                            storeFile = file(uploadPath!!)
                            storePassword = uploadStorePassword
                            keyAlias = uploadAlias
                            keyPassword = uploadKeyPassword
                        }
                }
                buildTypes {
                    debug { applicationIdSuffix = ".debug" }
                    release {
                        isMinifyEnabled = true
                        isShrinkResources = true
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro",
                        )
                    }
                }
                // Personal optimized APKs are deliberately development-signed and separately
                // identified.
                productFlavors.getByName("personal").signingConfig =
                    signingConfigs.getByName("debug")
                if (signingReady)
                    productFlavors.getByName("store").signingConfig =
                        signingConfigs.getByName("upload")
                lint {
                    lintConfig = rootProject.file("lint.xml")
                    warningsAsErrors = true
                    abortOnError = true
                    disable += "GradleDependency"
                } // Versions are intentionally pinned; updates require compatibility review.
                testOptions { animationsDisabled = true }
            }
            configure<KotlinAndroidProjectExtension> {
                compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
            }
            configure<ApplicationAndroidComponentsExtension> {
                beforeVariants {
                    if (
                        it.productFlavors.any { pair -> pair.second == "store" } &&
                            it.buildType == "debug"
                    )
                        it.enable = false
                }
            }
            val validateBrand =
                tasks.register("validateSevenBrand") {
                    doLast {
                        require(
                            brand?.resolve("res/mipmap-anydpi-v26/ic_launcher.xml")?.isFile == true
                        ) {
                            "Private icons required: -Psevens.brandDir=/path/to/brand"
                        }
                        require(identity("displayName").isNotBlank()) {
                            "Private android/identity.properties needs displayName"
                        }
                        val themed =
                            listOf(
                                    "res/mipmap-anydpi-v33/ic_launcher.xml",
                                    "res/mipmap-anydpi-v26/ic_launcher.xml",
                                )
                                .any {
                                    brand
                                        ?.resolve(it)
                                        ?.takeIf { file -> file.isFile }
                                        ?.readText()
                                        ?.contains("<monochrome") == true
                                }
                        require(themed) { "Private icons need an API-33 monochrome layer" }
                    }
                }
            val validateStore =
                tasks.register("validateSevenStore") {
                    dependsOn(validateBrand)
                    doLast {
                        require(personalUnlock == "false") {
                            "Store builds reject sevens.personalUnlock=true"
                        }
                        require(
                            storeId.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) &&
                                !storeId.startsWith("org.example.") &&
                                !storeId.endsWith(".personal")
                        ) {
                            "Configure the actual Play applicationId"
                        }
                        require(
                            productId.isNotBlank() && identity("billingPublicKey").isNotBlank()
                        ) {
                            "Configure unlockProductId and Play billingPublicKey"
                        }
                        require(signingReady && file(uploadPath!!).isFile) {
                            "Store builds require all four SEVENS_UPLOAD_* signing environment variables"
                        }
                        val keyStore =
                            java.security.KeyStore.getInstance(
                                file(uploadPath!!),
                                uploadStorePassword!!.toCharArray(),
                            )
                        val certificate =
                            keyStore.getCertificate(uploadAlias)
                                as? java.security.cert.X509Certificate
                        require(
                            certificate != null &&
                                !certificate.subjectX500Principal.name.contains(
                                    "Android Debug",
                                    ignoreCase = true,
                                )
                        ) {
                            "A production upload certificate is required; debug signing is forbidden"
                        }
                        val fingerprint =
                            java.security.MessageDigest.getInstance("SHA-256")
                                .digest(certificate.encoded)
                                .joinToString("") { "%02x".format(it) }
                        require(
                            identity("uploadCertificateSha256")
                                .replace(":", "")
                                .equals(fingerprint, ignoreCase = true)
                        ) {
                            "Upload certificate does not match identity.properties"
                        }
                        java.security.KeyFactory.getInstance("RSA")
                            .generatePublic(
                                java.security.spec.X509EncodedKeySpec(
                                    java.util.Base64.getDecoder()
                                        .decode(identity("billingPublicKey"))
                                )
                            )

                        require(
                            providers.gradleProperty("sevens.versionCode").isPresent &&
                                providers.gradleProperty("sevens.versionName").isPresent
                        ) {
                            "Store builds require explicit versionCode and versionName"
                        }
                        require(version.isNotBlank()) { "Store versionName must not be blank" }
                    }
                }
            tasks.configureEach {
                if (name.startsWith("prePersonal") && name.endsWith("Build"))
                    dependsOn(validateBrand)
                if (name.startsWith("preStore") && name.endsWith("Build")) dependsOn(validateStore)
            }
        }
}
