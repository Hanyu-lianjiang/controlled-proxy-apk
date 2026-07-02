package com.v2ray.ang.control

import android.content.Context
import android.net.TrafficStats
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

object ControlledTrafficReporter {
    private const val REPORT_INTERVAL_MS = 5_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reportMutex = Mutex()
    private var reportJob: Job? = null

    fun captureBaseline(context: Context) {
        val appContext = context.applicationContext
        currentAppTrafficCounters(appContext)?.let { (txBytes, rxBytes) ->
            ControlledSession.saveAppTrafficCounters(appContext, txBytes, rxBytes)
        } ?: ControlledSession.clearAppTrafficCounters(appContext)
    }

    fun start(context: Context) {
        val appContext = context.applicationContext
        if (!ControlledSession.hasToken(appContext) || reportJob?.isActive == true) return

        val (lastTxBytes, lastRxBytes) = ControlledSession.appTrafficCounters(appContext)
        if (lastTxBytes < 0L || lastRxBytes < 0L) {
            captureBaseline(appContext)
        }

        reportJob = scope.launch {
            reportOnce(appContext)
            while (isActive) {
                delay(REPORT_INTERVAL_MS)
                reportOnce(appContext)
            }
        }
    }

    fun stop(context: Context, reportFinal: Boolean = false) {
        val appContext = context.applicationContext
        reportJob?.cancel()
        reportJob = null
        if (reportFinal && ControlledSession.hasToken(appContext)) {
            scope.launch {
                reportOnce(appContext)
            }
        }
    }

    private suspend fun reportOnce(context: Context) {
        if (!reportMutex.tryLock()) return
        try {
            val baseUrl = ControlledSession.getBaseUrl(context)
            val token = ControlledSession.getToken(context)
            if (baseUrl.isBlank() || token.isBlank()) return

            val (txBytes, rxBytes) = collectTrafficDelta(context)
            val result = ControlledApi(baseUrl).report(token, txBytes, rxBytes, MmkvManager.getSelectServer())
            result.user?.let { ControlledSession.saveAuth(context, baseUrl, token, it) }

            if (!result.allowed) {
                ControlledNodeSync.clear(context)
                CoreServiceManager.stopVService(context)
            }
        } catch (_: ControlledApiException) {
            ControlledNodeSync.clear(context)
            CoreServiceManager.stopVService(context)
        } catch (_: Exception) {
            // Keep the tunnel running during transient network failures.
        } finally {
            reportMutex.unlock()
        }
    }

    private fun collectTrafficDelta(context: Context): Pair<Long, Long> {
        val appDelta = collectAppTrafficDelta(context)
        if (appDelta != null && appDelta.first + appDelta.second > 0L) {
            return appDelta
        }
        val coreDelta = collectCoreTrafficDelta()
        if (coreDelta.first + coreDelta.second > 0L) {
            return coreDelta
        }
        return appDelta ?: (0L to 0L)
    }

    private fun collectAppTrafficDelta(context: Context): Pair<Long, Long>? {
        val current = currentAppTrafficCounters(context) ?: return null
        val (lastTxBytes, lastRxBytes) = ControlledSession.appTrafficCounters(context)
        ControlledSession.saveAppTrafficCounters(context, current.first, current.second)
        if (lastTxBytes < 0L || lastRxBytes < 0L || current.first < lastTxBytes || current.second < lastRxBytes) {
            return 0L to 0L
        }
        return (current.first - lastTxBytes) to (current.second - lastRxBytes)
    }

    private fun currentAppTrafficCounters(context: Context): Pair<Long, Long>? {
        val unsupported = TrafficStats.UNSUPPORTED.toLong()
        val txBytes = TrafficStats.getUidTxBytes(context.applicationInfo.uid)
        val rxBytes = TrafficStats.getUidRxBytes(context.applicationInfo.uid)
        if (txBytes == unsupported || rxBytes == unsupported) return null
        return txBytes to rxBytes
    }

    private fun collectCoreTrafficDelta(): Pair<Long, Long> {
        var txBytes = 0L
        var rxBytes = 0L
        CoreServiceManager.queryAllOutboundTrafficStats().forEach { stat ->
            if (!stat.tag.startsWith(AppConfig.TAG_PROXY)) return@forEach
            when (stat.direction) {
                AppConfig.UPLINK -> txBytes += stat.value
                AppConfig.DOWNLINK -> rxBytes += stat.value
            }
        }
        return txBytes to rxBytes
    }
}
