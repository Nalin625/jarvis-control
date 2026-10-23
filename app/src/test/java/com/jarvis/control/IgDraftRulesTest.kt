package com.jarvis.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IgDraftRulesTest {
    @Test
    fun onlyTheInstagramAppPackageCounts() {
        assertTrue(IgDraftRules.isInstagram("com.instagram.android"))
        assertFalse(IgDraftRules.isInstagram("com.whatsapp"))
        assertFalse(IgDraftRules.isInstagram("com.instagram.lite"))
        assertFalse(IgDraftRules.isInstagram(null))
    }

    @Test
    fun aDraftNeedsATitleOrSomeText() {
        assertTrue(IgDraftRules.shouldRequestDraft("com.instagram.android", "Sam", ""))
        assertTrue(IgDraftRules.shouldRequestDraft("com.instagram.android", "", "hi"))
        assertFalse(IgDraftRules.shouldRequestDraft("com.instagram.android", "", "   "))
        assertFalse(IgDraftRules.shouldRequestDraft("com.instagram.android", null, null))
    }

    @Test
    fun otherAppsNeverRequestADraft() {
        assertFalse(IgDraftRules.shouldRequestDraft("com.whatsapp", "Sam", "hi"))
    }

    @Test
    fun groupFlagKeyIsTheAndroidName() {
        assertEquals("android.isGroupConversation", IgDraftRules.GROUP_EXTRA)
    }
}
