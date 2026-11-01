package com.riyadm.unsendvault.ui.settings

import android.content.Context
import androidx.compose.runtime.Immutable
import com.riyadm.unsendvault.apps.AppDirectory
import com.riyadm.unsendvault.apps.ProfileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
data class InstalledApp(
    val packageName: String,
    val label: String,
    /** [ProfileKind.PERSONAL] when the app is in the current profile; otherwise the profile it was found in. */
    val profile: ProfileKind = ProfileKind.PERSONAL,
    /** Other profiles with the same app. */
    val alsoIn: Set<ProfileKind> = emptySet(),
)

/** Lists launchable apps of every profile (personal, work, cloned, …) for the exclusion picker. */
fun interface InstalledAppsSource {
    suspend fun launcherApps(): List<InstalledApp>

    /** Label of one package that may not be launchable (e.g. a "New" app), from any profile. Null if unknown. */
    suspend fun labelFor(packageName: String): String? = null
}

/** [AppDirectory] on the IO dispatcher (launcher-intent `<queries>`, no QUERY_ALL_PACKAGES, no extra permission). */
class PackageManagerAppsSource(context: Context, private val directory: AppDirectory = AppDirectory(context)) : InstalledAppsSource {
    override suspend fun launcherApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        directory.launchableApps().map { InstalledApp(it.packageName, it.label, it.profile, it.alsoIn) }
    }

    override suspend fun labelFor(packageName: String): String? = withContext(Dispatchers.IO) { directory.label(packageName) }
}
