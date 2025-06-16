group = "com.android"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
}

// Define project configurations
extra["projectConfigs"] = mapOf(
    "openssl" to mapOf(
        "libVersion" to "3.3.3",
        "libName" to "OpenSSL"
    ),
    "jsoncpp" to mapOf(
        "libVersion" to "1.9.5",
        "libName" to "JsonCpp"
    ),
    "zlib" to mapOf(
        "libVersion" to "1.3.1",
        "libName" to "zlib"
    ),
    "libpng" to mapOf(
        "libVersion" to "1.6.44",
        "libName" to "libpng"
    ),
    "curl" to mapOf(
        "libVersion" to "8.10.1",
        "libName" to "Curl"
    ),
    "utf8proc" to mapOf(
        "libVersion" to "2.9.0",
        "libName" to "utf8proc"
    ),
    "gmp" to mapOf(
        "libVersion" to "6.3.0",
        "libName" to "GMP"
    ),
    "blst" to mapOf(
        "libVersion" to "0.3.10",
        "libName" to "blst"
    ),
    "sodium" to mapOf(
        "libVersion" to "1.0.20",
        "libName" to "Sodium"
    )
)

tasks.register("exportProjectInfo") {
    // Create an output file property
    val outputFile = project.objects.property(File::class)

    doFirst {
        // Get the output file path from the provided property or use a default
        outputFile.set(project.layout.buildDirectory.file("ndkports-matrix.json").get().asFile)
        // Ensure the parent directory exists
        outputFile.get().parentFile.mkdirs()
    }

    doLast {
        val projects = subprojects.filter { proj ->
            proj.plugins.hasPlugin("com.android.ndkports.NdkPorts")
        }.map { proj ->
            // Get project-specific configuration
            val configs = rootProject.extra["projectConfigs"] as Map<String, Map<String, String>>
            val projectConfig = configs[proj.name] ?: error("No configuration found for project ${proj.name}")

            mapOf(
                "name" to proj.name,
                "version" to projectConfig["libVersion"],
                "libName" to projectConfig["libName"]
            )
        }

        val matrix = mapOf("include" to projects)
        val json = groovy.json.JsonBuilder(matrix).toPrettyString()

        // Write to the output file
        outputFile.get().writeText(json)

        // Print the file path for debugging
        println("Matrix JSON written to: ${outputFile.get().absolutePath}")
    }
}

tasks.register("release") {
    dependsOn(project.getTasksByName("test", true))
    dependsOn(project.getTasksByName("distZip", true))
}
