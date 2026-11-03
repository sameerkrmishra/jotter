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
    jvm {
        // The search benchmark (src/jvmBenchmark), run by the searchBenchmark task below. It sees
        // the module's internals, as tests do.
        val main = compilations.getByName("main")
        compilations.create("benchmark") {
            associateWith(main)
        }
    }

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

// PLAN.md §10: search over 10,000 synthetic notes in under 50 ms. Not part of `check`, because
// timings on shared CI machines are noisy. Results are in README.md.
// `check` compiles and analyses the benchmark, without running it, so it cannot rot.
tasks.named("check") {
    dependsOn("compileBenchmarkKotlinJvm", "detektBenchmarkJvm")
}

tasks.register<JavaExec>("searchBenchmark") {
    group = "verification"
    description = "Measures search over 10,000 synthetic notes on the JVM."
    val benchmark = kotlin.jvm().compilations.getByName("benchmark")
    classpath = files(benchmark.output.allOutputs, benchmark.runtimeDependencyFiles)
    mainClass = "io.github.sameerkrmishra.jotter.core.storage.SearchBenchmarkKt"
}

kover {
    currentProject {
        sources {
            // The benchmark is a tool, not product code: it has no tests and is not counted.
            excludedSourceSets.add("benchmark")
        }
    }
}
