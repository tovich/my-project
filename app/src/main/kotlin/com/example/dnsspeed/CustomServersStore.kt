package com.example.dnsspeed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists user-added DNS servers in SharedPreferences. */
object CustomServersStore {

    private const val PREFS = "dns_speed_prefs"
    private const val KEY = "custom_servers"

    fun load(context: Context): List<DnsServer> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()

        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                DnsServer(
                    id = obj.getString("id"),
                    provider = obj.getString("provider"),
                    label = "Свой сервер",
                    ip = obj.getString("ip"),
                    isCustom = true
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, servers: List<DnsServer>) {
        val array = JSONArray()
        servers.filter { it.isCustom }.forEach { server ->
            array.put(
                JSONObject()
                    .put("id", server.id)
                    .put("provider", server.provider)
                    .put("ip", server.ip)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }
}
