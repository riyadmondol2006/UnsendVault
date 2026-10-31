package com.riyadm.unsendvault.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppDirectoryTest {
    private val own = "com.riyadm.unsendvault"

    @Test
    fun workOnlyApps_areListed_underTheirProfile() {
        val merged = AppDirectory.mergeProfiles(
            current = listOf("com.signal" to "Signal", own to "UnsendVault"),
            others = listOf(ProfileKind.WORK to listOf("com.whatsapp" to "WhatsApp", "com.viber" to "Viber", own to "UnsendVault")),
            ownPackage = own,
        )
        assertEquals(listOf("Signal", "Viber", "WhatsApp"), merged.map { it.label })
        assertEquals(ProfileKind.PERSONAL, merged.single { it.packageName == "com.signal" }.profile)
        assertEquals(ProfileKind.WORK, merged.single { it.packageName == "com.whatsapp" }.profile)
    }

    @Test
    fun appInBothProfiles_isListedOnce_asPersonal_withTheOtherProfiles() {
        val merged = AppDirectory.mergeProfiles(
            current = listOf("com.whatsapp" to "WhatsApp"),
            others = listOf(
                ProfileKind.WORK to listOf("com.whatsapp" to "WhatsApp"),
                ProfileKind.CLONE to listOf("com.whatsapp" to "WhatsApp"),
            ),
            ownPackage = own,
        ).single()
        assertEquals(ProfileKind.PERSONAL, merged.profile)
        assertEquals(setOf(ProfileKind.WORK, ProfileKind.CLONE), merged.alsoIn)
    }

    @Test
    fun appInTwoOtherProfiles_keepsTheFirst_andNotesTheSecond() {
        val merged = AppDirectory.mergeProfiles(
            current = emptyList(),
            others = listOf(
                ProfileKind.WORK to listOf("com.whatsapp" to "WhatsApp"),
                ProfileKind.WORK to listOf("com.whatsapp" to "WhatsApp"),
                ProfileKind.OTHER to listOf("com.whatsapp" to "WhatsApp"),
            ),
            ownPackage = own,
        ).single()
        assertEquals(ProfileKind.WORK, merged.profile)
        assertEquals(setOf(ProfileKind.OTHER), merged.alsoIn)
    }

    @Test
    fun userTypes_mapToProfileKinds() {
        assertEquals(ProfileKind.WORK, AppDirectory.profileKindOf("android.os.usertype.profile.MANAGED"))
        assertEquals(ProfileKind.CLONE, AppDirectory.profileKindOf("android.os.usertype.profile.CLONE"))
        assertEquals(ProfileKind.PRIVATE, AppDirectory.profileKindOf("android.os.usertype.profile.PRIVATE"))
        assertEquals(ProfileKind.OTHER, AppDirectory.profileKindOf("com.samsung.usertype.profile.SECURE_FOLDER"))
        assertEquals(ProfileKind.OTHER, AppDirectory.profileKindOf(null))
    }
}
