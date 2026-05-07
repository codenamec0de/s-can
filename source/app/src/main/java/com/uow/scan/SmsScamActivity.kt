package com.uow.scan

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.uow.scan.adapter.SmsVerdictAdapter
import com.uow.scan.api.ScanAiClient
import com.uow.scan.data.ScanDatabase
import com.uow.scan.model.SmsVerdict
import com.uow.scan.util.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SmsScamActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VERDICT_ID = "verdict_id"
    }

    private enum class ConnState { CHECKING, OK, WARN, BAD, CACHED }

    private lateinit var rvVerdicts: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var btnBack: FrameLayout
    private lateinit var btnSettings: FrameLayout
    private lateinit var statusPill: LinearLayout
    private lateinit var statusPillDot: View
    private lateinit var statusPillLabel: TextView
    private lateinit var adapter: SmsVerdictAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sms_scam)

        btnBack = findViewById(R.id.btnBack)
        btnSettings = findViewById(R.id.btnSettings)
        rvVerdicts = findViewById(R.id.rvVerdicts)
        emptyState = findViewById(R.id.emptyState)
        statusPill = findViewById(R.id.statusPill)
        statusPillDot = findViewById(R.id.statusPillDot)
        statusPillLabel = findViewById(R.id.statusPillLabel)

        adapter = SmsVerdictAdapter { verdict -> showDetail(verdict) }
        rvVerdicts.layoutManager = LinearLayoutManager(this)
        rvVerdicts.adapter = adapter

        btnBack.setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, AiServerActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
        statusPill.setOnClickListener { checkConnection() }

        loadVerdicts()
    }

    override fun onResume() {
        super.onResume()
        loadVerdicts()
        checkConnection()
    }

    private fun checkConnection() {
        if (PreferencesManager.isSmsFallbackEnabled(this)) {
            setConnStatus(ConnState.CACHED, getString(R.string.sms_v4_conn_cached))
            return
        }
        setConnStatus(ConnState.CHECKING, getString(R.string.sms_v4_conn_checking))
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { ScanAiClient.getApi(this@SmsScamActivity).health() }
            }
            outcome.fold(
                onSuccess = { resp ->
                    when {
                        resp.isSuccessful && resp.body()?.status == "ok" ->
                            setConnStatus(ConnState.OK, getString(R.string.sms_v4_conn_ok))
                        resp.isSuccessful ->
                            setConnStatus(ConnState.WARN, getString(R.string.sms_v4_conn_warn))
                        else ->
                            setConnStatus(
                                ConnState.BAD,
                                getString(R.string.sms_v4_conn_bad_code, resp.code())
                            )
                    }
                },
                onFailure = {
                    setConnStatus(ConnState.BAD, getString(R.string.sms_v4_conn_bad))
                }
            )
        }
    }

    private fun setConnStatus(state: ConnState, label: String) {
        val (dotRes, colorRes) = when (state) {
            ConnState.OK -> R.drawable.bg_v4_sev_dot_ok to R.color.v4_ok
            ConnState.WARN, ConnState.CACHED -> R.drawable.bg_v4_sev_dot_warn to R.color.v4_warn
            ConnState.BAD -> R.drawable.bg_v4_sev_dot_bad to R.color.v4_bad
            ConnState.CHECKING -> R.drawable.bg_v4_sev_dot_warn to R.color.v4_fg2
        }
        statusPillDot.setBackgroundResource(dotRes)
        statusPillLabel.text = label
        statusPillLabel.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun loadVerdicts() {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = ScanDatabase.getInstance(this@SmsScamActivity).smsVerdictDao()
            val entities = dao.getAll()
            val verdicts = entities.map { e ->
                SmsVerdict(
                    e.id, e.sender, e.messageBody, e.verdict,
                    e.confidence, e.explanation, e.timestamp, e.isRead
                )
            }

            val highlightId = intent.getLongExtra(EXTRA_VERDICT_ID, -1)
            if (highlightId > 0) {
                dao.markAsRead(highlightId)
            }

            withContext(Dispatchers.Main) {
                if (verdicts.isEmpty()) {
                    emptyState.visibility = View.VISIBLE
                    rvVerdicts.visibility = View.GONE
                } else {
                    emptyState.visibility = View.GONE
                    rvVerdicts.visibility = View.VISIBLE
                    adapter.submitList(verdicts)
                }
            }
        }
    }

    private fun showDetail(verdict: SmsVerdict) {
        CoroutineScope(Dispatchers.IO).launch {
            ScanDatabase.getInstance(this@SmsScamActivity)
                .smsVerdictDao().markAsRead(verdict.id)
        }

        AlertDialog.Builder(this)
            .setTitle("${verdict.verdictLabel} (${verdict.confidencePercent})")
            .setMessage(
                "From: ${verdict.sender}\n\n" +
                "Message:\n${verdict.messageBody}\n\n" +
                "AI Analysis:\n${verdict.explanation}"
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
