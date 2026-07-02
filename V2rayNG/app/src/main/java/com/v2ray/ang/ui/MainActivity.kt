package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.control.ControlledApi
import com.v2ray.ang.control.ControlledApiException
import com.v2ray.ang.control.ControlledNode
import com.v2ray.ang.control.ControlledNodeSync
import com.v2ray.ang.control.ControlledSession
import com.v2ray.ang.control.ControlledTrafficReporter
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import kotlin.system.measureTimeMillis

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private enum class DashboardTab {
        PROXY,
        NODES,
        MINE,
    }

    private sealed class TcpTestState {
        data object Testing : TcpTestState()
        data class Success(val delayMillis: Long) : TcpTestState()
        data object Failed : TcpTestState()
    }

    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null
    private var dashboardRefreshJob: Job? = null
    private var tcpTestJob: Job? = null
    private val tcpTestResults = mutableMapOf<String, TcpTestState>()
    private val tcpResultViews = mutableMapOf<String, TextView>()
    private var lastRemoteTrafficRefreshAt = 0L
    private var lastNodeRenderSignature = ""
    private var activeDashboardTab = DashboardTab.PROXY
    private var pendingAppUpdate: CheckUpdateResult? = null

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        } else {
            restoreConnectState()
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
        mainViewModel.reloadServerList()
        refreshSimpleDashboard()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.controlled_home_title))

        // setup viewpager and tablayout
        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

        // setup navigation drawer
        setupNavigationDrawer()

        binding.fab.setOnClickListener { handlePrimaryConnectClick() }
        binding.btnPrimaryConnect.setOnClickListener { handlePrimaryConnectClick() }
        binding.btnQuickSync.setOnClickListener { syncAuthorizedNodes(showToast = true) }
        binding.btnTestTcp.setOnClickListener { testAuthorizedNodeTcpConnections() }
        binding.btnQuickAccount.setOnClickListener {
            requestActivityLauncher.launch(Intent(this, ControlledActivity::class.java))
        }
        binding.btnCheckUpdate.setOnClickListener { handleUpdateClick() }
        binding.navProxy.setOnClickListener { showDashboardTab(DashboardTab.PROXY) }
        binding.navNodes.setOnClickListener { showDashboardTab(DashboardTab.NODES) }
        binding.navMine.setOnClickListener { showDashboardTab(DashboardTab.MINE) }
        binding.viewConnectHalo.startAnimation(AnimationUtils.loadAnimation(this, R.anim.controlled_connect_pulse))
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }
        setupMotionFeedback()

        setupGroupTab()
        setupViewModel()
        SubscriptionUpdater.sync()
        mainViewModel.reloadServerList()
        showDashboardTab(DashboardTab.PROXY)
        refreshSimpleDashboard()
        refreshUpdateButton()
        checkForAppUpdate(auto = true)

        if (!ControlledSession.hasToken(this)) {
            requestActivityLauncher.launch(Intent(this, ControlledActivity::class.java))
        }

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }

    private fun setupNavigationDrawer() {
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        binding.navView.isVisible = false
        binding.navView.setNavigationItemSelectedListener(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            applyRunningState(false, isRunning)
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this)
        groupPagerAdapter.update(groups)

        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
            groupPagerAdapter.groups.getOrNull(position)?.let {
                tab.text = it.remarks
                tab.tag = it.id
            }
        }.also { it.attach() }

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (groups.size - 1)
        binding.viewPager.setCurrentItem(targetIndex, false)

        binding.tabGroup.isVisible = groups.size > 1
        refreshGroupTabTitles(true)
    }

    fun refreshGroupTabTitles(refreshAll: Boolean = false) {
        val groupsToRefresh = if (refreshAll || mainViewModel.subscriptionId.isEmpty()) {
            groupPagerAdapter.groups
        } else {
            groupPagerAdapter.groups.filter { it.id == mainViewModel.subscriptionId }
        }

        groupsToRefresh.forEach { group ->
            if (group.id.isEmpty()) {
                return@forEach
            }
            val tabIndex = groupPagerAdapter.groups.indexOfFirst { it.id == group.id }
            if (tabIndex >= 0) {
                val count = MmkvManager.decodeServerList(group.id).size
                binding.tabGroup.getTabAt(tabIndex)?.text = "${group.remarks} ($count)"
            }
        }
    }

    private fun handleFabAction() {
        handlePrimaryConnectClick()
    }

    private fun handlePrimaryConnectClick() {
        applyRunningState(isLoading = true, isRunning = false)

        if (mainViewModel.isRunning.value == true) {
            stopControlledServiceWithFinalReport()
        } else {
            prepareControlledStart()
        }
    }

    private fun stopControlledServiceWithFinalReport() {
        ControlledTrafficReporter.stop(this, reportFinal = true)
        CoreServiceManager.stopVService(this)
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        } else {
            // service not running: keep existing no-op (could show a message if desired)
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            restoreConnectState()
            toast(R.string.title_file_chooser)
            return
        }
        ControlledTrafficReporter.captureBaseline(this)
        CoreServiceManager.startVService(this)
        lifecycleScope.launch {
            delay(5_000)
            if (mainViewModel.isRunning.value != true) {
                restoreConnectState()
            }
        }
    }

    private fun prepareControlledStart() {
        if (!ControlledSession.hasToken(this)) {
            applyRunningState(isLoading = false, isRunning = false)
            requestActivityLauncher.launch(Intent(this, ControlledActivity::class.java))
            return
        }

        syncAuthorizedNodes(showToast = false) { importedCount ->
            if (importedCount <= 0) {
                applyRunningState(isLoading = false, isRunning = false)
                toast(R.string.controlled_home_no_authorized_nodes)
                return@syncAuthorizedNodes
            }

            if (SettingsManager.isVpnMode()) {
                val intent = VpnService.prepare(this)
                if (intent == null) {
                    startV2Ray()
                } else {
                    requestVpnPermission.launch(intent)
                }
            } else {
                startV2Ray()
            }
        }
    }

    private fun syncAuthorizedNodes(showToast: Boolean, onReady: ((Int) -> Unit)? = null) {
        val baseUrl = ControlledSession.getBaseUrl(this)
        val token = ControlledSession.getToken(this)
        if (baseUrl.isBlank() || token.isBlank()) {
            if (showToast) toast(R.string.controlled_login_first)
            requestActivityLauncher.launch(Intent(this, ControlledActivity::class.java))
            return
        }

        showLoading()
        if (!showToast) {
            binding.tvConnectionState.text = getString(R.string.controlled_home_checking)
        }
        lifecycleScope.launch {
            try {
                val nodesResult = withContext(Dispatchers.IO) {
                    ControlledApi(baseUrl).nodes(token)
                }
                val importedCount = withContext(Dispatchers.IO) {
                    ControlledNodeSync.sync(this@MainActivity, nodesResult)
                }
                ControlledSession.saveAuth(this@MainActivity, baseUrl, token, nodesResult.user)
                mainViewModel.subscriptionIdChanged(ControlledNodeSync.CONTROLLED_SUBSCRIPTION_ID)
                setupGroupTab()
                mainViewModel.reloadServerList()
                refreshSimpleDashboard()
                if (showToast) toast(getString(R.string.controlled_sync_success, importedCount))
                onReady?.invoke(importedCount)
                if (showToast) restoreConnectState()
            } catch (e: ControlledApiException) {
                handleControlledAccessDenied(e)
            } catch (e: Exception) {
                applyRunningState(isLoading = false, isRunning = mainViewModel.isRunning.value == true)
                refreshSimpleDashboard()
                toastError("${getString(R.string.controlled_sync_failed)}: ${e.message.orEmpty()}")
            } finally {
                hideLoading()
            }
        }
    }

    private fun handleControlledAccessDenied(e: ControlledApiException) {
        val baseUrl = ControlledSession.getBaseUrl(this)
        val token = ControlledSession.getToken(this)
        e.user?.let { ControlledSession.saveAuth(this, baseUrl, token, it) }
        ControlledNodeSync.clear(this)
        CoreServiceManager.stopVService(this)
        mainViewModel.reloadServerList()
        setupGroupTab()
        applyRunningState(isLoading = false, isRunning = false)
        refreshSimpleDashboard()
        toastError(getString(R.string.controlled_home_access_denied))
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    private fun restoreConnectState() {
        applyRunningState(isLoading = false, isRunning = mainViewModel.isRunning.value == true)
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (isLoading) {
            binding.fab.setImageResource(R.drawable.ic_fab_check)
            binding.btnPrimaryConnect.text = ""
            binding.tvConnectionState.text = getString(R.string.controlled_home_checking)
            return
        }

        if (isRunning) {
            binding.panelConnectionStatus.setBackgroundResource(R.drawable.bg_controlled_ios_card)
            binding.viewConnectHalo.setBackgroundResource(R.drawable.bg_controlled_connect_halo_active)
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.fab.contentDescription = getString(R.string.action_stop_service)
            setTestState(getString(R.string.connection_connected))
            binding.layoutTest.isFocusable = true
            binding.btnPrimaryConnect.text = ""
            binding.btnPrimaryConnect.setIconResource(R.drawable.ic_stop_24dp)
            binding.btnPrimaryConnect.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.controlled_connect_active))
            binding.tvConnectionState.text = getString(R.string.controlled_home_connected)
            binding.tvConnectionSubtitle.text = getString(R.string.controlled_home_running)
        } else {
            binding.panelConnectionStatus.setBackgroundResource(R.drawable.bg_controlled_ios_card)
            binding.viewConnectHalo.setBackgroundResource(R.drawable.bg_controlled_connect_halo)
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.connection_not_connected))
            binding.layoutTest.isFocusable = false
            binding.btnPrimaryConnect.text = ""
            binding.btnPrimaryConnect.setIconResource(R.drawable.ic_play_24dp)
            binding.btnPrimaryConnect.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.controlled_connect))
            binding.tvConnectionState.text = getString(R.string.controlled_home_disconnected)
            binding.tvConnectionSubtitle.text = getString(R.string.controlled_home_tap_to_start)
        }
        binding.viewConnectHalo.startAnimation(AnimationUtils.loadAnimation(this, R.anim.controlled_connect_pulse))
        refreshSimpleDashboard()
    }

    private fun refreshSimpleDashboard() {
        val controlledServers = MmkvManager.decodeServerList(ControlledNodeSync.CONTROLLED_SUBSCRIPTION_ID)
        val controlledNodes = ControlledSession.getNodes(this)
        val selectedGuid = MmkvManager.getSelectServer()

        binding.tvAccountState.text = accountBadgeText()
        refreshTrafficUsage()
        binding.tvProxyCurrentNode.text = selectedNodeLabel(controlledServers, controlledNodes, selectedGuid)
        renderNodePage(controlledServers, controlledNodes, selectedGuid)
        refreshMinePage()
    }

    private fun accountBadgeText(): String {
        if (!ControlledSession.hasToken(this)) return getString(R.string.controlled_not_logged_in)
        return getString(R.string.controlled_home_authorized)
    }

    private fun refreshTrafficUsage() {
        binding.tvTrafficUsage.text = trafficUsageText()
        binding.progressTraffic.max = 1000
        binding.progressTraffic.progress = trafficProgress()
        binding.progressMineTraffic.max = 1000
        binding.progressMineTraffic.progress = binding.progressTraffic.progress
    }

    private fun trafficUsageText(): String {
        if (!ControlledSession.hasToken(this)) return getString(R.string.controlled_not_logged_in)
        val limitGb = ControlledSession.trafficLimitGb(this)
        val usedGb = ControlledSession.trafficUsedGb(this)
        val remainingGb = ControlledSession.trafficRemainingGb(this)
        return if (limitGb > 0.0 && remainingGb != null) {
            "${formatTrafficGb(remainingGb)} GB / ${formatTrafficGb(limitGb)} GB"
        } else {
            "${formatTrafficGb(usedGb)} GB / ${getString(R.string.controlled_home_unlimited)}"
        }
    }

    private fun trafficProgress(): Int {
        val limitGb = ControlledSession.trafficLimitGb(this)
        if (limitGb <= 0.0) return 0
        return ((ControlledSession.trafficUsedGb(this) / limitGb) * 1000).toInt().coerceIn(0, 1000)
    }

    private fun formatTrafficGb(value: Double): String =
        ControlledSession.formatGb(value)

    private fun startDashboardAutoRefresh() {
        if (dashboardRefreshJob?.isActive == true) return
        dashboardRefreshJob = lifecycleScope.launch {
            while (isActive) {
                binding.tvAccountState.text = accountBadgeText()
                refreshTrafficUsage()
                refreshMinePage()
                val now = System.currentTimeMillis()
                if (mainViewModel.isRunning.value == true && now - lastRemoteTrafficRefreshAt >= 3_000L) {
                    lastRemoteTrafficRefreshAt = now
                    refreshTrafficStatusFromBackend()
                    binding.tvAccountState.text = accountBadgeText()
                    refreshTrafficUsage()
                    refreshMinePage()
                }
                delay(1_000)
            }
        }
    }

    private suspend fun refreshTrafficStatusFromBackend() {
        val baseUrl = ControlledSession.getBaseUrl(this)
        val token = ControlledSession.getToken(this)
        if (baseUrl.isBlank() || token.isBlank()) return

        try {
            val result = withContext(Dispatchers.IO) {
                ControlledApi(baseUrl).report(token, 0L, 0L, MmkvManager.getSelectServer())
            }
            result.user?.let { ControlledSession.saveAuth(this, baseUrl, token, it) }
            if (!result.allowed) {
                ControlledNodeSync.clear(this)
                CoreServiceManager.stopVService(this)
                mainViewModel.reloadServerList()
                setupGroupTab()
                applyRunningState(isLoading = false, isRunning = false)
                toastError(getString(R.string.controlled_home_access_denied))
            }
        } catch (e: ControlledApiException) {
            handleControlledAccessDenied(e)
        } catch (_: Exception) {
            // Keep the visible dashboard on cached values during transient network failures.
        }
    }

    private fun stopDashboardAutoRefresh() {
        dashboardRefreshJob?.cancel()
        dashboardRefreshJob = null
    }

    private fun showDashboardTab(tab: DashboardTab) {
        val previousTab = activeDashboardTab
        activeDashboardTab = tab
        binding.pageProxy.isVisible = tab == DashboardTab.PROXY
        binding.pageNodes.isVisible = tab == DashboardTab.NODES
        binding.pageMine.isVisible = tab == DashboardTab.MINE

        styleBottomNav(binding.navProxy, tab == DashboardTab.PROXY)
        styleBottomNav(binding.navNodes, tab == DashboardTab.NODES)
        styleBottomNav(binding.navMine, tab == DashboardTab.MINE)
        refreshSimpleDashboard()
        if (previousTab != tab) {
            animatePageIn(
                when (tab) {
                    DashboardTab.PROXY -> binding.pageProxy
                    DashboardTab.NODES -> binding.pageNodes
                    DashboardTab.MINE -> binding.pageMine
                }
            )
        }
    }

    private fun styleBottomNav(item: TextView, selected: Boolean) {
        item.setTextColor(ContextCompat.getColor(this, if (selected) R.color.controlled_connect else R.color.controlled_text_muted))
        item.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        item.setBackgroundResource(if (selected) R.drawable.bg_controlled_nav_selected else R.drawable.bg_controlled_node_unselected)
        item.animate()
            .scaleX(if (selected) 1.02f else 1f)
            .scaleY(if (selected) 1.02f else 1f)
            .setDuration(140L)
            .start()
    }

    private fun renderNodePage(serverIds: List<String>, nodes: List<ControlledNode>, selectedGuid: String?) {
        val signature = buildNodeRenderSignature(serverIds, nodes, selectedGuid)
        binding.tvNodePageSelected.text = selectedNodeLabel(serverIds, nodes, selectedGuid)
        if (signature == lastNodeRenderSignature) {
            updateTcpResultViews()
            return
        }
        lastNodeRenderSignature = signature
        binding.llNodeList.removeAllViews()
        tcpResultViews.clear()
        if (serverIds.isEmpty()) {
            binding.llNodeList.addView(simpleNodeText(getString(R.string.controlled_home_no_line), muted = true))
            return
        }

        serverIds.forEachIndexed { index, guid ->
            val node = nodes.getOrNull(index)
            val profile = MmkvManager.decodeServerConfig(guid)
            val selected = guid == selectedGuid
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(72)
                setPadding(dp(16), dp(13), dp(16), dp(13))
                background = ContextCompat.getDrawable(
                    this@MainActivity,
                    if (selected) R.drawable.bg_controlled_node_selected else R.drawable.bg_controlled_surface_panel
                )
                elevation = if (selected) dp(4).toFloat() else dp(2).toFloat()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(12)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    animateTap(this)
                    selectControlledNode(guid)
                }
            }

            val flag = TextView(this).apply {
                text = node?.flagEmoji?.takeIf { it.isNotBlank() } ?: "🌐"
                textSize = 29f
                gravity = Gravity.CENTER
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(dp(46), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(8)
                }
            }
            val textColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val title = TextView(this).apply {
                text = nodeDisplayName(node, profile?.remarks, index)
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.controlled_ink))
            }
            val tcpState = TextView(this).apply {
                text = tcpTestText(guid)
                textSize = 12f
                gravity = Gravity.CENTER
                isVisible = text.isNotBlank()
                includeFontPadding = false
                setTextColor(ContextCompat.getColor(this@MainActivity, tcpTestTextColor(guid)))
                layoutParams = LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(8)
                }
            }

            textColumn.addView(title)
            row.addView(flag)
            row.addView(textColumn)
            row.addView(tcpState)
            tcpResultViews[guid] = tcpState
            binding.llNodeList.addView(row)
            if (activeDashboardTab == DashboardTab.NODES) {
                row.alpha = 0f
                row.translationY = dp(10).toFloat()
                row.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay((index * 24L).coerceAtMost(120L))
                    .setDuration(180L)
                    .start()
            }
        }
    }

    private fun buildNodeRenderSignature(
        serverIds: List<String>,
        nodes: List<ControlledNode>,
        selectedGuid: String?,
    ): String = buildString {
        append(selectedGuid.orEmpty())
        serverIds.forEachIndexed { index, guid ->
            val node = nodes.getOrNull(index)
            append('|')
                .append(guid)
                .append(':')
                .append(node?.id.orEmpty())
                .append(':')
                .append(node?.name.orEmpty())
                .append(':')
                .append(node?.flagEmoji.orEmpty())
        }
    }

    private fun updateTcpResultViews() {
        tcpResultViews.forEach { (guid, view) ->
            view.text = tcpTestText(guid)
            view.isVisible = view.text.isNotBlank()
            view.setTextColor(ContextCompat.getColor(this, tcpTestTextColor(guid)))
        }
    }

    private fun testAuthorizedNodeTcpConnections() {
        val serverIds = MmkvManager.decodeServerList(ControlledNodeSync.CONTROLLED_SUBSCRIPTION_ID)
        if (serverIds.isEmpty()) {
            toast(R.string.controlled_nodes_test_no_nodes)
            return
        }
        if (tcpTestJob?.isActive == true) return

        val targets = serverIds.map { guid ->
            val profile = MmkvManager.decodeServerConfig(guid)
            Triple(guid, profile?.server, profile?.serverPort)
        }

        tcpTestResults.clear()
        targets.forEach { (guid, _, _) -> tcpTestResults[guid] = TcpTestState.Testing }
        refreshSimpleDashboard()

        tcpTestJob = lifecycleScope.launch {
            binding.btnTestTcp.isEnabled = false
            try {
                val results = targets.map { (guid, host, port) ->
                    async(Dispatchers.IO) {
                        guid to testTcpConnection(host, port)
                    }
                }.awaitAll()
                results.forEach { (guid, result) -> tcpTestResults[guid] = result }
                refreshSimpleDashboard()
                toast(R.string.controlled_nodes_test_done)
            } finally {
                binding.btnTestTcp.isEnabled = true
            }
        }
    }

    private fun testTcpConnection(host: String?, portText: String?): TcpTestState {
        val normalizedHost = host?.trim()?.takeIf { it.isNotBlank() } ?: return TcpTestState.Failed
        val port = portText?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return TcpTestState.Failed
        return try {
            val elapsed = measureTimeMillis {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(normalizedHost, port), 3_000)
                }
            }
            TcpTestState.Success(elapsed)
        } catch (_: Exception) {
            TcpTestState.Failed
        }
    }

    private fun tcpTestText(guid: String): String =
        when (val state = tcpTestResults[guid]) {
            TcpTestState.Testing -> getString(R.string.controlled_nodes_test_testing)
            is TcpTestState.Success -> getString(R.string.controlled_nodes_test_success, state.delayMillis)
            TcpTestState.Failed -> getString(R.string.controlled_nodes_test_failed)
            null -> ""
        }

    private fun tcpTestTextColor(guid: String): Int =
        when (tcpTestResults[guid]) {
            is TcpTestState.Success -> R.color.controlled_connect
            TcpTestState.Failed -> R.color.controlled_danger
            else -> R.color.controlled_text_muted
        }

    private fun selectedNodeLabel(
        serverIds: List<String>,
        nodes: List<com.v2ray.ang.control.ControlledNode>,
        selectedGuid: String?,
    ): String {
        val selectedLabel = selectedNodeDisplayName(serverIds, nodes, selectedGuid)
        return when {
            selectedLabel != null && serverIds.isNotEmpty() -> getString(R.string.controlled_nodes_selected_format, selectedLabel)
            serverIds.isNotEmpty() -> getString(R.string.controlled_nodes_selected_format, getString(R.string.controlled_home_current))
            else -> getString(R.string.controlled_home_no_line)
        }
    }

    private fun selectedNodeDisplayName(
        serverIds: List<String>,
        nodes: List<com.v2ray.ang.control.ControlledNode>,
        selectedGuid: String?,
    ): String? {
        val selectedIndex = serverIds.indexOf(selectedGuid)
        val selectedNode = nodes.getOrNull(selectedIndex)
        val selectedProfileName = selectedGuid?.let { MmkvManager.decodeServerConfig(it)?.remarks }?.takeIf { it.isNotBlank() }
        return selectedNode
            ?.let { node ->
                val name = nodeDisplayName(node, selectedProfileName, selectedIndex)
                node.flagEmoji?.takeIf { it.isNotBlank() }?.let { flag -> "$flag $name" } ?: name
            }
            ?: selectedProfileName
    }

    private fun nodeDisplayName(node: ControlledNode?, fallback: String?, index: Int): String =
        node?.name
            ?.takeIf { it.isNotBlank() && !looksLikeHost(it) }
            ?: fallback?.takeIf { it.isNotBlank() && !looksLikeHost(it) }
            ?: node?.countryName?.takeIf { it.isNotBlank() }
            ?: "线路 ${index + 1}"

    private fun handleUpdateClick() {
        pendingAppUpdate?.takeIf { it.hasUpdate }?.let {
            showAppUpdateDialog(it)
            return
        }
        checkForAppUpdate(auto = false)
    }

    private fun checkForAppUpdate(auto: Boolean) {
        if (!auto) {
            toast(R.string.update_checking_for_update)
            showLoading()
        }
        lifecycleScope.launch {
            try {
                val result = UpdateCheckerManager.checkForUpdate(includePreRelease = false)
                pendingAppUpdate = result.takeIf { it.hasUpdate }
                refreshUpdateButton()
                if (auto) return@launch
                if (result.hasUpdate) {
                    showAppUpdateDialog(result)
                } else {
                    toast(R.string.update_already_latest_version)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to check app update", e)
                if (!auto) {
                    toastError(e.message ?: getString(R.string.toast_failure))
                }
            } finally {
                if (!auto) {
                    hideLoading()
                }
            }
        }
    }

    private fun refreshUpdateButton() {
        binding.btnCheckUpdate.text = getString(
            if (pendingAppUpdate?.hasUpdate == true) {
                R.string.controlled_mine_online_update
            } else {
                R.string.controlled_mine_check_update
            }
        )
    }

    private fun showAppUpdateDialog(result: CheckUpdateResult) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_new_version_found, result.latestVersion))
            .setMessage(result.releaseNotes?.takeIf { it.isNotBlank() } ?: getString(R.string.update_now))
            .setPositiveButton(R.string.update_now) { _, _ ->
                val url = result.downloadUrl
                if (url.isNullOrBlank()) {
                    toastError(R.string.update_download_failed)
                } else {
                    downloadAndInstallUpdate(url, result.latestVersion.orEmpty())
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadAndInstallUpdate(downloadUrl: String, version: String) {
        showLoading()
        lifecycleScope.launch {
            try {
                val apkFile = withContext(Dispatchers.IO) {
                    val updateDir = File(cacheDir, "updates").apply { mkdirs() }
                    val target = File(updateDir, "internal-vpn-${version.ifBlank { "latest" }}.apk")
                    val direct = HttpUtil.downloadToFile(
                        UrlContentRequest(url = downloadUrl, timeout = 30_000),
                        target
                    )
                    val downloaded = if (direct) {
                        true
                    } else {
                        HttpUtil.downloadToFile(
                            UrlContentRequest(
                                url = downloadUrl,
                                timeout = 30_000,
                                httpPort = SettingsManager.getHttpPort(),
                                proxyUsername = SettingsManager.getSocksUsername(),
                                proxyPassword = SettingsManager.getSocksPassword()
                            ),
                            target
                        )
                    }
                    if (downloaded && target.length() > 0L) target else null
                }
                if (apkFile == null) {
                    toastError(R.string.update_download_failed)
                } else {
                    toast(R.string.update_download_success)
                    installDownloadedApk(apkFile)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to download app update", e)
                toastError(e.message ?: getString(R.string.update_download_failed))
            } finally {
                hideLoading()
            }
        }
    }

    private fun installDownloadedApk(apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            this,
            "${BuildConfig.APPLICATION_ID}.cache",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to open app installer", e)
            toastError(e.message ?: getString(R.string.toast_failure))
        }
    }

    private fun refreshMinePage() {
        val loggedIn = ControlledSession.hasToken(this)
        val label = ControlledSession.userLabel(this).ifBlank {
            if (loggedIn) getString(R.string.controlled_home_account) else getString(R.string.controlled_not_logged_in)
        }
        val username = ControlledSession.username(this).ifBlank { label }
        val serverIds = MmkvManager.decodeServerList(ControlledNodeSync.CONTROLLED_SUBSCRIPTION_ID)
        val nodes = ControlledSession.getNodes(this)
        val selectedGuid = MmkvManager.getSelectServer()
        val expiresAt = ControlledSession.expiresAtDisplay(this).ifBlank { getString(R.string.controlled_home_unlimited) }
        binding.tvMineUser.text = label
        binding.tvMineAvatar.text = avatarInitial(username)
        binding.tvMineStatus.text = if (loggedIn) {
            getString(R.string.controlled_home_authorized)
        } else {
            getString(R.string.controlled_not_logged_in)
        }
        binding.tvMineStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (loggedIn) R.color.controlled_connected_start else R.color.controlled_text_muted
            )
        )
        binding.tvMineExpire.text = getString(R.string.controlled_mine_expire_short, expiresAt)
        binding.tvMineTraffic.text = trafficUsageText()
        binding.tvMineDevice.text = selectedNodeDisplayName(serverIds, nodes, selectedGuid)
            ?: getString(R.string.controlled_home_no_line)
        val lastSync = ControlledSession.lastSyncAt(this)
        binding.tvMineLastSync.text =
            if (lastSync > 0L) android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", lastSync).toString() else getString(R.string.controlled_mine_never_sync)
        binding.tvMineVersion.text = BuildConfig.VERSION_NAME
    }

    private fun avatarInitial(value: String): String {
        val text = value.trim()
        if (text.isBlank()) return "I"
        val firstCodePoint = text.codePointAt(0)
        return String(Character.toChars(firstCodePoint)).uppercase(Locale.getDefault())
    }

    private fun looksLikeHost(value: String): Boolean {
        val text = value.trim()
        return Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""").containsMatchIn(text) ||
            Regex("""(?i)\b[a-z0-9-]+(?:\.[a-z0-9-]+)+\b""").containsMatchIn(text)
    }

    private fun simpleNodeText(textValue: String, muted: Boolean): TextView =
        TextView(this).apply {
            text = textValue
            textSize = 14f
            setPadding(0, 8, 0, 8)
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (muted) R.color.md_theme_onSurfaceVariant else R.color.md_theme_onSurface
                )
            )
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMotionFeedback() {
        listOf<View>(
            binding.btnPrimaryConnect,
            binding.btnQuickSync,
            binding.btnTestTcp,
            binding.btnQuickAccount,
            binding.btnCheckUpdate,
            binding.navProxy,
            binding.navNodes,
            binding.navMine,
        ).forEach { view ->
            view.setOnTouchListener { touched, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> touched.animate()
                        .scaleX(0.97f)
                        .scaleY(0.97f)
                        .setDuration(90L)
                        .start()

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> touched.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(140L)
                        .start()
                }
                false
            }
        }
    }

    private fun animatePageIn(view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = dp(12).toFloat()
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(190L)
            .start()
    }

    private fun animateTap(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.985f)
            .scaleY(0.985f)
            .setDuration(60L)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120L)
                    .start()
            }
            .start()
    }

    private fun selectControlledNode(guid: String) {
        if (guid == MmkvManager.getSelectServer()) return
        MmkvManager.setSelectServer(guid)
        mainViewModel.reloadServerList()
        refreshSimpleDashboard()
        if (mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        refreshSimpleDashboard()
        startDashboardAutoRefresh()
    }

    override fun onPause() {
        stopDashboardAutoRefresh()
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode()
            true
        }

        R.id.import_clipboard -> {
            importClipboard()
            true
        }

        R.id.import_local -> {
            importConfigLocal()
            true
        }

        R.id.import_manually_policy_group -> {
            importManually(EConfigType.POLICYGROUP.value)
            true
        }

        R.id.import_manually_proxy_chain -> {
            importManually(EConfigType.PROXYCHAIN.value)
            true
        }

        R.id.import_manually_vmess -> {
            importManually(EConfigType.VMESS.value)
            true
        }

        R.id.import_manually_vless -> {
            importManually(EConfigType.VLESS.value)
            true
        }

        R.id.import_manually_ss -> {
            importManually(EConfigType.SHADOWSOCKS.value)
            true
        }

        R.id.import_manually_socks -> {
            importManually(EConfigType.SOCKS.value)
            true
        }

        R.id.import_manually_http -> {
            importManually(EConfigType.HTTP.value)
            true
        }

        R.id.import_manually_trojan -> {
            importManually(EConfigType.TROJAN.value)
            true
        }

        R.id.import_manually_wireguard -> {
            importManually(EConfigType.WIREGUARD.value)
            true
        }

        R.id.import_manually_hysteria2 -> {
            importManually(EConfigType.HYSTERIA2.value)
            true
        }

        R.id.export_all -> {
            exportAll()
            true
        }

        R.id.real_ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            delAllConfig()
            true
        }

        R.id.del_duplicate_config -> {
            delDuplicateConfig()
            true
        }

        R.id.del_invalid_config -> {
            delInvalidConfig()
            true
        }

        R.id.sort_by_test_results -> {
            sortByTestResults()
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        R.id.locate_selected_config -> {
            locateSelectedServer()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerGroupActivity::class.java)
            )
        } else if (createConfigType == EConfigType.PROXYCHAIN.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerProxyChainActivity::class.java)
            )
        } else {
            startActivity(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerActivity::class.java)
            )
        }
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                            refreshGroupTabTitles()
                        }

                        countSub > 0 -> setupGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    hideLoading()
                }
                LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    fun importConfigViaSub(): Boolean {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (result.successCount + result.failureCount + result.skipCount == 0) {
                    toast(R.string.title_update_subscription_no_subscription)
                } else if (result.successCount > 0 && result.failureCount + result.skipCount == 0) {
                    toast(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    toast(
                        getString(
                            R.string.title_update_subscription_result,
                            result.configCount, result.successCount, result.failureCount, result.skipCount
                        )
                    )
                }
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                    refreshGroupTabTitles()
                }
                hideLoading()
            }
        }
        return true
    }

    private fun exportAll() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                hideLoading()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                hideLoading()
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

    /**
     * Locates and scrolls to the currently selected server.
     * If the selected server is in a different group, automatically switches to that group first.
     */
    private fun locateSelectedServer() {
        val targetSubscriptionId = mainViewModel.findSubscriptionIdBySelect()
        if (targetSubscriptionId.isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }

        val targetGroupIndex = groupPagerAdapter.groups.indexOfFirst { it.id == targetSubscriptionId }
        if (targetGroupIndex < 0) {
            toast(R.string.toast_server_not_found_in_group)
            return
        }

        // Switch to target group if needed, then scroll to the server
        if (binding.viewPager.currentItem != targetGroupIndex) {
            binding.viewPager.setCurrentItem(targetGroupIndex, true)
            binding.viewPager.postDelayed({ scrollToSelectedServer(targetGroupIndex) }, 1000)
        } else {
            scrollToSelectedServer(targetGroupIndex)
        }
    }

    /**
     * Scrolls to the selected server in the specified fragment.
     * @param groupIndex The index of the group/fragment to scroll in
     */
    private fun scrollToSelectedServer(groupIndex: Int) {
        val itemId = groupPagerAdapter.getItemId(groupIndex)
        val fragment = supportFragmentManager.findFragmentByTag("f$itemId") as? GroupServerFragment

        if (fragment?.isAdded == true && fragment.view != null) {
            fragment.scrollToSelectedServer()
        } else {
            toast(R.string.toast_fragment_not_available)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.controlled_backend -> requestActivityLauncher.launch(Intent(this, ControlledActivity::class.java))
            R.id.sub_setting -> requestActivityLauncher.launch(Intent(this, SubSettingActivity::class.java))
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.routing_setting -> requestActivityLauncher.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.user_asset_setting -> requestActivityLauncher.launch(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.promotion -> Utils.openUri(this, "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}")
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.backup_restore -> requestActivityLauncher.launch(Intent(this, BackupActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onDestroy() {
        tabMediator?.detach()
        super.onDestroy()
    }
}
