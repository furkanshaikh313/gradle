// tag::use-and-configure-plugin[]
plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}
// end::use-and-configure-plugin[]

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:2.0.0")
}

