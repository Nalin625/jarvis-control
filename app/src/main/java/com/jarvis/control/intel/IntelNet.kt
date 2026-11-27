package com.jarvis.control.intel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.content.pm.PackageManager
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Network intelligence for the network this phone is on. Device scans only run when you confirm you own or administer it. */
object NetTools {
    class Dev(val ip: String, val host: String, val ms: Long, val ports: String, val mac: String)

    fun localInfo(ctx: Context): Report {
        val rep = Report("Local network", "network", "this phone's connection")
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        if (net == null) { rep.na("Connection", "not connected to any network"); return rep }
        val caps = cm.getNetworkCapabilities(net)
        val lp = cm.getLinkProperties(net)
        val kind = when { caps == null -> "unknown"; caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"; caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"; caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"; else -> "other" }
        rep.obs("Type", kind)
        if (caps != null) {
            rep.obs("Internet validated by Android", if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "yes" else "NO - connected but internet not confirmed")
            rep.obs("Metered", if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "no" else "yes")
            rep.est("Link bandwidth (as reported)", "down ${caps.linkDownstreamBandwidthKbps / 1000} Mbps / up ${caps.linkUpstreamBandwidthKbps / 1000} Mbps")
            rep.obs("VPN active", if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) "yes" else "no")
        }
        if (lp != null) {
            rep.obs("Phone address", lp.linkAddresses.joinToString(", ") { it.address.hostAddress ?: "" })
            rep.obs("Gateway (router)", lp.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress ?: "not reported")
            rep.obs("DNS servers", lp.dnsServers.joinToString(", ") { it.hostAddress ?: "" }.ifBlank { "none" })
            rep.obs("Private DNS", if (lp.isPrivateDnsActive) "active (${lp.privateDnsServerName ?: "automatic"})" else "off")
            if (lp.mtu > 0) rep.obs("MTU", lp.mtu.toString())
        }
        if (kind == "Wi-Fi") {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION") val wi = wm.connectionInfo
            val ssid = wi?.ssid?.trim('"')
            if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") rep.na("Wi-Fi name", "hidden by Android until the app has location permission (use the button below)")
            else rep.obs("Wi-Fi name", ssid)
            if (wi != null) { rep.obs("Signal", "${wi.rssi} dBm"); rep.obs("Link speed", "${wi.linkSpeed} Mbps"); if (wi.frequency > 0) rep.obs("Frequency", "${wi.frequency} MHz") }
        }
        return rep
    }

