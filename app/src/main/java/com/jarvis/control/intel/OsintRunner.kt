package com.jarvis.control.intel

import android.content.Context

/*
 * Runs one OSINT check by name, so the laptop (through the phone's control server) and the phone screen use the same checks.
 * Each check fails soft: a bad input or a dead source comes back as an UNAVAILABLE finding, never as a crash.
 */
object OsintRunner {
    /** Check names the control server accepts. */
    val kinds = listOf("mx", "mailauth", "robots", "relations", "email", "ghuser", "ghrepos", "ghrepo", "cve", "package", "reputation")

    fun run(ctx: Context, kind: String, input: String): Report {
        val t = input.trim()
        return when (kind) {
            "mx" -> OsintDomain.mxRecords(t)
            "mailauth" -> OsintDomain.spfDmarcDkim(t)
            "robots" -> OsintDomain.robotsAndSitemaps(t)
            "relations" -> OsintDomain.relationships(t).report
            "email" -> OsintEmail.check(t)
            "ghuser" -> OsintGithub.searchUsers(t, ctx)
            "ghrepos" -> OsintGithub.searchRepos(t, ctx)
            "ghrepo" -> OsintGithub.repoDetail(t, ctx)
            "cve" -> OsintThreat.cve(t, ctx)
            "package" -> OsintThreat.osv(t)
            "reputation" -> OsintThreat.reputation(t, ctx)
            else -> Report("Unknown check", "error", t).also { it.na("Check", "no such check: $kind") }
        }
    }

    /** Saves a finished check to a named project, creating the project if needed. Returns the project name, or null if nothing was saved. */
    fun save(ctx: Context, projectName: String, r: Report): String? {
        if (r.kind == "error") return null
        val store = SqlWorkspaceStore.get(ctx)
        val name = projectName.trim().ifBlank { "Laptop checks" }
        val existing = store.projects().firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
        val pid = existing ?: store.createProject(name, System.currentTimeMillis()).takeIf { it >= 0 } ?: return null
        return if (store.addItem(reportToItem(r, pid)) >= 0) name else null
    }
}
