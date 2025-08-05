rootProject.name = "ndkports"

pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "kotlinx-serialization") {
                useModule("org.jetbrains.kotlin:kotlin-serialization:${requested.version}")
            }
        }
    }
}

include("curl")
// include("openssl")
// include("utf8proc")
// include("gmp")
// include("jsoncpp")
// include("zlib")
// include("libpng")
// include("blst")
// include("sodium")
