plugins {
    id("jotter.kmp-library")
    id("jotter.quality")
    id("jotter.android-device-test")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.backend.api)
            implementation(projects.core.logging)
            implementation(libs.ktor.client.core)
            // CIO is the one Ktor engine for the JVM, Android and linuxX64 alike, without a native
            // library such as libcurl to link.
            implementation(libs.ktor.client.cio)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(projects.backend.api.contract)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.network)
        }
    }
}

// The tests against real servers, in the package `live`, need servers that scripts/live-test.sh
// starts in containers. They run only when it sets JOTTER_WEBDAV_LIVE, and then alone. The URLs
// are task inputs, so a result is never taken from the cache for other servers.
val liveVariables =
    listOf(
        "JOTTER_WEBDAV_LIVE",
        "JOTTER_WEBDAV_LIGHTTPD_URL",
        "JOTTER_WEBDAV_APACHE_URL",
        "JOTTER_WEBDAV_RCLONE_URL",
        // Tests that are known to fail on a server, as "Class.method,Class.method". See the README.
        "JOTTER_WEBDAV_LIVE_EXCLUDE",
    )
val live = providers.environmentVariable("JOTTER_WEBDAV_LIVE").orNull == "1"
val liveExcluded =
    providers
        .environmentVariable("JOTTER_WEBDAV_LIVE_EXCLUDE")
        .orElse("")
        .get()
        .split(',')
        .filter { it.isNotBlank() }

// A device has no route to the containers, so the live tests never run there. The filter below
// does not reach the device test task, which is not an AbstractTestTask.
deviceTest {
    runnerArguments["notPackage"] = "io.github.sameerkrmishra.jotter.backend.webdav.live"
}

tasks.withType<AbstractTestTask>().configureEach {
    liveVariables.forEach { name ->
        inputs.property(name, providers.environmentVariable(name).orElse(""))
    }
    filter.run {
        isFailOnNoMatchingTests = false
        if (live) {
            includeTestsMatching("*.webdav.live.*")
            liveExcluded.forEach { excludeTestsMatching("*.$it") }
        } else {
            excludeTestsMatching("*.webdav.live.*")
        }
    }
}
