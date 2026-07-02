package com.v2ray.ang.ui

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.control.ControlledApi
import com.v2ray.ang.control.ControlledApiException
import com.v2ray.ang.control.ControlledNodeSync
import com.v2ray.ang.control.ControlledSession
import com.v2ray.ang.databinding.ActivityControlledBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ControlledActivity : BaseActivity() {
    private val binding by lazy {
        ActivityControlledBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.etBackendUrl.setText(ControlledSession.getBaseUrl(this))
        binding.etUsername.setText(ControlledSession.username(this))
        binding.tvDeviceId.text = ControlledSession.getDeviceId(this)
        refreshStatus()

        binding.btnLogin.setOnClickListener { loginAndSync() }
        binding.btnSync.setOnClickListener { syncOnly() }
        binding.btnLogout.setOnClickListener { logoutAndClear() }
    }

    private fun loginAndSync() {
        val baseUrl = ControlledSession.getBaseUrl(this)
        val username = binding.etUsername.text?.toString()?.trim().orEmpty()
        val licenseCode = binding.etLicenseCode.text?.toString()?.trim().orEmpty()
        if (username.isBlank() || licenseCode.isBlank()) {
            toast(R.string.controlled_fill_required)
            return
        }

        showLoading()
        lifecycleScope.launch {
            try {
                val deviceId = ControlledSession.getDeviceId(this@ControlledActivity)
                val loginResult = withContext(Dispatchers.IO) {
                    ControlledApi(baseUrl).login(username, licenseCode, deviceId, BuildConfig.VERSION_NAME)
                }
                ControlledSession.saveAuth(this@ControlledActivity, baseUrl, loginResult.token, loginResult.user)

                val nodesResult = withContext(Dispatchers.IO) {
                    ControlledApi(baseUrl).nodes(loginResult.token)
                }
                val importedCount = withContext(Dispatchers.IO) {
                    ControlledNodeSync.sync(this@ControlledActivity, nodesResult)
                }
                ControlledSession.saveAuth(this@ControlledActivity, baseUrl, loginResult.token, nodesResult.user)

                setResult(RESULT_OK)
                refreshStatus()
                toast(getString(R.string.controlled_sync_success, importedCount))
                finish()
            } catch (e: Exception) {
                handleSyncError(baseUrl, token = null, e = e)
            } finally {
                hideLoading()
            }
        }
    }

    private fun syncOnly() {
        val baseUrl = ControlledSession.getBaseUrl(this)
        val token = ControlledSession.getToken(this)
        if (baseUrl.isBlank() || token.isBlank()) {
            toast(R.string.controlled_login_first)
            return
        }

        showLoading()
        lifecycleScope.launch {
            try {
                val nodesResult = withContext(Dispatchers.IO) {
                    ControlledApi(baseUrl).nodes(token)
                }
                val importedCount = withContext(Dispatchers.IO) {
                    ControlledNodeSync.sync(this@ControlledActivity, nodesResult)
                }
                ControlledSession.saveAuth(this@ControlledActivity, baseUrl, token, nodesResult.user)
                setResult(RESULT_OK)
                refreshStatus()
                toast(getString(R.string.controlled_sync_success, importedCount))
            } catch (e: Exception) {
                handleSyncError(baseUrl, token = token, e = e)
            } finally {
                hideLoading()
            }
        }
    }

    private fun handleSyncError(baseUrl: String, token: String?, e: Exception) {
        if (e is ControlledApiException && e.statusCode == 403) {
            e.user?.let {
                ControlledSession.saveAuth(this, baseUrl, token ?: ControlledSession.getToken(this), it)
            }
            ControlledNodeSync.clear(this)
            setResult(RESULT_OK)
            refreshStatus()
            toastError(getString(R.string.controlled_home_access_denied))
            return
        }
        toastError("${getString(R.string.controlled_sync_failed)}: ${e.message.orEmpty()}")
    }

    private fun logoutAndClear() {
        ControlledSession.clear(this)
        ControlledNodeSync.clear(this)
        setResult(RESULT_OK)
        refreshStatus()
        toast(R.string.controlled_logged_out)
    }

    private fun refreshStatus() {
        binding.tvStatus.text = getString(R.string.controlled_login_credential_notice)
    }
}
