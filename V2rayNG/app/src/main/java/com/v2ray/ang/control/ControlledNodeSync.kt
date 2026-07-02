package com.v2ray.ang.control

import android.content.Context
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager

object ControlledNodeSync {
    const val CONTROLLED_SUBSCRIPTION_ID = "controlled_backend"

    fun sync(context: Context, result: ControlledNodesResult): Int {
        val oldServerIds = MmkvManager.decodeServerList(CONTROLLED_SUBSCRIPTION_ID)
        val oldSelectedServer = MmkvManager.getSelectServer()
        val oldNodes = ControlledSession.getNodes(context)
        val selectedNodeIndex = oldServerIds.indexOf(oldSelectedServer).takeIf { it >= 0 }
        val selectedNodeId = selectedNodeIndex?.let { oldNodes.getOrNull(it)?.id }

        ControlledSession.saveNodes(context, result.nodes)
        MmkvManager.encodeSubscription(
            CONTROLLED_SUBSCRIPTION_ID,
            SubscriptionItem(
                remarks = "后台授权节点",
                url = "",
                enabled = true,
                lastUpdated = System.currentTimeMillis(),
                autoUpdate = false,
            )
        )

        MmkvManager.removeServerViaSubid(CONTROLLED_SUBSCRIPTION_ID)
        val content = result.subscriptionText.trim()
        if (content.isBlank()) {
            ControlledSession.markSynced(context)
            return 0
        }

        val (count, _) = AngConfigManager.importBatchConfig(content, CONTROLLED_SUBSCRIPTION_ID, true)
        val controlledServers = MmkvManager.decodeServerList(CONTROLLED_SUBSCRIPTION_ID)
        val selectedServer = MmkvManager.getSelectServer()
        val restoredIndex = result.nodes.indexOfFirst { it.id == selectedNodeId }.takeIf { it >= 0 }
        val restoredServer = restoredIndex?.let { controlledServers.getOrNull(it) }
        when {
            restoredServer != null -> MmkvManager.setSelectServer(restoredServer)
            (selectedServer.isNullOrBlank() || !controlledServers.contains(selectedServer)) && controlledServers.isNotEmpty() -> {
                MmkvManager.setSelectServer(controlledServers.first())
            }
        }
        ControlledSession.markSynced(context)
        return count
    }

    fun clear(context: Context) {
        MmkvManager.removeServerViaSubid(CONTROLLED_SUBSCRIPTION_ID)
        ControlledSession.saveNodes(context, emptyList())
        ControlledSession.markSynced(context)
    }
}
