package com.uow.scan

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.uow.scan.adapter.SmsVerdictAdapter
import com.uow.scan.api.ScanAiClient
import com.uow.scan.api.ScanAiFallback
import com.uow.scan.data.ScanDatabase
import com.uow.scan.data.entity.SmsVerdictEntity
import com.uow.scan.model.SmsVerdict
import com.uow.scan.util.PreferencesManager
import com.uow.scan.util.ScanDialog
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
    private lateinit var btnResetVerdicts: FrameLayout
    private lateinit var statusPill: LinearLayout
    private lateinit var statusPillDot: View
    private lateinit var statusPillLabel: TextView
    private lateinit var btnTrySample: LinearLayout
    private lateinit var adapter: SmsVerdictAdapter

    /** A bundled, realistic scam SMS the "Try a sample scam" button can classify on cue. */
    private data class SampleScam(val sender: String, val body: String)

    /**
     * Varied, convincing sample scams (AU context) cycled through on each tap of "Try a sample
     * scam". Each body contains a substring that the on-device classifier ([ScanAiFallback] /
     * assets/scam_fallback.json) matches to a bespoke SCAM explanation, so every demo card reads
     * as a tailored verdict rather than a generic keyword hit.
     */
    private val sampleScams = listOf(
        SampleScam(
            "+61 400 555 113",
            "AusPost: your parcel is held pending a \$1.99 release fee. " +
                "Pay now to avoid return: auspost-au.info/track"
        ),
        SampleScam(
            "+61 400 818 224",
            "LINKT: you have an unpaid toll of \$4.83. Late fees apply if not settled " +
                "within 48 hours: linkt-au.com/pay"
        ),
        SampleScam(
            "ATO",
            "ATO Refund: our records show you are owed a \$1,284.50 tax refund. Confirm your " +
                "bank details to receive your ATO refund: ato-refund.online/claim"
        ),
        SampleScam(
            "CommBank",
            "CommBank: a new payee 'J SMITH' was added and a \$980 transfer is pending. " +
                "If this wasn't you, call our fraud team now on 02 8014 7720."
        ),
        SampleScam(
            "myGov",
            "myGov: your account has been suspended after 3 failed login attempts. Reactivate " +
                "within 24 hours to avoid permanent closure: my-gov.au-secure.com/restore"
        ),
        SampleScam(
            "+61 491 570 156",
            "Hi mum, I smashed my phone and I'm texting from a temporary number. I can't get " +
                "into my banking — could you pay a bill for me today? I'll pay you back x"
        ),
        SampleScam(
            "Netflix",
            "Netflix: your payment was declined and your membership is on hold. Update your " +
                "billing within 24h to avoid cancellation: netflix-billing.account-verify.com"
        ),
    )

    private var sampleIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sms_scam)

        btnBack = findViewById(R.id.btnBack)
        btnSettings = findViewById(R.id.btnSettings)
        btnResetVerdicts = findViewById(R.id.btnResetVerdicts)
        rvVerdicts = findViewById(R.id.rvVerdicts)
        emptyState = findViewById(R.id.emptyState)
        statusPill = findViewById(R.id.statusPill)
        statusPillDot = findViewById(R.id.statusPillDot)
        statusPillLabel = findViewById(R.id.statusPillLabel)
        btnTrySample = findViewById(R.id.btnTrySample)

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
        btnTrySample.setOnClickListener { insertSampleScam() }
        btnResetVerdicts.setOnClickListener { confirmResetVerdicts() }

        loadVerdicts()
    }

    /**
     * Clears every stored verdict (after a confirm), so the screen can be reset to a clean
     * slate between demos. No-ops with a toast when there is nothing to clear.
     */
    private fun confirmResetVerdicts() {
        lifecycleScope.launch {
            val dao = ScanDatabase.getInstance(this@SmsScamActivity).smsVerdictDao()
            val count = withContext(Dispatchers.IO) { dao.getCount() }
            if (count == 0) {
                Toast.makeText(this@SmsScamActivity, R.string.sms_v4_reset_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }
            ScanDialog.confirm(
                context = this@SmsScamActivity,
                title = getString(R.string.sms_v4_reset_title),
                message = getString(R.string.sms_v4_reset_message, count),
                confirmText = getString(R.string.sms_v4_reset_confirm),
                cancelText = getString(R.string.sms_v4_reset_cancel),
            ) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.clearAll() }
                    Toast.makeText(this@SmsScamActivity, R.string.sms_v4_reset_done, Toast.LENGTH_SHORT).show()
                    loadVerdicts()
                }
            }
        }
    }

    /**
     * Classifies a bundled sample scam message entirely on-device (no SMS or server needed)
     * and stores the verdict, so a real SCAM card can be demonstrated on cue.
     */
    private fun insertSampleScam() {
        btnTrySample.isEnabled = false
        val sample = sampleScams[sampleIndex % sampleScams.size]
        sampleIndex++
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val result = ScanAiFallback.classify(this@SmsScamActivity, sample.body)
                ScanDatabase.getInstance(this@SmsScamActivity).smsVerdictDao().insert(
                    SmsVerdictEntity(
                        sender = sample.sender,
                        messageBody = sample.body,
                        verdict = result.verdict.uppercase(),
                        confidence = result.confidence,
                        explanation = result.reasoning,
                        timestamp = System.currentTimeMillis(),
                        urlSignals = null
                    )
                )
            }
            btnTrySample.isEnabled = true
            loadVerdicts()
        }
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
            // CACHED = on-device classifier active: a healthy/green state, not a warning.
            ConnState.OK, ConnState.CACHED -> R.drawable.bg_v4_sev_dot_ok to R.color.v4_ok
            ConnState.WARN -> R.drawable.bg_v4_sev_dot_warn to R.color.v4_warn
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

        ScanDialog.notice(
            context = this,
            title = "${verdict.verdictLabel} (${verdict.confidencePercent})",
            message = "From: ${verdict.sender}\n\n" +
                "Message:\n${verdict.messageBody}\n\n" +
                "AI Analysis:\n${verdict.explanation}",
            buttonText = "OK",
        )
    }
}
