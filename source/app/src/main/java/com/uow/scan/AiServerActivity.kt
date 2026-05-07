package com.uow.scan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.uow.scan.api.ScanAiClient
import com.uow.scan.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiServerActivity : AppCompatActivity() {

    private enum class Status { OK, BAD, WARN, IDLE, CHECKING }

    private lateinit var btnBack: FrameLayout
    private lateinit var swCachedFallback: SwitchCompat
    private lateinit var statusCard: LinearLayout
    private lateinit var statusDot: View
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusMeta: TextView
    private lateinit var btnStatusRefresh: ImageView

    private lateinit var serverUrlField: FrameLayout
    private lateinit var etServerUrl: EditText
    private lateinit var tvUrlError: TextView

    private lateinit var btnSave: FrameLayout
    private lateinit var refContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_server)
        bindViews()
        loadPrefs()
        setupListeners()
        renderStatusReference()
        setStatus(Status.IDLE)
    }

    override fun onResume() {
        super.onResume()
        if (etServerUrl.text.isNotBlank() && !swCachedFallback.isChecked) {
            testConnection()
        }
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        swCachedFallback = findViewById(R.id.swCachedFallback)
        statusCard = findViewById(R.id.statusCard)
        statusDot = findViewById(R.id.statusDot)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusMeta = findViewById(R.id.tvStatusMeta)
        btnStatusRefresh = findViewById(R.id.btnStatusRefresh)
        serverUrlField = findViewById(R.id.serverUrlField)
        etServerUrl = findViewById(R.id.etServerUrl)
        tvUrlError = findViewById(R.id.tvUrlError)
        btnSave = findViewById(R.id.btnSave)
        refContainer = findViewById(R.id.refContainer)
    }

    private fun loadPrefs() {
        // getSmsServerUrl returns DEFAULT_SMS_SERVER_URL when nothing is saved,
        // so the field is pre-populated with the public sidecar by default.
        etServerUrl.setText(PreferencesManager.getSmsServerUrl(this))
        swCachedFallback.isChecked = PreferencesManager.isSmsFallbackEnabled(this)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
        btnStatusRefresh.setOnClickListener { testConnection() }
        btnSave.setOnClickListener { saveAndTest() }
        swCachedFallback.setOnCheckedChangeListener { _, isChecked ->
            PreferencesManager.setSmsFallbackEnabled(this, isChecked)
            if (isChecked) {
                setStatus(Status.IDLE, getString(R.string.ai_server_status_cached_meta))
            } else {
                testConnection()
            }
        }
    }

    private fun saveAndTest() {
        val url = etServerUrl.text.toString().trim()

        if (url.isBlank() || !looksLikeUrl(url)) {
            tvUrlError.visibility = View.VISIBLE
            tvUrlError.text = getString(R.string.ai_server_url_error_invalid)
            serverUrlField.setBackgroundResource(R.drawable.bg_v4_field_bad)
            return
        }

        tvUrlError.visibility = View.GONE
        serverUrlField.setBackgroundResource(R.drawable.bg_v4_field_neutral)

        PreferencesManager.setSmsServerUrl(this, url)
        ScanAiClient.reset()
        Toast.makeText(this, getString(R.string.ai_server_saved), Toast.LENGTH_SHORT).show()

        if (!swCachedFallback.isChecked) {
            testConnection()
        }
    }

    private fun looksLikeUrl(text: String): Boolean =
        text.startsWith("http://") || text.startsWith("https://")

    private fun testConnection() {
        if (swCachedFallback.isChecked) {
            setStatus(Status.IDLE, getString(R.string.ai_server_status_cached_meta))
            return
        }
        setStatus(Status.CHECKING)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ScanAiClient.getApi(this@AiServerActivity).health() }
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { resp ->
                        when {
                            resp.isSuccessful && resp.body()?.status == "ok" ->
                                setStatus(Status.OK)
                            resp.isSuccessful ->
                                setStatus(Status.WARN)
                            else ->
                                setStatus(Status.BAD, "Server returned ${resp.code()}")
                        }
                    },
                    onFailure = { err ->
                        setStatus(Status.BAD, err.message ?: "Connection timed out")
                    }
                )
            }
        }
    }

    private fun setStatus(status: Status, customMeta: String? = null) {
        when (status) {
            Status.OK -> {
                statusCard.setBackgroundResource(R.drawable.bg_v4_status_card_ok)
                statusDot.setBackgroundResource(R.drawable.bg_v4_sev_dot_ok)
                tvStatusTitle.setText(R.string.ai_server_status_connected)
                tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.v4_ok))
                tvStatusMeta.text = customMeta ?: getString(R.string.ai_server_status_connected_meta)
                tvUrlError.visibility = View.GONE
                serverUrlField.setBackgroundResource(R.drawable.bg_v4_field_neutral)
            }
            Status.WARN -> {
                statusCard.setBackgroundResource(R.drawable.bg_v4_status_card_warn)
                statusDot.setBackgroundResource(R.drawable.bg_v4_sev_dot_warn)
                tvStatusTitle.setText(R.string.ai_server_status_warn)
                tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.v4_warn))
                tvStatusMeta.text = customMeta ?: getString(R.string.ai_server_status_warn_meta)
            }
            Status.BAD -> {
                statusCard.setBackgroundResource(R.drawable.bg_v4_status_card_bad)
                statusDot.setBackgroundResource(R.drawable.bg_v4_sev_dot_bad)
                tvStatusTitle.setText(R.string.ai_server_status_unreachable)
                tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.v4_bad))
                tvStatusMeta.text = customMeta ?: getString(
                    R.string.ai_server_status_unreachable_meta, "just now"
                )
                serverUrlField.setBackgroundResource(R.drawable.bg_v4_field_bad)
                tvUrlError.visibility = View.VISIBLE
                tvUrlError.text = getString(R.string.ai_server_url_error_timeout)
            }
            Status.CHECKING -> {
                statusCard.setBackgroundResource(R.drawable.bg_v4_surface)
                statusDot.setBackgroundResource(R.drawable.bg_v4_sev_dot_warn)
                tvStatusTitle.setText(R.string.ai_server_status_checking)
                tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.v4_fg1))
                tvStatusMeta.text = getString(R.string.ai_server_status_checking_meta)
            }
            Status.IDLE -> {
                statusCard.setBackgroundResource(R.drawable.bg_v4_surface)
                statusDot.setBackgroundResource(R.drawable.bg_v4_sev_dot_warn)
                tvStatusTitle.setText(R.string.ai_server_status_idle)
                tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.v4_fg1))
                tvStatusMeta.text = customMeta ?: getString(R.string.ai_server_status_idle_meta)
                tvUrlError.visibility = View.GONE
                serverUrlField.setBackgroundResource(R.drawable.bg_v4_field_neutral)
            }
        }
    }

    private fun renderStatusReference() {
        data class RefRow(val sev: Int, val icon: Int, val title: Int, val detail: Int)

        val rows = listOf(
            RefRow(R.color.v4_bad, R.drawable.ic_glyph_warn,
                R.string.ai_server_ref_unreachable_t, R.string.ai_server_ref_unreachable_d),
            RefRow(R.color.v4_warn, R.drawable.ic_glyph_warn,
                R.string.ai_server_ref_warn_t, R.string.ai_server_ref_warn_d),
            RefRow(R.color.v4_bad, R.drawable.ic_glyph_warn,
                R.string.ai_server_ref_tls_t, R.string.ai_server_ref_tls_d),
            RefRow(R.color.v4_ok, R.drawable.ic_glyph_check,
                R.string.ai_server_ref_ok_t, R.string.ai_server_ref_ok_d),
        )

        refContainer.removeAllViews()
        for (row in rows) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_v4_status_ref, refContainer, false)
            v.findViewById<ImageView>(R.id.refIcon).apply {
                setImageResource(row.icon)
                setColorFilter(ContextCompat.getColor(this@AiServerActivity, row.sev))
            }
            v.findViewById<TextView>(R.id.refTitle).apply {
                setText(row.title)
                setTextColor(ContextCompat.getColor(this@AiServerActivity, row.sev))
            }
            v.findViewById<TextView>(R.id.refDetail).setText(row.detail)
            refContainer.addView(v)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
