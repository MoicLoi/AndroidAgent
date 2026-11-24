plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.anroidagent.app"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // 研究模式（只记录不执行）：编译期常量，来自 gradle.properties
        buildConfigField(
            "boolean",
            "RESEARCH_MODE",
            (findProperty("anroidagent.researchMode") as String? ?: "false"),
        )
    }

    /**
     * 两个 APK 来自同一份代码：
     * - base：无障碍 + MediaProjection，**不含任何 shell 代码**
     * - pro ：base 的全部功能 + 追加包模块（Shizuku）
     *
     * 这样"追加包保留完整基础功能"是构造上成立的，不是靠人工同步。
     */
    flavorDimensions += "edition"
    productFlavors {
        create("base") {
            dimension = "edition"
            applicationId = "dev.anroidagent.app"
            resValue("string", "app_name", "AnroidAgent")
        }
        create("pro") {
            dimension = "edition"
            applicationId = "dev.anroidagent.pro"
            versionNameSuffix = "-pro"
            resValue("string", "app_name", "AnroidAgent Pro")
        }
    }

    /**
     * 演示签名。
     *
     * 密钥**不入库**：通过 -P 参数传入（或环境变量），仓库里只留这个配置。
     * 没有传参时 release 产出 unsigned APK，不会静默用调试密钥冒充发布包。
     */
    signingConfigs {
        create("demo") {
            val ks = (findProperty("demoKeystore") as String?)
                ?: System.getenv("ANROIDAGENT_DEMO_KEYSTORE")
            if (!ks.isNullOrBlank()) {
                // 相对路径按仓库根目录解析，避免落到 app/ 下
                storeFile = rootProject.file(ks)
                storePassword = (findProperty("demoStorePass") as String?)
                    ?: System.getenv("ANROIDAGENT_DEMO_STORE_PASS")
                keyAlias = (findProperty("demoKeyAlias") as String?) ?: "anroidagent-demo"
                keyPassword = (findProperty("demoKeyPass") as String?)
                    ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("demo")?.takeIf { it.storeFile != null }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        // AGP 9 起 resValue 默认关闭，flavor 里用 resValue 必须显式开启
        resValues = true
        // BuildConfig.RESEARCH_MODE
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":driver"))
    implementation(project(":agent"))
    implementation(project(":protocol"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.orgjson)

    // 只有 pro flavor 打进去；base 的 APK 里不存在 Shizuku 的任何类
    "proImplementation"(project(":premium-shizuku"))
}
