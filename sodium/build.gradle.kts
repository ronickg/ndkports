import com.android.ndkports.AdHocPortTask
import com.android.ndkports.CMakeCompatibleVersion
import java.io.File
import java.net.URL
import java.security.MessageDigest

// Get project-specific configuration
val configs = rootProject.extra["projectConfigs"] as Map<String, Map<String, String>>
val projectConfig = configs[project.name] ?: error("No configuration found for project ${project.name}")

val libVersion = projectConfig["libVersion"]!!
val snapshotVersion = projectConfig["snapshotVersion"] ?: ""
val downloadUrl = "https://download.libsodium.org/libsodium/releases/libsodium-$libVersion.tar.gz"

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
    minSdkVersion.set(21) // Match libsodium's minimum SDK version
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
        // Create necessary directories first
        run {
            args("mkdir", "-p",
                buildDirectory.resolve("minimal").absolutePath,
                buildDirectory.resolve("full").absolutePath,
                installDirectory.resolve("minimal").absolutePath,
                installDirectory.resolve("full").absolutePath,
                installDirectory.resolve("lib").absolutePath,
                installDirectory.resolve("include").absolutePath
            )
        }

        // Set architecture-specific flags
        val archConfig = when (toolchain.abi.abiName) {
            "armeabi-v7a" -> Pair(
                "armv7-a",
                "-Os -mfloat-abi=softfp -mfpu=vfpv3-d16 -mthumb -marm -march=armv7-a"
            )
            "arm64-v8a" -> Pair(
                "armv8-a+crypto",
                "-Os -march=armv8-a+crypto"
            )
            "x86" -> Pair(
                "i686",
                "-Os -march=i686"
            )
            "x86_64" -> Pair(
                "westmere",
                "-Os -march=westmere"
            )
            else -> error("Unsupported ABI: ${toolchain.abi.abiName}")
        }

        val targetArch = archConfig.first
        val cflags = archConfig.second

        // Build minimal version first
        run {
            val minimalInstallDir = installDirectory.resolve("minimal")

            args(
                sourceDirectory.resolve("configure").absolutePath,
                "--host=${toolchain.binutilsTriple}",
                "--prefix=${minimalInstallDir.absolutePath}",
                "--disable-soname-versions",
                "--disable-pie",
                "--enable-minimal",
                "--with-sysroot=${toolchain.ndk.sysrootDirectory}"
            )

            env("CC", toolchain.clang.absolutePath)
            env("AR", toolchain.ar.absolutePath)
            env("RANLIB", toolchain.ranlib.absolutePath)
            env("STRIP", toolchain.strip.absolutePath)
            env("PATH", "${toolchain.binDir}:${System.getenv("PATH")}")
            env("TARGET_ARCH", targetArch)
            env("CFLAGS", cflags)
            env("LDFLAGS", "-Wl,-z,max-page-size=16384")

            // Set correct NDK platform version for 64-bit architectures
            if (toolchain.abi.abiName == "arm64-v8a" || toolchain.abi.abiName == "x86_64") {
                env("NDK_PLATFORM", "android-21")
            } else {
                env("NDK_PLATFORM", "android-19")
            }
        }

        run { args("make", "clean") }
        run { args("make", "-j$ncpus") }
        run { args("make", "install") }

        // Build full version
        run {
            val fullInstallDir = installDirectory.resolve("full")

            args(
                sourceDirectory.resolve("configure").absolutePath,
                "--host=${toolchain.binutilsTriple}",
                "--prefix=${fullInstallDir.absolutePath}",
                "--disable-soname-versions",
                "--disable-pie",
                "--with-sysroot=${toolchain.ndk.sysrootDirectory}"
            )

            env("CC", toolchain.clang.absolutePath)
            env("AR", toolchain.ar.absolutePath)
            env("RANLIB", toolchain.ranlib.absolutePath)
            env("STRIP", toolchain.strip.absolutePath)
            env("PATH", "${toolchain.binDir}:${System.getenv("PATH")}")
            env("TARGET_ARCH", targetArch)
            env("CFLAGS", cflags)
            env("LDFLAGS", "-Wl,-z,max-page-size=16384")
            env("LIBSODIUM_FULL_BUILD", "1")

            // Set correct NDK platform version for 64-bit architectures
            if (toolchain.abi.abiName == "arm64-v8a" || toolchain.abi.abiName == "x86_64") {
                env("NDK_PLATFORM", "android-21")
            } else {
                env("NDK_PLATFORM", "android-19")
            }
        }

        run { args("make", "clean") }
        run { args("make", "-j$ncpus") }
        run { args("make", "install") }

        // Copy and rename libraries - file operations remain the same
        run {
            args("bash", "-c", """
                # First create the necessary directories
                mkdir -p "${installDirectory.resolve("lib")}"

                # Copy minimal libraries
                cp "${installDirectory.resolve("minimal/lib/libsodium.so")}" "${installDirectory.resolve("lib/libsodium-minimal.so")}"
                cp "${installDirectory.resolve("minimal/lib/libsodium.a")}" "${installDirectory.resolve("lib/libsodium-minimal-static.a")}"

                # Copy minimal .la file if it exists
                if [ -f "${installDirectory.resolve("minimal/lib/libsodium.la")}" ]; then
                    cp "${installDirectory.resolve("minimal/lib/libsodium.la")}" "${installDirectory.resolve("lib/")}"
                fi

                # Copy full libraries
                cp "${installDirectory.resolve("full/lib/libsodium.so")}" "${installDirectory.resolve("lib/")}"
                cp "${installDirectory.resolve("full/lib/libsodium.a")}" "${installDirectory.resolve("lib/libsodium-static.a")}"

                # Copy full .la file if it exists
                if [ -f "${installDirectory.resolve("full/lib/libsodium.la")}" ]; then
                    cp "${installDirectory.resolve("full/lib/libsodium.la")}" "${installDirectory.resolve("lib/")}"
                fi

                # Create includes directory and copy headers
                mkdir -p "${installDirectory.resolve("include")}"
                cp -R "${installDirectory.resolve("full/include/.")}" "${installDirectory.resolve("include/")}"
            """.trimIndent())
        }
    }
}



