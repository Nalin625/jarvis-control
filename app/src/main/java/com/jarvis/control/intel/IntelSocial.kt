package com.jarvis.control.intel

import android.content.Context

/** Analytics for an Instagram account YOU own or manage, through Meta's official Graph API with your own token.
 *  No scraping, no login bypass, no follower identities. */
object SocialTools {
    private const val GRAPH = "https://graph.facebook.com/v19.0"

    fun configured(ctx: Context): Boolean = IntelDb.get(ctx).get("ig_user").isNotBlank() && Vault.has(ctx, "ig_token")

    fun setup(ctx: Context, userId: String, token: String): String {
        if (!Regex("^\\d{5,20}$").matches(userId.trim())) return "The account ID is numbers only (from your own account in Meta for Developers)."
        if (token.trim().length < 20) return "That token looks too short."
        IntelDb.get(ctx).put("ig_user", userId.trim())
        return if (Vault.put(ctx, "ig_token", token.trim())) "Saved. The token is encrypted on this phone (Android Keystore)."
        else "Couldn't save the token: the Android Keystore is unavailable on this phone."
    }

    fun report(ctx: Context): Report {
        val rep = Report("Social analytics - your account", "social", "your account")
        if (!configured(ctx)) {
            rep.na("Setup", "not set up yet. Press 'Set up my Instagram' and enter your account ID and an access token from Meta for Developers. Only accounts you own or manage can be read.")
            return rep
        }
        val db = IntelDb.get(ctx)
        val uid = db.get("ig_user")
        val token = Vault.get(ctx, "ig_token")
        if (token == null) { rep.na("Token", "could not be read from the Keystore - set it up again"); return rep }
        val src = "$GRAPH/$uid"
        val auth = mapOf("Authorization" to "Bearer $token")
        val r = Net.get("$src?fields=username,followers_count,follows_count,media_count", auth, timeoutMs = 9000)
        val j = r.json()
        if (j == null || j.has("error")) {
            rep.na("Account", (j?.optJSONObject("error")?.optString("message") ?: r.why()).take(140), src)
            return rep
        }
        val followers = j.optInt("followers_count", -1)
        rep.ver("Account", "@" + j.optString("username"), src)
        rep.ver("Followers", followers.toString(), src)
        rep.ver("Following", j.optInt("follows_count").toString(), src)
        rep.ver("Posts", j.optInt("media_count").toString(), src)
        val series = db.snaps("ig:followers", 30).map { it.second.toDoubleOrNull() ?: 0.0 }.reversed() + listOf(followers.toDouble())
        if (followers >= 0) db.snap("ig:followers", followers.toString())
        if (series.size >= 2) {
            val d = series.last() - series.first()
            rep.obs("Follower history", Fmt.spark(series) + "  change " + (if (d >= 0) "+" else "") + d.toInt())
        } else rep.obs("Follower history", "started now - check again later to see changes")
        val media = Net.get("$src/media?fields=id,media_type,timestamp,permalink&limit=10", auth, timeoutMs = 9000).json()?.optJSONArray("data")
        if (media == null) rep.na("Recent posts", "could not be read", src)
        else {
            val items = (0 until media.length()).map { media.getJSONObject(it) }
            val prev = db.lastSnap("ig:media")?.split(",")?.toSet().orEmpty()
            if (prev.isEmpty()) rep.obs("Posts", "${items.size} recent posts saved as the baseline")
            else {
                val fresh = items.filter { it.optString("id") !in prev }
                if (fresh.isEmpty()) rep.obs("New posts", "none since last check")
                for (p in fresh.take(5)) rep.obs("New post", "${p.optString("media_type")} ${p.optString("timestamp").take(10)}", p.optString("permalink"))
            }
            db.snap("ig:media", items.joinToString(",") { it.optString("id") })
        }
        rep.summary = "Read through Meta's official API with your own token: your own counts and posts only. Nothing is scraped."
        return rep
    }
}
