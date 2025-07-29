import com.android.ndkports.AdHocPortTask
import com.android.ndkports.AndroidExecutableTestTask
import com.android.ndkports.CMakeCompatibleVersion
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import java.io.File
import java.net.URL

// Get project-specific configuration
val configs = rootProject.extra["projectConfigs"] as Map<String, Map<String, String>>
val projectConfig = configs[project.name] ?: error("No configuration found for project ${project.name}")

val libVersion = projectConfig["libVersion"]!!
val snapshotVersion = projectConfig["snapshotVersion"] ?: ""
val downloadUrl = "https://github.com/supranational/blst/archive/refs/tags/v$libVersion.tar.gz"

group = "io.github.ronickg"
version = if (snapshotVersion.isNotEmpty()) "$libVersion-$snapshotVersion" else libVersion

plugins {
    id("maven-publish")
    id("com.android.ndkports.NdkPorts")
    id("signing")
    distribution
}

ndkPorts {
    ndkPath.set(File(project.findProperty("ndkPath") as String))
    source.set(project.file("src.tar.gz"))
    minSdkVersion.set(21)
}

// Task to download source
tasks.register<DefaultTask>("downloadSource") {
    val outputFile = project.file("src.tar.gz")
    outputs.file(outputFile)

    doLast {
        URL(downloadUrl).openStream().use { input ->
            outputFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        ndkPorts.source.set(outputFile)
    }
}

tasks.extractSrc {
    dependsOn("downloadSource")
}

val buildTask = tasks.register<AdHocPortTask>("buildPort") {
    builder {
        // Create base directories
        run {
            val includeDir = installDirectory.resolve("include/blst")
            val libDir = installDirectory.resolve("lib")
            args(
                "mkdir", "-p",
                includeDir.absolutePath,
                libDir.absolutePath
            )
        }

        // Copy header files
        run {
            val bindingsSrcDir = sourceDirectory.resolve("bindings")
            val destDir = installDirectory.resolve("include/blst")
            args(
                "bash", "-c",
                "cp -v $bindingsSrcDir/*.{h,hpp} $destDir"
            )
        }

        // Compile straight to shared library
        run {
            val serverC = sourceDirectory.resolve("src/server.c").absolutePath
            val assemblyS = sourceDirectory.resolve("build/assembly.S").absolutePath

            // Create version script file
            val versionScript = buildDirectory.resolve("version.script")
            versionScript.parentFile.mkdirs()
            versionScript.writeText("{ global: blst_*; BLS12_381_*; local: *; };")

            args(
                toolchain.clang.absolutePath,
                "-shared",
                "-Wl,-soname,libblst.so",  // Add SONAME
                "-o", buildDirectory.resolve("libblst.so").absolutePath,
                "-fPIC",
                "-O",
                "-fno-builtin",
                "-Wall",
                "-Wextra",
                "-Wno-error=array-parameter",
                "-Wl,-Bsymbolic",
                "-Wl,--version-script=${versionScript.absolutePath}",
                "-Wl,-z,max-page-size=16384",  // 16KB page support
                serverC,
                assemblyS
            )

            // Set environment variables for build
            env("CC", toolchain.clang.absolutePath)
            env("AR", toolchain.ar.absolutePath)
        }

        // Copy the shared library
        run {
            val soFile = buildDirectory.resolve("libblst.so").absolutePath
            val destDir = installDirectory.resolve("lib")
            args(
                "cp", "-v",
                soFile,
                destDir.absolutePath
            )
        }
    }
}

tasks.prefabPackage {
    version.set(CMakeCompatibleVersion.parse(libVersion))
    modules {
        create("blst")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["prefab"])
            pom {
                name.set("BLST")
                description.set("The ndkports AAR for BLST.")
                val repoUrl = "https://github.com/${System.getenv("GITHUB_REPOSITORY") ?: "user/repo"}"
                url.set(repoUrl)
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://github.com/supranational/blst/blob/master/LICENSE")
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

            into("/")

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