plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.kotlin.kapt") version "2.1.20" apply false
}

// A project-only formatting dependency; public Java/SDK tools remain managed by C:\DevTools.
val kotlinFormatter by configurations.creating { isTransitive = false }
dependencies { kotlinFormatter("com.facebook:ktfmt:0.54:jar-with-dependencies") }
tasks.register<JavaExec>("formatKotlin") {
    group = "formatting"
    description = "Format Kotlin sources with the pinned project formatter."
    classpath = kotlinFormatter
    mainClass.set("com.facebook.ktfmt.cli.Main")
    args("--kotlinlang-style")
    args(fileTree("app/src") { include("**/*.kt") }.files.sortedBy { it.path }.map { it.path })
}