tasks.prefabPackage {
    version.set(CMakeCompatibleVersion.parse(libVersion))

    modules {
        // Full version modules
        create("sodium") {
            static.set(false)
        }
        create("sodium-static") {
            static.set(true)
        }

        // Minimal version modules
        create("sodium-minimal") {
            static.set(false)
        }
        create("sodium-minimal-static") {
            static.set(true)
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["prefab"])
            pom {
                name.set("libsodium")
                description.set("The ndkports AAR for libsodium.")
                val repoUrl = "https://github.com/${System.getenv("GITHUB_REPOSITORY") ?: "user/repo"}"
                url.set(repoUrl)
                licenses {
                    license {
                        name.set("ISC License")
                        url.set("https://github.com/jedisct1/libsodium/blob/master/LICENSE")
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

//Alternative build script but seems to not building for some other android architectures

// import com.android.ndkports.AdHocPortTask
// import com.android.ndkports.CMakeCompatibleVersion
// import java.io.File
// import java.net.URL

// // Get project-specific configuration
// val configs = rootProject.extra["projectConfigs"] as Map<String, Map<String, String>>
// val projectConfig = configs[project.name] ?: error("No configuration found for project ${project.name}")

// val libVersion = projectConfig["libVersion"]!!
// val downloadUrl = "https://download.libsodium.org/libsodium/releases/libsodium-$libVersion.tar.gz"

// group = "io.github.ronickg"
// version = libVersion

// plugins {
//     id("maven-publish")
//     id("com.android.ndkports.NdkPorts")
//     id("signing")
//     distribution
// }

// ndkPorts {
//     ndkPath.set(File(project.findProperty("ndkPath") as String))
//     source.set(project.file("src.tar.gz"))
//     minSdkVersion.set(19)
// }

// // Task to download source
// tasks.register<DefaultTask>("downloadSource") {
//     val outputFile = project.file("src.tar.gz")
//     outputs.file(outputFile)

//     doLast {
//         URL(downloadUrl).openStream().use { input ->
//             outputFile.outputStream().use { output ->
//                 input.copyTo(output)
//             }
//         }
//         ndkPorts.source.set(outputFile)
//     }
// }

// tasks.extractSrc {
//     dependsOn("downloadSource")
// }

// val buildTask = tasks.register<DefaultTask>("buildPort") {
//     dependsOn("extractSrc")
//     val sodiumVersion = libVersion
//     val script = project.projectDir.resolve("android-aar.sh").absolutePath

//     // Enable logging for this task
//     logging.captureStandardOutput(LogLevel.LIFECYCLE)
//     logging.captureStandardError(LogLevel.LIFECYCLE)

//     doLast {
//         val destPath = project.buildDir.resolve("port/aar")
//         val distPath = project.buildDir.resolve("distributions")
//         destPath.mkdirs()
//         distPath.mkdirs()

//         println("AAR Destination Path: ${destPath.absolutePath}")
//         println("Script: ${script}")
//         println("Sodium Version: ${sodiumVersion}")
//         println("Running script ${script} with args ${sodiumVersion} ${destPath.absolutePath}")

//         project.exec {
//             executable = "bash"
//             args(
//                 script,
//                 sodiumVersion,
//                 destPath.absolutePath,
//                 distPath.absolutePath
//             )
//             environment(
//                 "ANDROID_NDK_HOME" to project.findProperty("ndkPath"),
//                 "PATH" to System.getenv("PATH")
//             )
//         }
//     }
// }

// // Handle publishing and signing
// publishing {
//     publications {
//         create<MavenPublication>("maven") {
//             artifact(project.file("${project.buildDir}/distributions/libsodium-${version}.aar"))
//             pom {
//                 name.set("libsodium")
//                 description.set("The ndkports AAR for libsodium.")
//                 val repoUrl = "https://github.com/${System.getenv("GITHUB_REPOSITORY") ?: "user/repo"}"
//                 url.set(repoUrl)
//                 licenses {
//                     license {
//                         name.set("ISC License")
//                         url.set("https://github.com/jedisct1/libsodium/blob/master/LICENSE")
//                         distribution.set("repo")
//                     }
//                 }
//                 developers {
//                     developer {
//                         name.set("Ronald")
//                     }
//                 }
//                 scm {
//                     url.set(repoUrl)
//                     connection.set("scm:git:${repoUrl}.git")
//                 }
//             }
//         }
//     }

//     repositories {
//         maven {
//             url = uri("${project.buildDir}/repository")
//         }
//     }
// }

// // Configure signing
// signing {
//     useGpgCmd()
//     sign(publishing.publications["maven"])
// }

// distributions {
//     main {
//         contents {
//             from("${project.buildDir}/repository")
//             into("/")
//             include("**/*.aar")
//             include("**/*.pom")
//             include("**/*.module")
//             include("**/*.aar.asc")
//             include("**/*.pom.asc")
//             include("**/*.module.asc")
//             include("**/*.aar.md5")
//             include("**/*.pom.md5")
//             include("**/*.module.md5")
//             include("**/*.aar.sha1")
//             include("**/*.pom.sha1")
//             include("**/*.module.sha1")
//             include("**/*.aar.sha256")
//             include("**/*.pom.sha256")
//             include("**/*.module.sha256")
//             include("**/*.aar.sha512")
//             include("**/*.pom.sha512")
//             include("**/*.module.sha512")
//             include("**/maven-metadata.xml")
//             include("**/maven-metadata.xml.asc")
//             include("**/maven-metadata.xml.sha256")
//             include("**/maven-metadata.xml.sha512")
//         }
//     }
// }

// tasks {
//     distZip {
//         dependsOn("publish")
//         destinationDirectory.set(File(rootProject.buildDir, "distributions"))
//     }

//     named("publish") {
//         dependsOn(buildTask)
//     }
// }