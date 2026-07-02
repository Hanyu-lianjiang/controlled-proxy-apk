package com.v2ray.ang.control

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object ControlledSession {
    private const val DEFAULT_BASE_URL = "http://43.108.8.13"
    private const val PREF_NAME = "controlled_backend"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_USERNAME = "username"
    private const val KEY_USER_LABEL = "user_label"
    private const val KEY_EXPIRES_AT = "expires_at"
    private const val KEY_TRAFFIC_LIMIT_GB = "traffic_limit_gb"
    private const val KEY_TRAFFIC_LIMIT_MB = "traffic_limit_mb"
    private const val KEY_TRAFFIC_USED_BYTES = "traffic_used_bytes"
    private const val KEY_TRAFFIC_USED_GB = "traffic_used_gb"
    private const val KEY_TRAFFIC_USED_MB = "traffic_used_mb"
    private const val KEY_TRAFFIC_EXCEEDED = "traffic_exceeded"
    private const val KEY_APP_TRAFFIC_TX_BYTES = "app_traffic_tx_bytes"
    private const val KEY_APP_TRAFFIC_RX_BYTES = "app_traffic_rx_bytes"
    private const val KEY_LAST_SYNC_AT = "last_sync_at"
    private const val KEY_NODES = "nodes"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getBaseUrl(context: Context): String = DEFAULT_BASE_URL

    fun getToken(context: Context): String =
        prefs(context).getString(KEY_TOKEN, "").orEmpty()

    fun hasToken(context: Context): Boolean =
        getToken(context).isNotBlank() && getBaseUrl(context).isNotBlank()

    fun getDeviceId(context: Context): String {
        val existing = prefs(context).getString(KEY_DEVICE_ID, "").orEmpty()
        if (existing.isNotBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    fun saveAuth(context: Context, baseUrl: String, token: String, user: ControlledUser) {
        prefs(context).edit()
            .putString(KEY_BASE_URL, baseUrl.trimEnd('/'))
            .putString(KEY_TOKEN, token)
            .putString(KEY_USERNAME, user.username)
            .putString(KEY_USER_LABEL, user.label.ifBlank { user.username })
            .putString(KEY_EXPIRES_AT, user.expiresAt.orEmpty())
            .putString(KEY_TRAFFIC_LIMIT_GB, user.trafficLimitGb.toString())
            .putLong(KEY_TRAFFIC_LIMIT_MB, user.trafficLimitMb)
            .putLong(KEY_TRAFFIC_USED_BYTES, user.trafficUsedBytes)
            .putString(KEY_TRAFFIC_USED_GB, user.trafficUsedGb.toString())
            .putString(KEY_TRAFFIC_USED_MB, user.trafficUsedMb.toString())
            .putBoolean(KEY_TRAFFIC_EXCEEDED, user.trafficExceeded)
            .apply()
    }

    fun markSynced(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis())
            .apply()
    }

    fun saveNodes(context: Context, nodes: List<ControlledNode>) {
        val array = JSONArray()
        nodes.forEach { node ->
            array.put(
                JSONObject()
                    .put("id", node.id)
                    .put("name", node.name)
                    .put("host", node.host.orEmpty())
                    .put("countryCode", node.countryCode.orEmpty())
                    .put("countryName", node.countryName.orEmpty())
                    .put("flagEmoji", node.flagEmoji.orEmpty())
            )
        }
        prefs(context).edit().putString(KEY_NODES, array.toString()).apply()
    }

    fun getNodes(context: Context): List<ControlledNode> {
        val raw = prefs(context).getString(KEY_NODES, "[]").orEmpty()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        ControlledNode(
                            id = item.optString("id"),
                            name = item.optString("name"),
                            uri = "",
                            host = item.optString("host").ifBlank { null },
                            countryCode = item.optString("countryCode").ifBlank { null },
                            countryName = item.optString("countryName").ifBlank { null },
                            flagEmoji = item.optString("flagEmoji").ifBlank { null },
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun statusText(context: Context): String {
        val userLabel = prefs(context).getString(KEY_USER_LABEL, "").orEmpty()
        val expiresAt = prefs(context).getString(KEY_EXPIRES_AT, "").orEmpty()
        val lastSyncAt = prefs(context).getLong(KEY_LAST_SYNC_AT, 0L)
        return buildString {
            append(if (hasToken(context)) "已登录" else "未登录")
            if (userLabel.isNotBlank()) append("\n用户：").append(userLabel)
            if (expiresAt.isNotBlank()) append("\n到期：").append(expiresAt)
            if (lastSyncAt > 0L) append("\n上次同步：").append(android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", lastSyncAt))
        }
    }

    fun userLabel(context: Context): String =
        prefs(context).getString(KEY_USER_LABEL, "").orEmpty()

    fun username(context: Context): String =
        prefs(context).getString(KEY_USERNAME, "").orEmpty().ifBlank { userLabel(context) }

    fun expiresAt(context: Context): String =
        prefs(context).getString(KEY_EXPIRES_AT, "").orEmpty()

    fun trafficLimitMb(context: Context): Long =
        prefs(context).getLong(KEY_TRAFFIC_LIMIT_MB, 0L)

    fun trafficLimitGb(context: Context): Double {
        val raw = prefs(context).getString(KEY_TRAFFIC_LIMIT_GB, "").orEmpty().toDoubleOrNull()
        if (raw != null && raw > 0.0) return raw
        return trafficLimitMb(context) / 1024.0
    }

    fun trafficUsedBytes(context: Context): Long =
        prefs(context).getLong(KEY_TRAFFIC_USED_BYTES, 0L)

    fun trafficUsedMbText(context: Context): String =
        prefs(context).getString(KEY_TRAFFIC_USED_MB, "0").orEmpty().ifBlank { "0" }

    fun trafficUsedMb(context: Context): Double =
        trafficUsedMbText(context).toDoubleOrNull() ?: (trafficUsedBytes(context) / 1024.0 / 1024.0)

    fun trafficUsedGbText(context: Context): String =
        prefs(context).getString(KEY_TRAFFIC_USED_GB, "").orEmpty().ifBlank { formatGb(trafficUsedGb(context)) }

    fun trafficUsedGb(context: Context): Double {
        val raw = prefs(context).getString(KEY_TRAFFIC_USED_GB, "").orEmpty().toDoubleOrNull()
        if (raw != null) return raw
        return trafficUsedBytes(context) / 1024.0 / 1024.0 / 1024.0
    }

    fun formatGb(value: Double): String =
        if (value >= 100) value.toInt().toString() else String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

    fun trafficExceeded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TRAFFIC_EXCEEDED, false)

    fun lastSyncAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SYNC_AT, 0L)

    fun saveAppTrafficCounters(context: Context, txBytes: Long, rxBytes: Long) {
        prefs(context).edit()
            .putLong(KEY_APP_TRAFFIC_TX_BYTES, txBytes)
            .putLong(KEY_APP_TRAFFIC_RX_BYTES, rxBytes)
            .apply()
    }

    fun appTrafficCounters(context: Context): Pair<Long, Long> =
        prefs(context).getLong(KEY_APP_TRAFFIC_TX_BYTES, -1L) to
            prefs(context).getLong(KEY_APP_TRAFFIC_RX_BYTES, -1L)

    fun clearAppTrafficCounters(context: Context) {
        prefs(context).edit()
            .remove(KEY_APP_TRAFFIC_TX_BYTES)
            .remove(KEY_APP_TRAFFIC_RX_BYTES)
            .apply()
    }

    fun clear(context: Context) {
        val deviceId = getDeviceId(context)
        prefs(context).edit().clear().putString(KEY_DEVICE_ID, deviceId).apply()
    }
}
