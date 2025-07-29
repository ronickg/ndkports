import com.android.ndkports.CMakePortTask
import com.android.ndkports.CMakeCompatibleVersion
import com.android.ndkports.PrefabSysrootPlugin
import java.io.File
import java.net.URL
import org.gradle.api.DefaultTask

// Get project-specific configuration
val configs = rootProject.extra["projectConfigs"] as Map<String, Map<String, String>>
val projectConfig = configs[project.name] ?: error("No configuration found for project ${project.name}")
val zlibConfig = configs["zlib"] ?: error("No configuration found for Zlib")

val libVersion = projectConfig["libVersion"]!!
val snapshotVersion = projectConfig["snapshotVersion"] ?: ""
val zlibVersion = zlibConfig["libVersion"]!!

val libpngDownloadUrl = "https://downloads.sourceforge.net/project/libpng/libpng16/$libVersion/libpng-$libVersion.tar.xz"

group = "io.github.ronickg"
version = if (snapshotVersion.isNotEmpty()) "$libVersion-$snapshotVersion" else libVersion

plugins {
    id("maven-publish")
    id("com.android.ndkports.NdkPorts")
    id("signing")
    distribution
}

dependencies {
    implementation(project(":zlib"))
}

ndkPorts {
    ndkPath.set(File(project.findProperty("ndkPath") as String))
    source.set(project.file("src.tar.xz"))
    minSdkVersion.set(21)
}

// Task to download libpng source
tasks.register<DefaultTask>("downloadLibpng") {
    val outputFile = project.file("src.tar.xz")
    outputs.file(outputFile)

    doLast {
        // Download source
        URL(libpngDownloadUrl).openStream().use { input ->
            outputFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // Set the source property after download
        ndkPorts.source.set(outputFile)
    }
}

tasks.extractSrc {
    dependsOn("downloadLibpng")
}

tasks.prefab {
    generator.set(PrefabSysrootPlugin::class.java)
}

val buildTask = tasks.register<CMakePortTask>("buildPort") {
    cmake {
        arg("-DPNG_SHARED=ON")
        arg("-DPNG_STATIC=OFF")
        arg("-DPNG_TESTS=OFF")
        arg("-DPNG_EXECUTABLES=OFF")
        arg("-DZLIB_ROOT=$sysroot")
    }
}

tasks.prefabPackage {
    version.set(CMakeCompatibleVersion.parse(libVersion))

    licensePath.set("LICENSE")

    @Suppress("UnstableApiUsage") dependencies.set(
        mapOf(
            "zlib" to zlibVersion  // Make sure this matches your zlib version
        )
    )

    modules {
        create("png16") {
            dependencies.set(listOf("//zlib:z"))
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["prefab"])
            pom {
                name.set("libpng")
                description.set("The ndkports AAR for libpng.")
                val repoUrl = "https://github.com/${System.getenv("GITHUB_REPOSITORY") ?: "user/repo"}"
                url.set(repoUrl)
                licenses {
                    license {
                        name.set("libpng License")
                        url.set("http://www.libpng.org/pub/png/src/libpng-LICENSE.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        name.set("Ronald")
                    }
                }
                scm {
                    url.set(repoUrl)
                    connection.set("scm:git:${repoUrl}.git")
                }
            }
        }
    }

    repositories {
        maven {
            url = uri("${project.buildDir}/repository")
        }
    }
}

// Configure signing
signing {
    useGpgCmd()
    sign(publishing.publications["maven"])
}

distributions {
    main {
        contents {
            from("${project.buildDir}/repository")

            into("/") // Force files to be at root of zip

            include("**/*.aar")
            include("**/*.pom")
            include("**/*.module")
            include("**/*.aar.asc")
            include("**/*.pom.asc")
            include("**/*.module.asc")
            include("**/*.aar.md5")
            include("**/*.pom.md5")
            include("**/*.module.md5")
            include("**/*.aar.sha1")
            include("**/*.pom.sha1")
            include("**/*.module.sha1")
            include("**/*.aar.sha256")
            include("**/*.pom.sha256")
            include("**/*.module.sha256")
            include("**/*.aar.sha512")
            include("**/*.pom.sha512")
            include("**/*.module.sha512")
            include("**/maven-metadata.xml")
            include("**/maven-metadata.xml.asc")
            include("**/maven-metadata.xml.sha256")
            include("**/maven-metadata.xml.sha512")
        }
    }
}

tasks {
    distZip {
        dependsOn("publish")
        destinationDirectory.set(File(rootProject.buildDir, "distributions"))
    }
}