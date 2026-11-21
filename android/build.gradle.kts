allprojects {
    repositories {
        google()
        mavenCentral()
        // JitPack hosts CloudStream's library; only needed (and only added)
        // for the experimental STREAMBOX_CS3=1 build.
        if (System.getenv("STREAMBOX_CS3") == "1") {
            maven("https://jitpack.io")
        }
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
