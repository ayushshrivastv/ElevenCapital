import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.temporal.ChronoUnit

plugins {
    kotlin("jvm") version "2.1.20" apply false
    kotlin("android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("com.android.application") version "8.9.2" apply false
    id("com.android.library") version "8.9.2" apply false
}

// Remove only reproducible diagnostics/old direct-check copies. Current Gradle/Kotlin/DEX
// caches and archived APKs stay intact so a routine update remains incremental.
val pruneBuildArtifacts by tasks.registering {
    group = "build"
    description = "Prune stale generated reports and copied check caches without invalidating compilation."
    doLast {
        val workspace = rootDir.toPath().toRealPath()
        val dryRun = providers.gradleProperty("pruneBuildArtifactsDryRun").orNull == "true"
        val pruneNow = providers.gradleProperty("pruneBuildArtifactsNow").orNull == "true"
        val cutoff = if (pruneNow) Instant.now() else Instant.now().minus(7, ChronoUnit.DAYS)
        var removedBytes = 0L
        var removedFiles = 0

        fun safePath(relative: String): Path? {
            val target = workspace.resolve(relative).normalize()
            check(target.startsWith(workspace) && target != workspace)
            var current = workspace
            for (part in workspace.relativize(target)) {
                current = current.resolve(part)
                if (Files.isSymbolicLink(current)) return null
            }
            return target.takeIf { Files.exists(it) }
        }

        fun removeFile(file: Path) {
            // Never follow links, including links inside an allowed generated directory.
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
            val bytes = Files.size(file)
            if (!dryRun) Files.delete(file)
            removedBytes += bytes
            removedFiles++
        }

        fun pruneOldTree(relative: String) {
            val target = safePath(relative) ?: return
            val before = removedBytes
            Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (attributes.isRegularFile && attributes.lastModifiedTime().toInstant().isBefore(cutoff)) {
                        removeFile(file)
                    }
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(directory: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    if (!dryRun && Files.list(directory).use { !it.findAny().isPresent }) Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            })
            if (removedBytes > before) logger.lifecycle("${if (dryRun) "Would prune" else "Pruned"}: $relative (${removedBytes - before} bytes)")
        }

        fun retainLatest(relative: String, suffix: String, keep: Int) {
            val target = safePath(relative) ?: return
            val files = Files.list(target).use { stream ->
                stream.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                    it.fileName.toString().endsWith(suffix) }.toList()
            }.sortedByDescending { Files.getLastModifiedTime(it).toMillis() }
            files.drop(keep).filter { Files.getLastModifiedTime(it).toInstant().isBefore(cutoff) }
                .forEach(::removeFile)
        }

        retainLatest("build/reports/kotlin-build", ".txt", 3)
        retainLatest(".kotlin/errors", ".log", 1)
        retainLatest(".gradle/kotlin/errors", ".log", 1)
        listOf("app", "core", "market-data").forEach { module ->
            listOf("reports", "test-results", "outputs/logs").forEach { generated ->
                pruneOldTree("$module/build/$generated")
            }
        }
        pruneOldTree("build/reports/problems")
        // Historical check inputs, source, screenshots, and result summaries are preserved.
        listOf("verification/activity-ui-typecheck", "verification/activity-app-typecheck",
            "verification/wallet-balance/direct-check").forEach { check ->
            // Direct checks record their inputs before compiling. Do not prune a check that
            // was started recently, even when an explicit one-time cleanup was requested.
            val inputs = safePath("$check/inputs.json")
            val recentlyUsed = inputs != null && Files.getLastModifiedTime(inputs).toInstant()
                .isAfter(Instant.now().minus(1, ChronoUnit.DAYS))
            if (!recentlyUsed) listOf("aar", "classes", "test-classes", "tmp")
                .forEach { cache -> pruneOldTree("$check/$cache") }
        }
        listOf("verification/activity-data/core-classes", "verification/activity-data/parser-classes")
            .forEach(::pruneOldTree)
        val megabytes = "%.1f".format(java.util.Locale.ROOT, removedBytes / 1_048_576.0)
        logger.lifecycle("Build housekeeping ${if (dryRun) "would remove" else "removed"} $removedFiles generated files ($megabytes MiB).")
    }
}
