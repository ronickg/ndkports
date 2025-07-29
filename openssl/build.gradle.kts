import com.android.ndkports.AdHocPortTask
import com.android.ndkports.AndroidExecutableTestTask
import com.android.ndkports.CMakeCompatibleVersion
import java.io.File
import java.net.URL
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.TaskAction

// Get project-specific configuration
val configs = rootProject.extra["projectConfigs"] as Map<String, Map<String, String>>
val projectConfig = configs[project.name] ?: error("No configuration found for project ${project.name}")

val libVersion = projectConfig["libVersion"]!!
val snapshotVersion = projectConfig["snapshotVersion"] ?: ""

val opensslDownloadUrl = "https://github.com/openssl/openssl/releases/download/openssl-${libVersion}/openssl-${libVersion}.tar.gz"
val opensslSha256Url = "${opensslDownloadUrl}.sha256"
val opensslAscUrl = "${opensslDownloadUrl}.asc"

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

// Task to download OpenSSL source
tasks.register<DefaultTask>("downloadOpenSSL") {
    val outputFile = project.file("src.tar.gz")
    val sha256File = project.file("openssl.sha256")
    val ascFile = project.file("openssl.asc")

    outputs.file(outputFile)
    outputs.file(sha256File)
    outputs.file(ascFile)

    doLast {
        // Download source
        URL(opensslDownloadUrl).openStream().use { input ->
            outputFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // Download SHA256
        URL(opensslSha256Url).openStream().use { input ->
            sha256File.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // Download ASC signature
        URL(opensslAscUrl).openStream().use { input ->
            ascFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // Set the source property after download
        ndkPorts.source.set(outputFile)
    }
}

// Task to verify OpenSSL integrity
tasks.register<DefaultTask>("verifyOpenSSL") {
    dependsOn("downloadOpenSSL")

    doLast {
        val sourceFile = project.file("src.tar.gz")
        val sha256File = project.file("openssl.sha256")
        val ascFile = project.file("openssl.asc")

        // Read expected SHA256
        val expectedHash = sha256File.readText().trim().split(" ")[0]

        // Calculate actual SHA256
        val digest = MessageDigest.getInstance("SHA-256")
        val actualHash = sourceFile.inputStream().use { input ->
            digest.digest(input.readBytes())
        }.joinToString("") { "%02x".format(it) }

        // Verify hash
        if (expectedHash != actualHash) {
            throw GradleException("SHA256 verification failed! Expected: $expectedHash, Got: $actualHash")
        } else {
            println("SHA256 verification succeeded.")
        }

        // Import OpenSSL PGP key first
        exec {
            commandLine(
                "gpg",
                "--keyserver",
                "keyserver.ubuntu.com",
                "--recv-keys",
                "BA5473A2B0587B07FB27CF2D216094DFD0CB81EF"  // OpenSSL release signing key
            )
        }

        // Verify the signature
        exec {
            commandLine("gpg", "--verify", ascFile.absolutePath, sourceFile.absolutePath)
        }

        println("GPG signature verification succeeded.")
    }
}

tasks.named("extractSrc") {
    dependsOn("verifyOpenSSL")
}


val buildTask = tasks.register<AdHocPortTask>("buildPort") {
    dependsOn("extractSrc")

    builder {
        run {
            args(
                sourceDirectory.resolve("Configure").absolutePath,
                "android-${toolchain.abi.archName}",
                "-D__ANDROID_API__=${toolchain.api}",
                "--prefix=${installDirectory.absolutePath}",
                "--openssldir=${installDirectory.absolutePath}",
                "no-sctp",
                "shared",
                "-Wl,-z,max-page-size=16384"
            )

            env("ANDROID_NDK", toolchain.ndk.path.absolutePath)
            env("PATH", "${toolchain.binDir}:${System.getenv("PATH")}")
        }

        run {
            args("make", "-j$ncpus", "SHLIB_EXT=.so")

            env("ANDROID_NDK", toolchain.ndk.path.absolutePath)
            env("PATH", "${toolchain.binDir}:${System.getenv("PATH")}")
        }

        run {
            args("make", "install_sw", "SHLIB_EXT=.so")

            env("ANDROID_NDK", toolchain.ndk.path.absolutePath)
            env("PATH", "${toolchain.binDir}:${System.getenv("PATH")}")
        }
    }
}

tasks.prefabPackage {
    version.set(CMakeCompatibleVersion.parse(libVersion))

    licensePath.set("LICENSE.txt")

    modules {
        create("crypto")
        create("ssl")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["prefab"])
            pom {
                name.set("OpenSSL")
                description.set("The ndkports AAR for OpenSSL.")
                val repoUrl = "https://github.com/${System.getenv("GITHUB_REPOSITORY") ?: "user/repo"}"
                url.set(repoUrl)
                licenses {
                    license {
                        name.set("Dual OpenSSL and SSLeay License")
                        url.set("https://www.openssl.org/source/license-openssl-ssleay.txt")
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