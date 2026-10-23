package com.jarvis.control

/**
 * Pure rules for Instagram DM draft requests. No Android APIs here, so they run as plain JVM unit tests.
 * Draft only: these rules decide what the phone reports to the laptop, never whether a message is sent.
 */
object IgDraftRules {
    const val IG_PACKAGE = "com.instagram.android"

    /** Android's key for "this notification is a group conversation" (API 28+). Read as a plain key so minSdk 26 still works. */
    const val GROUP_EXTRA = "android.isGroupConversation"

    fun isInstagram(pkg: String?): Boolean = pkg == IG_PACKAGE

    /** A draft is requested only for an Instagram notification that has a title or some text. */
    fun shouldRequestDraft(pkg: String?, title: String?, text: String?): Boolean =
        isInstagram(pkg) && (!title.isNullOrBlank() || !text.isNullOrBlank())
}
