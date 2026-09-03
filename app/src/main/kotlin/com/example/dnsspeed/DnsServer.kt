package com.example.dnsspeed

data class DnsServer(
    val id: String,
    val provider: String,
    val label: String,
    val ip: String,
    val isCustom: Boolean = false
)

private fun builtin(provider: String, label: String, ip: String) =
    DnsServer(id = "builtin:$ip", provider = provider, label = label, ip = ip)

val POPULAR_DNS_SERVERS = listOf(
    builtin("Google Public DNS", "Основной", "8.8.8.8"),
    builtin("Google Public DNS", "Резервный", "8.8.4.4"),
    builtin("Cloudflare", "Основной", "1.1.1.1"),
    builtin("Cloudflare", "Резервный", "1.0.0.1"),
    builtin("Quad9", "Основной", "9.9.9.9"),
    builtin("Quad9", "Резервный", "149.112.112.112"),
    builtin("OpenDNS", "Основной", "208.67.222.222"),
    builtin("OpenDNS", "Резервный", "208.67.220.220"),
    builtin("AdGuard DNS", "Основной", "94.140.14.14"),
    builtin("AdGuard DNS", "Резервный", "94.140.15.15"),
    builtin("CleanBrowsing", "Основной", "185.228.168.9"),
    builtin("Comodo Secure DNS", "Основной", "8.26.56.26"),
    builtin("Яндекс.DNS", "Основной", "77.88.8.8"),
    builtin("Яндекс.DNS", "Резервный", "77.88.8.1"),
)

/** Accepts IPv4 (with octet range check) and basic IPv6 literals. */
fun isValidDnsAddress(input: String): Boolean {
    val value = input.trim()
    if (value.isEmpty()) return false

    if (value.contains(":")) {
        // Basic IPv6 sanity check: only hex digits and colons, at least two colons.
        return value.count { it == ':' } >= 2 &&
            value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
    }

    val octets = value.split(".")
    if (octets.size != 4) return false
    return octets.all { octet ->
        octet.isNotEmpty() && octet.length <= 3 && octet.all { it.isDigit() } && octet.toInt() in 0..255
    }
}
