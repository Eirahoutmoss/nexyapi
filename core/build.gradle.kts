import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Saf Kotlin çekirdek: Android'e bağımlı değildir, bu yüzden bilgisayarda hızlıca test edilir.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}
