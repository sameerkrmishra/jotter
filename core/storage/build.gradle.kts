plugins {
    id("jotter.kmp-library")
    id("jotter.quality")
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("JotterDatabase") {
            // Generated code is excluded from the coverage gate by this package (jotter.root).
            packageName = "io.github.sameerkrmishra.jotter.core.storage.db"
            // core:sqlite's driver is synchronous (ADR-0002).
            generateAsync = false
            dialect(libs.sqldelight.dialect.sqlite344)
            // `check` verifies that the migrations (.sqm, none yet) turn every saved schema
            // (databases/N.db, written by generateCommonMainJotterDatabaseSchema) into the one the
            // .sq files create. See README.md.
            verifyMigrations = true
            schemaOutputDirectory = file("src/commonMain/sqldelight/databases")
        }
    }
    // The plugin links Native binaries against the system's libsqlite3 by default. core:sqlite
    // brings its own, statically linked SQLite build.
    linkSqlite = false
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            // Flow appears in NoteService's API.
            api(libs.kotlinx.coroutines.core)
            implementation(projects.core.sqlite)
            implementation(libs.sqldelight.coroutines.extensions)
        }
        commonTest.dependencies {
            implementation(libs.kotest.property)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio)
        }
    }
}

// sqlite-bundled's Android artifact ships SQLite only for device ABIs, so on the host JVM the
// Android variant fails with "UnsatisfiedLinkError: no sqliteJni". The same tests run on the JVM
// target (with SQLite for the host) and on linuxX64.
tasks.named { it == "testAndroidHostTest" }.configureEach {
    enabled = false
}
