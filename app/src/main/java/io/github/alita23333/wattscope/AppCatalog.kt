package io.github.alita23333.wattscope

import android.content.Context
import android.content.Intent
import java.io.File

/** Resolve application identities once per collector session, outside the sampling loop. */
class AppCatalog(private val context: Context) {
    private var labels: Map<String, String> = emptyMap()
    private var excluded: Set<String> = setOf(context.packageName, "com.android.systemui")

    fun prepare(directory: File): File {
        val pm = context.packageManager
        labels =
            pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    0,
                )
                .associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        val homes =
            pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    0,
                )
                .map { it.activityInfo.packageName }
        excluded = homes.toSet() + setOf(context.packageName, "com.android.systemui")
        return File(directory, "foreground-apps.txt").apply {
            writeText(
                (labels.keys + homes + context.packageName)
                    .filter { it.matches(Regex("[A-Za-z0-9_.]+")) }
                    .sorted()
                    .joinToString("\n")
            )
        }
    }

    fun label(pkg: String) = labels[pkg] ?: pkg

    fun recordable(pkg: String) = pkg.isNotBlank() && pkg in labels && pkg !in excluded
}
