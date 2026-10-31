package com.riyadm.unsendvault.apps

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle

/** Which kind of profile an app lives in. Exclusions and the archive key apps by package name, across profiles. */
enum class ProfileKind { PERSONAL, WORK, CLONE, PRIVATE, OTHER }

/** One launchable app, merged across the user's profiles. */
data class LaunchableApp(
    val packageName: String,
    val label: String,
    /** Profile shown for the entry: [ProfileKind.PERSONAL] when the app exists in the current profile. */
    val profile: ProfileKind,
    /** Other profiles that also have this app (the same package name; exclusion applies to all of them). */
    val alsoIn: Set<ProfileKind> = emptySet(),
)

/**
 * Looks up apps in the current profile **and** the user's other profiles (work profile, cloned apps / Dual Messenger,
 * Secure Folder and similar). [PackageManager] only sees the current profile, but notifications from the other profiles
 * reach the listener too, so labels, icons and the app pickers would otherwise show bare package names or miss the app.
 * Other profiles are read through [LauncherApps], which needs no permission (package visibility still applies; the
 * manifest's launcher-intent query covers launchable apps). Every lookup is best effort and never throws.
 */
class AppDirectory(context: Context) {
    private val app = context.applicationContext
    private val pm: PackageManager = app.packageManager
    private val launcher: LauncherApps? = app.getSystemService(LauncherApps::class.java)
    private val me: UserHandle = Process.myUserHandle()

    /** Launchable apps of all profiles, one entry per package, current profile first. Excludes UnsendVault. Blocking. */
    fun launchableApps(): List<LaunchableApp> {
        val others = otherProfiles().map { profile ->
            kindOf(profile) to activitiesIn(null, profile).mapNotNull { a ->
                val pkg = a.applicationInfo?.packageName ?: return@mapNotNull null
                pkg to (a.label?.toString()?.takeIf { it.isNotBlank() } ?: pkg)
            }
        }
        return mergeProfiles(currentProfileActivities(), others, ownPackage = app.packageName)
    }

    /** User-visible label: current profile first, then any other profile. Null when the app is in none of them. */
    fun label(packageName: String): String? {
        try {
            return pm.getApplicationLabel(applicationInfo(packageName)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
        } catch (_: RuntimeException) {
        }
        return firstInOtherProfile(packageName)?.second?.label?.toString()?.takeIf { it.isNotBlank() }
    }

    /** Icon from the current profile, else the other profile's icon with its badge (e.g. the work briefcase). */
    fun icon(packageName: String): Drawable? {
        try {
            return pm.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
        } catch (_: RuntimeException) {
        }
        val activity = firstInOtherProfile(packageName)?.second ?: return null
        return try {
            activity.getBadgedIcon(0)
        } catch (_: RuntimeException) {
            null
        }
    }

    /** True when [launch] can open the app in some profile. */
    fun canLaunch(packageName: String): Boolean =
        launchIntent(packageName) != null || firstInOtherProfile(packageName) != null

    /** Opens the app: the current profile's copy if there is one, else the copy in another profile. */
    fun launch(packageName: String): Boolean {
        launchIntent(packageName)?.let { intent ->
            return try {
                app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: RuntimeException) {
                false
            }
        }
        val (user, activity) = firstInOtherProfile(packageName) ?: return false
        return try {
            launcher?.startMainActivity(activity.componentName, user, null, null)
            launcher != null
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun launchIntent(packageName: String): Intent? = try {
        pm.getLaunchIntentForPackage(packageName)
    } catch (_: RuntimeException) {
        null
    }

    private fun applicationInfo(packageName: String) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getApplicationInfo(packageName, 0)
    }

    private fun currentProfileActivities(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        } catch (_: RuntimeException) {
            emptyList()
        }
        return activities.map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
    }

    private fun otherProfiles(): List<UserHandle> = try {
        launcher?.profiles.orEmpty().filter { it != me }
    } catch (_: RuntimeException) {
        emptyList()
    }

    private fun activitiesIn(packageName: String?, user: UserHandle): List<LauncherActivityInfo> = try {
        launcher?.getActivityList(packageName, user).orEmpty()
    } catch (_: RuntimeException) {
        emptyList() // profile being removed, locked or otherwise unavailable
    }

    private fun firstInOtherProfile(packageName: String): Pair<UserHandle, LauncherActivityInfo>? {
        for (user in otherProfiles()) {
            activitiesIn(packageName, user).firstOrNull()?.let { return user to it }
        }
        return null
    }

    /**
     * Android 15+ reports the profile type. Older versions don't expose it to apps, so their profiles are shown as
     * [ProfileKind.OTHER] rather than guessing "work".
     */
    private fun kindOf(user: UserHandle): ProfileKind {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return ProfileKind.OTHER
        val type = try {
            launcher?.getLauncherUserInfo(user)?.userType
        } catch (_: RuntimeException) {
            null
        }
        return profileKindOf(type)
    }

    companion object {
        /**
         * One entry per package: apps of the current profile keep their label and are marked with the other profiles
         * that also have them; apps found only in other profiles are listed under the first such profile. Sorted by
         * label; [ownPackage] is left out.
         */
        fun mergeProfiles(
            current: List<Pair<String, String>>,
            others: List<Pair<ProfileKind, List<Pair<String, String>>>>,
            ownPackage: String,
        ): List<LaunchableApp> {
            val found = LinkedHashMap<String, LaunchableApp>()
            current.forEach { (pkg, label) -> if (pkg != ownPackage) found.putIfAbsent(pkg, LaunchableApp(pkg, label, ProfileKind.PERSONAL)) }
            for ((kind, apps) in others) {
                for ((pkg, label) in apps) {
                    if (pkg == ownPackage) continue
                    val existing = found[pkg]
                    found[pkg] = when {
                        existing == null -> LaunchableApp(pkg, label, kind)
                        existing.profile == kind -> existing
                        else -> existing.copy(alsoIn = existing.alsoIn + kind)
                    }
                }
            }
            return found.values.sortedBy { it.label.lowercase() }
        }

        /** Maps a `UserManager` user-type string (Android 15+) to a [ProfileKind]. */
        fun profileKindOf(userType: String?): ProfileKind = when (userType) {
            "android.os.usertype.profile.MANAGED" -> ProfileKind.WORK
            "android.os.usertype.profile.CLONE" -> ProfileKind.CLONE
            "android.os.usertype.profile.PRIVATE" -> ProfileKind.PRIVATE
            else -> ProfileKind.OTHER
        }
    }
}
