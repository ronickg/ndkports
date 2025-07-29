import com.android.ndkports.AndroidExecutableTestTask
import com.android.ndkports.CMakeCompatibleVersion
import com.android.ndkports.MesonPortTask
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import java.io.File
import java.net.URL
import java.security.MessageDigest

// Get project-specific configuration
val configs = rootProject.extra["projectConfigs"] as Map<String, Map<String, String>>
val projectConfig = configs[project.name] ?: error("No configuration found for project ${project.name}")

val libVersion = projectConfig["libVersion"]!!
val snapshotVersion = projectConfig["snapshotVersion"] ?: ""

val jsoncppDownloadUrl = "https://github.com/open-source-parsers/jsoncpp/archive/refs/tags/${libVersion}.tar.gz"

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

// Task to download JsonCpp source
tasks.register<DefaultTask>("downloadJsonCpp") {
    val outputFile = project.file("src.tar.gz")
    outputs.file(outputFile)

    doLast {
        // Download source
        URL(jsoncppDownloadUrl).openStream().use { input ->
            outputFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // Set the source property after download
        ndkPorts.source.set(outputFile)
    }
}

tasks.extractSrc {
    dependsOn("downloadJsonCpp")

    doLast {
        // jsoncpp has a "version" file on the include path that conflicts with
        // https://en.cppreference.com/w/cpp/header/version. Remove it so we can
        // build.
        outDir.get().asFile.resolve("version").delete()
    }
}

val buildTask = tasks.register<MesonPortTask>("buildPort")

tasks.prefabPackage {
    version.set(CMakeCompatibleVersion.parse(libVersion))

    modules {
        create("jsoncpp")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["prefab"])
            pom {
                name.set("JsonCpp")
                description.set("The ndkports AAR for JsonCpp.")
                val repoUrl = "https://github.com/${System.getenv("GITHUB_REPOSITORY") ?: "user/repo"}"
                url.set(repoUrl)
                licenses {
                    license {
                        name.set("The JsonCpp License")
                        url.set("https://github.com/open-source-parsers/jsoncpp/blob/master/LICENSE")
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