import java.io.File

plugins {
    id("com.android.application") version "9.0.1" apply false
}

val externalBuildRoot =
    providers.environmentVariable("LOCALAPPDATA")
        .orElse(providers.systemProperty("java.io.tmpdir"))
        .map { File(it, "FitnessTrackerBuild") }
        .get()

layout.buildDirectory.set(externalBuildRoot.resolve("root"))

subprojects {
    layout.buildDirectory.set(externalBuildRoot.resolve(name))
}

tasks.register<Delete>("clean") {
    delete(externalBuildRoot)
}
