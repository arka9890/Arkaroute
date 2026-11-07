package com.omniroute.app

import android.content.Context
import java.io.File

/**
 * App cache accounting for the Service screen's `Hapus Cache` action and
 * reused by Settings (Task 8).
 *
 * Walks [Context.cacheDir] recursively — the WebView cache lives there on
 * modern Android (e.g. `cacheDir/webview`). Deliberately kept free of
 * `NativeGateway` references so JVM tests never trigger `loadLibrary`.
 */
object AppCache {
    /** Total size in bytes of every file under the app cache dirs. */
    fun size(context: Context): Long =
        cacheDirs(context).sumOf { dir ->
            if (dir.exists()) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        }

    /** Deletes every cached file under the cache dirs and returns bytes freed. */
    fun clear(context: Context): Long {
        val freed = size(context)
        cacheDirs(context).forEach { dir ->
            if (!dir.exists()) return@forEach
            dir.walkTopDown()
                .sortedByDescending { it.path.length }
                .filter { it.isFile }
                .forEach { it.delete() }
            // Prune now-empty subdirectories, keeping the cache root itself.
            dir.walkTopDown()
                .filter { it.isDirectory && it != dir }
                .sortedByDescending { it.path.length }
                .forEach { it.delete() }
        }
        return freed
    }

    private fun cacheDirs(context: Context): List<File> = listOf(context.cacheDir)
}
