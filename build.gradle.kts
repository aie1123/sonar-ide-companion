plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.7.0"
}

group = "com.cmi.codex"
version = providers.gradleProperty("pluginVersion").get()

fun requiredLocalPath(propertyName: String, environmentName: String): String =
    providers.gradleProperty(propertyName)
        .orElse(providers.environmentVariable(environmentName))
        .orNull
        ?: error("Set -P$propertyName=<path> or the $environmentName environment variable")

val intellijLocalPath = requiredLocalPath("intellijLocalPath", "INTELLIJ_LOCAL_PATH")
val sonarLintPluginPath = requiredLocalPath("sonarLintPluginPath", "SONARLINT_PLUGIN_PATH")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(intellijLocalPath)
    }

    // The MCP server is a bundled module rather than a regular marketplace plugin in
    // this local IDEA distribution, so add its API jar explicitly for compilation.
    compileOnly(files("$intellijLocalPath/plugins/mcpserver/lib/mcpserver.jar"))

    // SonarQube for IDE is installed locally and is not bundled with IntelliJ.
    // Keep it compile-only; the user's installed plugin supplies it at runtime.
    compileOnly(fileTree(sonarLintPluginPath) {
        include("lib/*.jar")
    })

    testImplementation(kotlin("test-junit5"))
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "261.22158.277"
            untilBuild = "261.*"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks {
    patchPluginXml {
        sinceBuild.set("261.22158.277")
        untilBuild.set("261.*")
    }

    test {
        useJUnitPlatform()
    }
}