    fun hasLocation(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun tcp(host: String, port: Int, timeout: Int = 1500): Long {
        val t0 = System.nanoTime()
        return try { Socket().use { it.connect(InetSocketAddress(host, port), timeout) }; (System.nanoTime() - t0) / 1_000_000 } catch (_: Exception) { -1 }
    }

    fun latency(ctx: Context): Report {
        val rep = Report("Network latency", "latency", "internet reachability")
        val db = IntelDb.get(ctx)
        for ((name, host, port) in listOf(Triple("Cloudflare DNS", "1.1.1.1", 443), Triple("Google DNS", "8.8.8.8", 443), Triple("Quad9 DNS", "9.9.9.9", 443))) {
            val s = (1..5).map { tcp(host, port).also { Thread.sleep(80) } }
            val ok = s.filter { it >= 0 }
            if (ok.isEmpty()) rep.obs(name, "no answer (5 of 5 lost)", "")
            else {
                rep.obs(name, "min ${ok.min()} / avg ${ok.sum() / ok.size} / max ${ok.max()} ms, lost ${s.size - ok.size} of 5", "")
                db.snap("lat:$host", (ok.sum() / ok.size).toString())
            }
        }
        val past = db.snaps("lat:1.1.1.1", 20).map { it.second.toDoubleOrNull() ?: 0.0 }.reversed()
        if (past.size >= 3) rep.obs("History (Cloudflare avg ms)", Fmt.spark(past) + "  latest ${past.last().toInt()} ms")
        rep.summary = "Latency is measured as TCP connection time from this phone, so it includes your Wi-Fi and your ISP."
        return rep
    }

    fun connectivity(ctx: Context): Report {
        val rep = Report("Internet connectivity", "connectivity", "this phone")
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        rep.obs("Android says", if (caps == null) "offline" else if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "online (validated)" else "connected, no confirmed internet")
        val r = Net.get("https://connectivitycheck.gstatic.com/generate_204", timeoutMs = 6000, maxBytes = 1)
        rep.obs("Active test", if (r.code == 204) "internet works (${r.ms} ms)" else "failed: ${r.why()}", "https://connectivitycheck.gstatic.com/generate_204")
        IntelDb.get(ctx).snap("conn", if (r.code == 204) "up" else "down")
        val hist = IntelDb.get(ctx).snaps("conn", 12)
        if (hist.isNotEmpty()) rep.obs("Recent checks", hist.reversed().joinToString("") { if (it.second == "up") "▮" else "▯" } + "  (▮ up, ▯ down)")
        return rep
    }

    fun dnsDiagnostics(): Report {
        val rep = Report("DNS diagnostics", "dnsdiag", "this phone's resolver")
        for (name in listOf("example.com", "google.com", "wikipedia.org")) {
            val t0 = System.nanoTime()
            val sys = try { InetAddress.getAllByName(name).mapNotNull { (it as? Inet4Address)?.hostAddress }.sorted() } catch (e: Exception) { emptyList() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val cf = Net.get("https://cloudflare-dns.com/dns-query?name=$name&type=A", mapOf("Accept" to "application/dns-json"), timeoutMs = 6000).json()?.optJSONArray("Answer")
            val ref = ArrayList<String>(); if (cf != null) for (i in 0 until cf.length()) { val a = cf.getJSONObject(i); if (a.optInt("type") == 1) ref.add(a.optString("data")) }
            ref.sort()
            when {
                sys.isEmpty() -> rep.obs(name, "your resolver FAILED to answer (${ms} ms)", "")
                ref.isEmpty() -> rep.obs(name, "resolver answered in $ms ms; reference lookup unavailable", "")
                sys.intersect(ref.toSet()).isNotEmpty() -> rep.obs(name, "resolver $ms ms - matches Cloudflare's answer", "https://cloudflare-dns.com/dns-query?name=$name&type=A")
                else -> rep.obs(name, "resolver $ms ms - answer DIFFERS from Cloudflare's (${sys.joinToString()} vs ${ref.joinToString()}). Can be normal for CDNs; if it happens for many sites, your DNS may be altered.", "https://cloudflare-dns.com/dns-query?name=$name&type=A")
            }
        }
        return rep
    }

    // ---- devices on your own network
    fun localPrefix(ctx: Context): Pair<String, String>? {   // (base like 192.168.1., own address)
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        val a = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val ip = a.address.hostAddress ?: return null
        return ip.substringBeforeLast('.') + "." to ip
    }

    fun sweep(ctx: Context, progress: (Int) -> Unit = {}): List<Dev> {
        val (base, me) = localPrefix(ctx) ?: return emptyList()
        val pool = Executors.newFixedThreadPool(48)
        val jobs = (1..254).map { i ->
            val ip = base + i
            pool.submit(Callable<Dev?> {
                if (ip == me) return@Callable null
                val t0 = System.nanoTime()
                var alive = false
                try { alive = InetAddress.getByName(ip).isReachable(350) } catch (_: Exception) { }
                val open = ArrayList<String>()
                for (p in intArrayOf(80, 443, 22, 53, 445, 8080, 62078, 5353)) if (tcp(ip, p, if (alive) 250 else 120) >= 0) { open.add(p.toString()); alive = true }
                if (!alive) return@Callable null
                val ms = (System.nanoTime() - t0) / 1_000_000
                val name = try { val c = InetAddress.getByName(ip).canonicalHostName; if (c == ip) "" else c } catch (_: Exception) { "" }
                Dev(ip, name, ms, open.joinToString(","), arpMac(ip))
            })
        }
        val out = ArrayList<Dev>(); var done = 0
        for (j in jobs) { try { j.get(8, TimeUnit.SECONDS)?.let { out.add(it) } } catch (_: Exception) { }; done++; if (done % 25 == 0) progress(done * 100 / 254) }
        pool.shutdownNow()
        return out.sortedBy { it.ip.substringAfterLast('.').toInt() }
    }

    private fun arpMac(ip: String): String = try {
        File("/proc/net/arp").readLines().drop(1).map { it.split(Regex("\\s+")) }.firstOrNull { it.getOrNull(0) == ip && it.getOrNull(3) != "00:00:00:00:00:00" }?.getOrNull(3) ?: ""
    } catch (_: Exception) { "" }

    private fun hint(d: Dev): String = when {
        d.ports.contains("62078") -> "looks like an Apple device"
        d.ports.contains("53") && d.ports.contains("80") -> "probably the router / gateway"
        d.ports.contains("445") -> "file-sharing computer or NAS"
        d.ports.contains("22") -> "computer or server (SSH open)"
        d.ports.contains("80") || d.ports.contains("443") || d.ports.contains("8080") -> "device with a web page (printer, camera, TV, router...)"
        else -> "answers pings only"
    }

    /** Own-network device inventory with new-device detection. */
    fun deviceReport(ctx: Context, notify: Boolean = false, progress: (Int) -> Unit = {}): Report {
        val rep = Report("Devices on this network", "devices", localPrefix(ctx)?.first?.plus("0/24") ?: "no network")
        if (localPrefix(ctx) == null) { rep.na("Network", "not connected to a Wi-Fi/LAN network"); return rep }
        val devs = sweep(ctx, progress)
        val db = IntelDb.get(ctx); val key = "devices:" + rep.target
        val known = db.get(key).split('\n').filter { it.isNotBlank() }.toSet()
        val firstRun = known.isEmpty()
        rep.obs("Devices answering", "${devs.size} (this phone excluded)")
        val fresh = ArrayList<Dev>()
        for (d in devs) {
            val id = if (d.mac.isNotBlank()) d.mac else d.ip
            val isNew = !firstRun && !known.contains(id) && !known.contains(d.ip)
            if (isNew) fresh.add(d)
            rep.obs((if (isNew) "NEW  " else "") + d.ip, listOf(d.host.ifBlank { null }, hint(d), d.mac.ifBlank { null }?.let { "MAC $it" }, "open ports: " + d.ports.ifBlank { "none seen" }, "${d.ms} ms").filterNotNull().joinToString(" - "))
        }
        if (devs.any { it.mac.isBlank() }) rep.na("MAC addresses", "Android hides them from apps; devices are tracked by IP address, so a device that changes IP can appear as new")
        db.put(key, (devs.map { if (it.mac.isNotBlank()) it.mac else it.ip } + devs.map { it.ip }).joinToString("\n") + "\n" + known.joinToString("\n"))
        if (firstRun) rep.summary = "First scan saved as your baseline. Later scans flag devices that were not here before."
        else if (fresh.isEmpty()) rep.summary = "No new devices since the last scan."
        else {
            rep.summary = "${fresh.size} new device(s): " + fresh.joinToString { it.ip }
            db.event("warn", "New device(s) on the network: " + fresh.joinToString { it.ip })
        }
        rep.summary += " Devices that block pings and have no open ports will not be seen."
        return rep
    }

    fun history(ctx: Context): Report {
        val rep = Report("Network history", "nethistory", "this phone")
        val db = IntelDb.get(ctx)
        val lat = db.snaps("lat:1.1.1.1", 30).map { it.second.toDoubleOrNull() ?: 0.0 }.reversed()
        rep.obs("Latency to 1.1.1.1 (ms)", if (lat.isEmpty()) "no data yet - run Latency" else Fmt.spark(lat) + "  latest ${lat.last().toInt()}, min ${lat.min().toInt()}, max ${lat.max().toInt()}")
        val c = db.snaps("conn", 20).reversed()
        rep.obs("Connectivity checks", if (c.isEmpty()) "no data yet" else c.joinToString("") { if (it.second == "up") "▮" else "▯" } + "  last: ${Fmt.ago(c.last().first)}")
        for ((t, l, text) in db.events(20).filter { it.third.contains("device", true) || it.third.contains("network", true) || it.third.contains("internet", true) }) rep.obs("${Fmt.date(t)} [$l]", text)
        return rep
    }
}
