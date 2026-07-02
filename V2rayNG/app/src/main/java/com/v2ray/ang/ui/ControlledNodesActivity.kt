package com.v2ray.ang.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.activity.OnBackPressedCallback
import com.v2ray.ang.R
import com.v2ray.ang.control.ControlledNode
import com.v2ray.ang.control.ControlledNodeSync
import com.v2ray.ang.control.ControlledSession
import com.v2ray.ang.databinding.ActivityControlledNodesBinding
import com.v2ray.ang.handler.MmkvManager

class ControlledNodesActivity : BaseActivity() {
    private val binding by lazy {
        ActivityControlledNodesBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.controlled_nodes_title))
        renderNodes()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closeWithAnimation()
            }
        })
    }

    private fun renderNodes() {
        binding.llNodes.removeAllViews()
        val serverIds = MmkvManager.decodeServerList(ControlledNodeSync.CONTROLLED_SUBSCRIPTION_ID)
        val nodes = ControlledSession.getNodes(this)
        val selectedGuid = MmkvManager.getSelectServer()

        if (serverIds.isEmpty()) {
            binding.llNodes.addView(emptyText())
            return
        }

        serverIds.forEachIndexed { index, guid ->
            val node = nodes.getOrNull(index)
            binding.llNodes.addView(nodeRow(guid, node, index, guid == selectedGuid))
        }
    }

    private fun nodeRow(guid: String, node: ControlledNode?, index: Int, selected: Boolean): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = ContextCompat.getDrawable(
                this@ControlledNodesActivity,
                if (selected) R.drawable.bg_controlled_node_selected else R.drawable.bg_controlled_surface_panel
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (!selected) {
                    MmkvManager.setSelectServer(guid)
                    setResult(RESULT_OK)
                }
                closeWithAnimation()
            }

            val flag = TextView(this@ControlledNodesActivity).apply {
                text = node?.flagEmoji?.takeIf { it.isNotBlank() } ?: "🌐"
                textSize = 28f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(54), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(8)
                }
            }
            val textColumn = LinearLayout(this@ControlledNodesActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val title = TextView(this@ControlledNodesActivity).apply {
                text = node?.name?.takeIf { it.isNotBlank() && !looksLikeHost(it) }
                    ?: node?.countryName?.takeIf { it.isNotBlank() }
                    ?: "线路 ${index + 1}"
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(this@ControlledNodesActivity, R.color.controlled_ink))
            }

            textColumn.addView(title)
            addView(flag)
            addView(textColumn)
        }

    private fun emptyText(): TextView =
        TextView(this).apply {
            text = getString(R.string.controlled_home_no_line)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@ControlledNodesActivity, R.color.controlled_text_muted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun looksLikeHost(value: String): Boolean {
        val text = value.trim()
        return Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""").containsMatchIn(text) ||
            Regex("""(?i)\b[a-z0-9-]+(?:\.[a-z0-9-]+)+\b""").containsMatchIn(text)
    }

    private fun closeWithAnimation() {
        finish()
        overridePendingTransition(R.anim.controlled_slide_in_left, R.anim.controlled_slide_out_right)
    }
}
