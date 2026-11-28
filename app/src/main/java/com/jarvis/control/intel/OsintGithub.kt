package com.jarvis.control.intel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/*
 * OSINT phase 3: GitHub intelligence through the official REST API, public data only.
 * Works without an account (60 requests an hour). An optional personal token, stored in the Keystore vault under
 * "github_token", raises that limit to 5,000 an hour. The token is sent only in the Authorization header.
 *
 * Not implemented on purpose: GitHub code search. It needs a token, and searching code for security-relevant strings
 * is easy to misuse, so it stays out of this app. Shown as UNSUPPORTED in the dashboard.
 */
object OsintGithub {
    private const val API = "https://api.github.com"

    private fun headers(ctx: Context?): Map<String, String> {
        val t = ctx?.let { Vault.get(it, "github_token") }
        return if (t.isNullOrBlank()) emptyMap()
        else mapOf("Authorization" to "Bearer $t", "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")
    }

    private fun day(iso: String): String = if (iso.length >= 10) iso.take(10) else iso
    private val fullName = Regex("^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$")

    private fun rate(r: NetResult): String = r.header("x-ratelimit-remaining")?.let { " (GitHub requests left this hour: $it)" } ?: ""

    private fun topics(o: JSONObject): String {
        val a: JSONArray = o.optJSONArray("topics") ?: return "none"
        if (a.length() == 0) return "none"
        return (0 until minOf(a.length(), 8)).joinToString(", ") { a.optString(it) }
    }

    fun searchUsers(q: String, ctx: Context?): Report {
        val term = q.trim().removePrefix("@")
        val rep = Report("GitHub user search - $term", "github-users", term)
        if (term.length < 2) return rep.also { it.na("Search", "enter at least 2 characters") }
        val src = "$API/search/users?q=${Net.enc(term)}&per_page=8"
        val r = Net.get(src, headers(ctx), timeoutMs = 9000, cacheSec = 900)
        val j = r.json()
        if (j == null) { rep.na("Search", r.why() + rate(r), src); return rep }
        val items = j.optJSONArray("items") ?: JSONArray()
        rep.ver("Matching public accounts", "${j.optInt("total_count")} (top ${minOf(items.length(), 8)} shown)" + rate(r), src)
        for (i in 0 until minOf(items.length(), 8)) {
            val o = items.getJSONObject(i)
            rep.ver(o.optString("login"), "${o.optString("type")}  ·  ${o.optString("html_url")}", o.optString("html_url"))
        }
        rep.summary = "Public GitHub accounts whose name matches. Two accounts with the same name are not proven to belong to the same person."
        return rep
    }

    fun searchRepos(q: String, ctx: Context?): Report {
        val term = q.trim()
        val rep = Report("GitHub repository search - $term", "github-repos", term)
        if (term.length < 2) return rep.also { it.na("Search", "enter at least 2 characters") }
        val src = "$API/search/repositories?q=${Net.enc(term)}&sort=stars&order=desc&per_page=8"
        val r = Net.get(src, headers(ctx), timeoutMs = 9000, cacheSec = 900)
        val j = r.json()
        if (j == null) { rep.na("Search", r.why() + rate(r), src); return rep }
        val items = j.optJSONArray("items") ?: JSONArray()
        rep.ver("Matching public repositories", "${j.optInt("total_count")} (top ${minOf(items.length(), 8)} by stars shown)" + rate(r), src)
        for (i in 0 until minOf(items.length(), 8)) {
            val o = items.getJSONObject(i)
            val lang = o.optString("language").ifBlank { "no language" }
            val flags = listOfNotNull(if (o.optBoolean("archived")) "archived" else null, if (o.optBoolean("fork")) "fork" else null)
            val extra = if (flags.isEmpty()) "" else "  [" + flags.joinToString(", ") + "]"
            rep.ver(o.optString("full_name"), "★ ${o.optInt("stargazers_count")}  ·  $lang  ·  updated ${day(o.optString("updated_at"))}$extra\n" +
                "topics: ${topics(o)}\n" + o.optString("description", "").take(120), o.optString("html_url"))
        }
        rep.summary = "Public repositories only, sorted by stars. Stars and dates come from GitHub; nothing here shows who is behind a project."
        return rep
    }

    /** One repository in detail: metadata plus language shares. */
    fun repoDetail(fullNameInput: String, ctx: Context?): Report {
        val name = fullNameInput.trim().trim('/')
        val rep = Report("GitHub repository - $name", "github-repo", name)
        if (!fullName.matches(name)) return rep.also { it.na("Input", "enter it as owner/name, e.g. octocat/Hello-World") }
        val src = "$API/repos/$name"
        val r = Net.get(src, headers(ctx), timeoutMs = 9000, cacheSec = 900)
        if (r.code == 404) { rep.na("Repository", "not found, or it is private (only public repositories are read)", src); return rep }
        val o = r.json()
        if (o == null) { rep.na("Repository", r.why() + rate(r), src); return rep }
        rep.ver("Description", o.optString("description", "none").take(200), src)
        rep.ver("Stars", "${o.optInt("stargazers_count")}  ·  forks ${o.optInt("forks_count")}  ·  open issues ${o.optInt("open_issues_count")}", src)
        rep.ver("Primary language", o.optString("language").ifBlank { "none set" }, src)
        rep.ver("Topics", topics(o), src)
        rep.ver("License", o.optJSONObject("license")?.optString("spdx_id")?.takeIf { it.isNotBlank() && it != "null" } ?: "none listed", src)
        rep.ver("Created", day(o.optString("created_at")), src)
        rep.ver("Last push", day(o.optString("pushed_at")), src)
        rep.ver("Last update", day(o.optString("updated_at")), src)
        rep.ver("Default branch", o.optString("default_branch"), src)
        rep.ver("Archived", if (o.optBoolean("archived")) "yes" else "no", src)
        rep.ver("Fork", if (o.optBoolean("fork")) "yes (a copy of another repository)" else "no", src)

        val langSrc = "$API/repos/$name/languages"
        val lr = Net.get(langSrc, headers(ctx), timeoutMs = 9000, cacheSec = 900)
        val langs = lr.json()
        if (langs == null) rep.na("Languages", lr.why(), langSrc)
        else {
            val total = langs.keys().asSequence().sumOf { langs.optLong(it) }.toDouble()
            if (total <= 0) rep.obs("Languages", "none recorded", langSrc)
            else rep.ver("Languages", langs.keys().asSequence().map { it to langs.optLong(it) }.sortedByDescending { it.second }.take(6)
                .joinToString(", ") { "${it.first} ${"%.1f".format(it.second * 100.0 / total)}%" }, langSrc)
        }
        rep.summary = "Read from the public GitHub API. The repository's owner is shown as stated by GitHub; nothing here is checked against any other account."
        return rep
    }

    /** Shown in the dashboard as a known gap rather than a button that pretends to work. */
    fun unsupportedNote(): Report = Report("GitHub code search", "unsupported", "")
        .also { it.na("Status", "not implemented: GitHub requires a token for code search, and searching code for security-relevant strings is not offered in this app") }
}
