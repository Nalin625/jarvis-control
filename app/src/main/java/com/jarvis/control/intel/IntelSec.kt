package com.jarvis.control.intel

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.net.URI
import java.security.MessageDigest

/** Personal cybersecurity: URL checks, breach exposure for your own addresses, and local device/app configuration checks. */
object SecTools {
    private val shorteners = setOf("bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "cutt.ly", "rb.gy", "ow.ly", "shorturl.at", "tiny.cc", "rebrand.ly", "buff.ly")
    private val riskyTld = setOf("zip", "mov", "tk", "ml", "ga", "cf", "gq", "top", "xyz", "click", "work", "support", "country", "kim", "loan", "icu", "cyou", "buzz", "rest", "monster", "sbs", "cfd")
    private val words = listOf("login", "signin", "verify", "secure", "update", "account", "wallet", "bank", "kyc", "otp", "refund", "reward", "prize", "gift", "suspend", "unlock", "confirm", "billing", "password", "support")
    private val brands = listOf("google", "paypal", "amazon", "flipkart", "paytm", "phonepe", "sbi", "hdfc", "icici", "axisbank", "instagram", "facebook", "whatsapp", "microsoft", "apple", "netflix", "irctc", "gpay", "airtel", "jio", "binance", "github")

    private fun lev(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) { 0 } }
        for (i in 0..a.length) d[i][0] = i; for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }

    /** Phishing / suspicious-domain check. Only looks at the address and public records; the page itself is never opened. */
    fun checkUrl(input: String, online: Boolean = true): Report {
        val raw = input.trim()
        val rep = Report("Phishing / URL check", "phishing", raw.take(80))
        val full = if (raw.contains("://")) raw else "http://$raw"
        val uri = try { URI(full) } catch (_: Exception) { null }
        val host = (uri?.host ?: Fmt.domainOf(raw)).lowercase()
        if (host.isBlank() || !host.contains('.')) { rep.na("Input", "enter a link or a domain name"); return rep }
        var score = 0
        fun sig(w: Int, label: String, v: String) { score += w; rep.obs(label, v) }
        val labels = host.split('.')
        val sld = if (labels.size >= 2) labels[labels.size - 2] else host
        val tld = labels.last()
        if (!raw.startsWith("https://")) sig(1, "No HTTPS", "the address is not using https")
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)) sig(3, "IP address as host", "real services rarely use a bare IP in links")
        if (uri?.userInfo != null) sig(3, "Hidden login part (@)", "text before @ can disguise the real site: ${uri.userInfo}")
        if (host.contains("xn--")) sig(3, "Look-alike characters", "punycode name (${java.net.IDN.toUnicode(host)}) - may imitate another site")
        if (labels.size > 4) sig(1, "Many subdomains", "${labels.size} name parts: the real domain is '$sld.$tld'")
        if (raw.length > 120) sig(1, "Very long link", "${raw.length} characters")
        if (tld in riskyTld) sig(1, "Often-abused ending", ".$tld is popular with throwaway sites")
        if (host in shorteners) sig(2, "Link shortener", "hides the real destination")
        val hit = words.filter { host.contains(it) || (uri?.path ?: "").lowercase().contains(it) }
        if (hit.isNotEmpty()) sig(1, "Pressure / account words", hit.take(5).joinToString())
        for (b in brands) {
            val inName = host.contains(b)
            val isReal = sld == b || (b == "axisbank" && sld == "axisbank") || (b == "sbi" && sld == "sbi")
            if (inName && !isReal) { sig(3, "Brand name inside another domain", "contains '$b' but the real domain is '$sld.$tld'"); break }
            if (!inName && lev(sld.replace("-", ""), b) in 1..1 && sld.length >= 5) { sig(3, "Spelled almost like '$b'", "'$sld' is one letter away from '$b'"); break }
        }
        if (online) {
            val d = "$sld.$tld"
            val r = Net.get("https://rdap.org/domain/$d", timeoutMs = 9000).json()
            val age = r?.let { WebTools.domainAgeDays(it) }
            if (age != null) { if (age < 30) sig(3, "Very new domain", "registered only $age days ago") else if (age < 180) sig(1, "New domain", "registered $age days ago") else rep.ver("Domain age", "$age days", "https://rdap.org/domain/$d") }
            else rep.na("Domain age", "registry did not provide it", "https://rdap.org/domain/$d")
            val dns = Net.get("https://dns.google/resolve?name=${Net.enc(host)}&type=A", timeoutMs = 6000).json()
            if (dns != null && dns.optInt("Status", 0) == 3) sig(2, "Domain does not exist", "no DNS record (NXDOMAIN)") else if (dns != null) rep.ver("DNS", "resolves", "https://dns.google/resolve?name=$host&type=A")
        }
        val level = when { score >= 6 -> "HIGH"; score >= 3 -> "MEDIUM"; else -> "LOW" }
        rep.est("Risk signals", "$level ($score points)")
        rep.summary = "This is a risk ESTIMATE from the address and public records only. LOW does not prove a site is safe, and HIGH does not prove it is malicious. When in doubt, type the official address yourself."
        return rep
    }

    /** Email exposure in public breaches. Only for addresses you own or are authorised to check. */
    fun breach(email: String): Report {
        val e = email.trim().lowercase()
        val masked = e.substringBefore('@').take(1) + "***@" + e.substringAfter('@', "?")
        val rep = Report("Email breach exposure", "breach", masked)
        if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(e)) { rep.na("Input", "enter a valid email address"); return rep }
        val src = "https://api.xposedornot.com/v1/check-email/${Net.enc(e)}"
        val r = Net.get(src, timeoutMs = 10000)
        if (r.code == 404) { rep.ver("Result", "not found in the public breaches known to XposedOrNot", src); rep.summary = "Good sign, but no database knows every leak."; return rep }
        val j = r.json()
        if (j == null) { rep.na("Lookup", r.why(), src); return rep }
        val arr = j.optJSONArray("breaches")?.optJSONArray(0)
        if (arr == null || arr.length() == 0) rep.ver("Result", "not found in known breaches", src)
        else {
            rep.ver("Found in breaches", arr.length().toString(), src)
            for (i in 0 until arr.length()) rep.ver("Breach", arr.getString(i), "https://xposedornot.com/")
            rep.summary = "Change the password on these services (use a unique one for each) and turn on two-step verification. The address itself is not saved in your history."
        }
        return rep
    }

    /** Password exposure via k-anonymity: only the first 5 characters of a hash leave the phone; the password is never stored. */
    fun passwordExposure(pw: String): Report {
        val rep = Report("Password exposure check", "password", "(not stored)")
        if (pw.isEmpty()) { rep.na("Input", "type the password to test"); return rep }
        val h = MessageDigest.getInstance("SHA-1").digest(pw.toByteArray()).joinToString("") { "%02X".format(it) }
        val src = "https://api.pwnedpasswords.com/range/${h.take(5)}"
        val r = Net.get(src, mapOf("Add-Padding" to "true"), timeoutMs = 9000)
        if (!r.ok) { rep.na("Lookup", r.why(), src); return rep }
        val count = r.body.lineSequence().map { it.trim().split(':') }.firstOrNull { it.size == 2 && it[0] == h.substring(5) }?.get(1)?.toLongOrNull() ?: 0
        if (count > 0) rep.ver("Result", "seen $count times in known breaches - do NOT use this password", src) else rep.ver("Result", "not found in known breaches", src)
        rep.obs("Length", "${pw.length} characters" + if (pw.length < 10) " (short)" else "")
        rep.summary = "Only the first 5 characters of the password's SHA-1 hash were sent. The password was not saved or logged."
        return rep
    }

    // ---- device configuration (local only)
    fun deviceSecurity(ctx: Context): Report {
        val rep = Report("Device security check", "devicesec", "this phone")
        val kg = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        rep.obs("Screen lock", if (kg.isDeviceSecure) "set (PIN / pattern / password / biometric)" else "NOT SET - anyone holding the phone can open it")
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val enc = dpm.storageEncryptionStatus
        rep.obs("Storage encryption", when (enc) { DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE, DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER -> "on"; DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED -> "not supported"; else -> "not active" })
        val adb = Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        rep.obs("USB debugging", if (adb) "ON - turn off unless you are developing" else "off")
        rep.obs("Developer options", if (Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1) "enabled" else "off")
        val patch = Build.VERSION.SECURITY_PATCH
        val age = try { (System.currentTimeMillis() - java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(patch)!!.time) / 86_400_000 } catch (_: Exception) { -1 }
        rep.obs("Android security patch", "$patch" + if (age >= 0) " ($age days old)" + if (age > 180) " - OLD, check for system updates" else "" else "")
        rep.obs("Android version", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val acc = Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services").orEmpty().split(':').filter { it.isNotBlank() }
        rep.obs("Accessibility services (can read the screen)", if (acc.isEmpty()) "none" else acc.joinToString("\n") { it.substringBefore('/') })
        val nl = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty().split(':').filter { it.isNotBlank() }
        rep.obs("Apps that can read notifications", if (nl.isEmpty()) "none" else nl.joinToString("\n") { it.substringBefore('/') })
        try {
            val admins = dpm.activeAdmins.orEmpty()
            rep.obs("Device administrator apps", if (admins.isEmpty()) "none" else admins.joinToString("\n") { it.packageName })
        } catch (_: Exception) { }
        rep.summary = "Everything above is read from this phone's own settings. Check each listed app is one you installed and trust."
        return rep
    }

    fun appAudit(ctx: Context): Report {
        val rep = Report("App security audit", "appaudit", "installed apps")
        val pm = ctx.packageManager
        val dangerous = listOf("android.permission.SEND_SMS", "android.permission.READ_SMS", "android.permission.RECEIVE_SMS", "android.permission.RECORD_AUDIO", "android.permission.CAMERA",
            "android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.READ_CONTACTS", "android.permission.READ_CALL_LOG", "android.permission.SYSTEM_ALERT_WINDOW", "android.permission.REQUEST_INSTALL_PACKAGES")
        val apps = try { pm.getInstalledPackages(PackageManager.GET_PERMISSIONS) } catch (_: Exception) { emptyList<PackageInfo>() }
        val user = apps.filter { it.applicationInfo != null && (it.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
        rep.obs("Apps you installed", user.size.toString())
        val side = user.filter { val i = try { if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(it.packageName).installingPackageName else @Suppress("DEPRECATION") pm.getInstallerPackageName(it.packageName) } catch (_: Exception) { null }; i != "com.android.vending" }
        rep.obs("Not installed from Google Play (sideloaded)", if (side.isEmpty()) "none" else "${side.size}: " + side.take(12).joinToString { pm.getApplicationLabel(it.applicationInfo!!).toString() })
        val risky = user.map { p -> p to (p.requestedPermissions ?: emptyArray()).count { it in dangerous } }.filter { it.second >= 4 }.sortedByDescending { it.second }
        rep.est("Apps asking for many sensitive permissions", if (risky.isEmpty()) "none with 4 or more" else risky.take(10).joinToString("\n") { "${pm.getApplicationLabel(it.first.applicationInfo!!)} (${it.second} sensitive permissions)" })
        // this app's own configuration
        val me = ctx.packageName
        val pi = pm.getPackageInfo(me, PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS)
        val f = pi.applicationInfo!!.flags
        rep.obs("This app: debuggable build", if (f and ApplicationInfo.FLAG_DEBUGGABLE != 0) "yes (normal for your own debug builds; a release build should be 'no')" else "no")
        rep.obs("This app: backups allowed", if (f and ApplicationInfo.FLAG_ALLOW_BACKUP != 0) "yes - app data can be extracted with a backup" else "no")
        rep.obs("This app: cleartext http allowed", if (f and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0) "yes (the control server uses plain http on your Wi-Fi)" else "no")
        val exported = (pi.activities.orEmpty().filter { it.exported } + pi.services.orEmpty().filter { it.exported } + pi.receivers.orEmpty().filter { it.exported }).map { it.name.substringAfterLast('.') }
        rep.obs("This app: exported components", "${exported.size}" + if (exported.isNotEmpty()) " (" + exported.take(8).joinToString() + (if (exported.size > 8) ", ..." else "") + ")" else "")
        rep.obs("This app: target SDK", pi.applicationInfo!!.targetSdkVersion.toString())
        rep.summary = "Counts and lists come from Android's package manager on this phone. 'Sensitive permissions' is a rough estimate of risk, not proof of misuse."
        return rep
    }

    fun localReport(ctx: Context): Report {
        val rep = Report("Local security report", "secreport", "this phone")
        val parts = listOf(deviceSecurity(ctx), appAudit(ctx))
        for (p in parts) for (f in p.findings) rep.add("${p.title.substringBefore(" ")} ${f.label}", f.value, f.ev, f.source)
        val ev = IntelDb.get(ctx).events(15, System.currentTimeMillis() - 30L * 86_400_000)
        if (ev.isEmpty()) rep.obs("Security events (30 days)", "none recorded") else for ((t, l, x) in ev) rep.obs("Event ${Fmt.date(t)} [$l]", x)
        rep.summary = "Generated locally on ${rep.whenText()}. Nothing in this report was sent anywhere."
        return rep
    }

    fun eventsReport(ctx: Context): Report {
        val rep = Report("Security event history", "events", "this phone")
        val ev = IntelDb.get(ctx).events(100)
        if (ev.isEmpty()) rep.na("Events", "none recorded yet") else for ((t, l, x) in ev) rep.obs("${Fmt.date(t)} [$l]", x)
        return rep
    }
}
