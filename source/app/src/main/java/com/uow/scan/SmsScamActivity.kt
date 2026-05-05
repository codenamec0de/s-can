package com.uow.scan

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.uow.scan.adapter.SmsVerdictAdapter
import com.uow.scan.data.ScanDatabase
import com.uow.scan.model.SmsVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class SmsScamActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VERDICT_ID = "verdict_id"
    }

    private lateinit var rvVerdicts: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var btnBack: FrameLayout
    private lateinit var btnSettings: FrameLayout
    private lateinit var adapter: SmsVerdictAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sms_scam)

        btnBack = findViewById(R.id.btnBack)
        btnSettings = findViewById(R.id.btnSettings)
        rvVerdicts = findViewById(R.id.rvVerdicts)
        emptyState = findViewById(R.id.emptyState)

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

        loadVerdicts()
    }

    override fun onResume() {
        super.onResume()
        loadVerdicts()
    }

    private fun loadVerdicts() {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = ScanDatabase.getInstance(this@SmsScamActivity).smsVerdictDao()
            val entities = dao.getAll()
            val verdicts = entities.map { e ->
                SmsVerdict(
                    e.id, e.sender, e.messageBody, e.verdict,
                    e.confidence, e.explanation, e.timestamp, e.isRead,
                    e.urlSignals
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

        val urlSection = formatUrlSignals(verdict.urlSignals)

        AlertDialog.Builder(this)
            .setTitle("${verdict.verdictLabel} (${verdict.confidencePercent})")
            .setMessage(
                "From: ${verdict.sender}\n\n" +
                "Message:\n${verdict.messageBody}\n\n" +
                "AI Analysis:\n${verdict.explanation}" +
                urlSection
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun formatUrlSignals(urlSignals: String?): String {
        if (urlSignals.isNullOrBlank()) return ""
        return try {
            val arr = JSONArray(urlSignals)
            val sb = StringBuilder("\n\n--- URL Analysis ---")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val url = obj.getString("url")
                val verdict = obj.getString("verdict").uppercase()
                val brand = obj.optString("brand_match", "")
                val riskScore = obj.optDouble("risk_score", 0.0)
                val riskPct = "${(riskScore * 100).toInt()}%"

                sb.append("\n\nURL: $url")
                sb.append("\nVerdict: $verdict (risk $riskPct)")
                if (brand.isNotBlank()) sb.append("\nImpersonating: $brand")

                val signals = obj.optJSONArray("signals")
                if (signals != null && signals.length() > 0) {
                    sb.append("\nSignals:")
                    for (j in 0 until signals.length()) {
                        val sig = signals.getJSONObject(j)
                        val weight = sig.optDouble("weight", 0.0)
                        if (weight > 0) sb.append("\n  - ${sig.getString("value")}")
                    }
                }
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }
}
