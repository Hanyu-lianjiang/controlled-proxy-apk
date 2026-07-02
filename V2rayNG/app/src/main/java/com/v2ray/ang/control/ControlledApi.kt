package com.v2ray.ang.control

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ControlledUser(
    val id: String,
    val label: String,
    val enabled: Boolean,
    val expiresAt: String?,
    val trafficLimitGb: Double,
    val trafficLimitMb: Long,
    val trafficUsedBytes: Long,
    val trafficUsedGb: Double,
    val trafficUsedMb: Double,
    val trafficExceeded: Boolean,
    val trafficRemainingGb: Double?,
    val trafficRemainingMb: Double?,
    val deviceLimit: Int,
)

data class ControlledNode(
    val id: String,
    val name: String,
    val uri: String,
    val host: String?,
    val countryCode: String?,
    val countryName: String?,
    val flagEmoji: String?,
)

data class ControlledLoginResult(
    val token: String,
    val user: ControlledUser,
)

data class ControlledNodesResult(
    val user: ControlledUser,
    val nodes: List<ControlledNode>,
    val subscriptionText: String,
    val generatedAt: String?,
)

data class ControlledReportResult(
    val ok: Boolean,
    val allowed: Boolean,
    val user: ControlledUser?,
)

class ControlledApiException(
    val statusCode: Int,
    val error: String,
    val user: ControlledUser?,
) : IOException("HTTP $statusCode: $error")

class ControlledApi(private val baseUrl: String) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun login(licenseCode: String, deviceId: String, appVersion: String): ControlledLoginResult {
        val body = JSONObject()
            .put("licenseCode", licenseCode)
            .put("deviceId", deviceId)
            .put("appVersion", appVersion)
            .toString()
            .toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(endpoint("/api/client/login"))
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw apiException(text, response.code)
            }
            val json = JSONObject(text)
            return ControlledLoginResult(
                token = json.getString("token"),
                user = parseUser(json.getJSONObject("user")),
            )
        }
    }

    fun nodes(token: String): ControlledNodesResult {
        val request = Request.Builder()
            .url(endpoint("/api/client/nodes"))
            .get()
            .header("Authorization", "Bearer $token")
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw apiException(text, response.code)
            }
            val json = JSONObject(text)
            val nodeArray = json.optJSONArray("nodes")
            val nodes = mutableListOf<ControlledNode>()
            if (nodeArray != null) {
                for (i in 0 until nodeArray.length()) {
                    val item = nodeArray.getJSONObject(i)
                    nodes.add(
                        ControlledNode(
                            id = item.getString("id"),
                            name = item.optString("name"),
                            uri = item.getString("uri"),
                            host = item.optString("host").ifBlank { null },
                            countryCode = item.optString("countryCode").ifBlank { null },
                            countryName = item.optString("countryName").ifBlank { null },
                            flagEmoji = item.optString("flagEmoji").ifBlank { null },
                        )
                    )
                }
            }
            return ControlledNodesResult(
                user = parseUser(json.getJSONObject("user")),
                nodes = nodes,
                subscriptionText = json.optString("subscriptionText"),
                generatedAt = json.optString("generatedAt").ifBlank { null },
            )
        }
    }

    fun report(token: String, txBytes: Long, rxBytes: Long, nodeId: String?): ControlledReportResult {
        val body = JSONObject()
            .put("event", if (txBytes + rxBytes > 0) "traffic" else "heartbeat")
            .put("txBytes", txBytes)
            .put("rxBytes", rxBytes)
            .put("nodeId", nodeId.orEmpty())
            .toString()
            .toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(endpoint("/api/client/report"))
            .post(body)
            .header("Authorization", "Bearer $token")
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw apiException(text, response.code)
            }
            val json = JSONObject(text)
            return ControlledReportResult(
                ok = json.optBoolean("ok", false),
                allowed = json.optBoolean("allowed", true),
                user = json.optJSONObject("user")?.let { parseUser(it) },
            )
        }
    }

    private fun endpoint(path: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        return "$normalized$path"
    }

    private fun parseUser(json: JSONObject): ControlledUser =
        ControlledUser(
            id = json.getString("id"),
            label = json.optString("label"),
            enabled = json.optBoolean("enabled", false),
            expiresAt = json.optString("expiresAt").ifBlank { null },
            trafficLimitGb = if (json.has("trafficLimitGb")) json.optDouble("trafficLimitGb", 0.0) else json.optLong("trafficLimitMb", 0L) / 1024.0,
            trafficLimitMb = json.optLong("trafficLimitMb", 0L),
            trafficUsedBytes = json.optLong("trafficUsedBytes", 0L),
            trafficUsedGb = if (json.has("trafficUsedGb")) json.optDouble("trafficUsedGb", 0.0) else json.optDouble("trafficUsedMb", 0.0) / 1024.0,
            trafficUsedMb = json.optDouble("trafficUsedMb", 0.0),
            trafficExceeded = json.optBoolean("trafficExceeded", false),
            trafficRemainingGb = if (json.isNull("trafficRemainingGb")) null else json.optDouble("trafficRemainingGb", 0.0),
            trafficRemainingMb = if (json.isNull("trafficRemainingMb")) null else json.optDouble("trafficRemainingMb", 0.0),
            deviceLimit = json.optInt("deviceLimit", 1),
        )

    private fun apiException(text: String, code: Int): ControlledApiException {
        return try {
            val json = JSONObject(text)
            ControlledApiException(
                statusCode = code,
                error = json.optString("error", text),
                user = json.optJSONObject("user")?.let { parseUser(it) },
            )
        } catch (_: Exception) {
            ControlledApiException(code, text, null)
        }
    }
}
