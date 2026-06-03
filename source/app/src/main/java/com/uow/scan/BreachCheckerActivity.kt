package com.uow.scan

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.uow.scan.util.BreachChecker
import com.uow.scan.util.PreferencesManager
import com.uow.scan.util.ScanDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class BreachCheckerActivity : AppCompatActivity() {

    private lateinit var btnBack: View
    private lateinit var heroCard: LinearLayout
    private lateinit var heroDot: View
    private lateinit var tvHeroEyebrow: TextView
    private lateinit var tvHeroEmail: TextView
    private lateinit var tvHeroMeta: TextView
    private lateinit var heroActions: LinearLayout
    private lateinit var btnRescan: Button
    private lateinit var btnAddAddress: Button

    private lateinit var credentialsSection: LinearLayout
    private lateinit var etEmail: TextInputEditText
    private lateinit var etApiKey: TextInputEditText
    private lateinit var btnCheck: Button

    private lateinit var monitoredSection: LinearLayout
    private lateinit var monitoredList: LinearLayout

    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvHistoryLabel: TextView
    private lateinit var breachList: LinearLayout

    /** Cached results per email so we can re-render without hitting the network. */
    private val resultsByEmail = mutableMapOf<String, BreachChecker.BreachResult>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_breach_checker)

        initViews()
        setupListeners()
        seedFirebaseEmail()
        prefillCredentials()
        rerenderAll()
    }

    private fun initViews() {
        val topBar = findViewById<View>(R.id.topBar)
        btnBack = topBar.findViewById(R.id.btnBack)
        topBar.findViewById<TextView>(R.id.tvTopBarTitle)
            .setText(R.string.breach_v4_title)

        heroCard = findViewById(R.id.heroCard)
        heroDot = findViewById(R.id.heroDot)
        tvHeroEyebrow = findViewById(R.id.tvHeroEyebrow)
        tvHeroEmail = findViewById(R.id.tvHeroEmail)
        tvHeroMeta = findViewById(R.id.tvHeroMeta)
        heroActions = findViewById(R.id.heroActions)
        btnRescan = findViewById(R.id.btnRescan)
        btnAddAddress = findViewById(R.id.btnAddAddress)

        credentialsSection = findViewById(R.id.credentialsSection)
        etEmail = findViewById(R.id.etEmail)
        etApiKey = findViewById(R.id.etApiKey)
        btnCheck = findViewById(R.id.btnCheck)

        monitoredSection = findViewById(R.id.monitoredSection)
        monitoredList = findViewById(R.id.monitoredList)

        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
        tvHistoryLabel = findViewById(R.id.tvHistoryLabel)
        breachList = findViewById(R.id.breachList)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
        btnCheck.setOnClickListener { saveCredentialsAndCheck() }
        btnRescan.setOnClickListener { rescanSelected() }
        btnAddAddress.setOnClickListener { showAddAddressDialog() }
        findViewById<View>(R.id.pwEntryCard).setOnClickListener { PasswordCheckActivity.start(this) }
    }

    /** First open after Firebase login — auto-add the user's account email if list is empty. */
    private fun seedFirebaseEmail() {
        val list = PreferencesManager.getBreachAddresses(this)
        if (list.isNotEmpty()) return
        FirebaseAuth.getInstance().currentUser?.email?.let { email ->
            PreferencesManager.addBreachAddress(this, email)
            PreferencesManager.setSelectedBreachAddress(this, email)
        }
    }

    private fun prefillCredentials() {
        FirebaseAuth.getInstance().currentUser?.email?.let { etEmail.setText(it) }
        getApiKey()?.let { etApiKey.setText(it) }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Top-level rendering
    // ──────────────────────────────────────────────────────────────────────

    private fun rerenderAll() {
        val configured = !getApiKey().isNullOrBlank()
        credentialsSection.visibility = if (configured) View.GONE else View.VISIBLE
        monitoredSection.visibility = if (configured) View.VISIBLE else View.GONE
        heroActions.visibility = if (configured) View.VISIBLE else View.GONE

        if (configured) {
            renderMonitoredAddresses()
            renderHeroAndHistoryForSelected()
        } else {
            renderHero(null, null)
            tvHistoryLabel.visibility = View.GONE
            breachList.removeAllViews()
        }
    }

    private fun renderHeroAndHistoryForSelected() {
        val selected = currentSelectedEmail()
        if (selected.isNullOrBlank()) {
            renderHero(null, null)
            tvHistoryLabel.visibility = View.GONE
            breachList.removeAllViews()
            return
        }

        val cached = resultsByEmail[selected]
        if (cached != null) {
            showResults(cached)
            return
        }
        renderHero(selected, null)
        tvHistoryLabel.visibility = View.GONE
        breachList.removeAllViews()

        // Pull from DB lazily.
        lifecycleScope.launch {
            val fromDb = withContext(Dispatchers.IO) {
                BreachChecker.getCached(this@BreachCheckerActivity, selected)
            }
            if (fromDb != null) {
                resultsByEmail[selected] = fromDb
                if (currentSelectedEmail() == selected) showResults(fromDb)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Credentials → first scan
    // ──────────────────────────────────────────────────────────────────────

    private fun saveCredentialsAndCheck() {
        val email = etEmail.text?.toString()?.trim()
        val apiKey = etApiKey.text?.toString()?.trim()

        if (email.isNullOrBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.error = getString(R.string.breach_v4_err_email_invalid)
            return
        }
        if (apiKey.isNullOrBlank()) {
            etApiKey.error = getString(R.string.breach_v4_err_apikey_required)
            return
        }

        saveApiKey(apiKey)

        // Make sure the entered email is in the monitored list and selected.
        if (PreferencesManager.getBreachAddresses(this).none { it.equals(email, ignoreCase = true) }) {
            PreferencesManager.addBreachAddress(this, email)
        }
        PreferencesManager.setSelectedBreachAddress(this, email)

        rerenderAll()
        runCheck(email, apiKey)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Re-scan selected address
    // ──────────────────────────────────────────────────────────────────────

    private fun rescanSelected() {
        val email = currentSelectedEmail()
        if (email.isNullOrBlank()) {
            Toast.makeText(this, R.string.breach_v4_err_no_address, Toast.LENGTH_SHORT).show()
            return
        }
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            // shouldn't reach here while configured; guard anyway
            credentialsSection.visibility = View.VISIBLE
            return
        }
        runCheck(email, apiKey)
    }

    private fun runCheck(email: String, apiKey: String) {
        btnCheck.isEnabled = false
        btnRescan.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.breach_v4_status_checking)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.v4_fg2))
        tvHistoryLabel.visibility = View.GONE
        breachList.removeAllViews()

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    BreachChecker.check(this@BreachCheckerActivity, email, apiKey)
                }
                resultsByEmail[email] = result
                progressBar.visibility = View.GONE
                tvStatus.visibility = View.GONE
                if (currentSelectedEmail() == email) showResults(result)
                renderMonitoredAddresses()
            } catch (e: BreachChecker.BreachCheckException) {
                progressBar.visibility = View.GONE
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = getString(R.string.breach_v4_status_error_prefix, e.message ?: "")
                tvStatus.setTextColor(ContextCompat.getColor(this@BreachCheckerActivity, R.color.v4_bad))
            } catch (_: Exception) {
                progressBar.visibility = View.GONE
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = getString(R.string.breach_v4_status_network_error)
                tvStatus.setTextColor(ContextCompat.getColor(this@BreachCheckerActivity, R.color.v4_bad))
            } finally {
                btnCheck.isEnabled = true
                btnRescan.isEnabled = true
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Add / select / remove monitored address
    // ──────────────────────────────────────────────────────────────────────

    private fun showAddAddressDialog() {
        val current = PreferencesManager.getBreachAddresses(this)
        if (current.size >= PreferencesManager.BREACH_ADDRESS_LIMIT) {
            Toast.makeText(
                this,
                getString(R.string.breach_v4_err_address_limit, PreferencesManager.BREACH_ADDRESS_LIMIT),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        ScanDialog.input(
            context = this,
            title = getString(R.string.breach_v4_dialog_add_title),
            hint = getString(R.string.breach_v4_email_hint),
            confirmText = getString(R.string.breach_v4_dialog_add_save),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        ) { email ->
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, R.string.breach_v4_err_email_invalid, Toast.LENGTH_SHORT).show()
                return@input
            }
            val ok = PreferencesManager.addBreachAddress(this, email)
            if (!ok) {
                Toast.makeText(this, R.string.breach_v4_err_address_duplicate, Toast.LENGTH_SHORT).show()
                return@input
            }
            PreferencesManager.setSelectedBreachAddress(this, email)
            rerenderAll()
            getApiKey()?.let { runCheck(email, it) }
        }
    }

    private fun confirmRemoveAddress(email: String) {
        ScanDialog.confirm(
            context = this,
            title = getString(R.string.breach_v4_dialog_remove_title),
            message = getString(R.string.breach_v4_dialog_remove_body, email),
            confirmText = getString(R.string.breach_v4_dialog_remove_confirm),
        ) {
            PreferencesManager.removeBreachAddress(this, email)
            resultsByEmail.remove(email)
            lifecycleScope.launch(Dispatchers.IO) {
                com.uow.scan.data.ScanDatabase.getInstance(this@BreachCheckerActivity)
                    .breachResultDao().deleteByEmail(email)
            }
            rerenderAll()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Render helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun renderMonitoredAddresses() {
        monitoredList.removeAllViews()
        val addresses = PreferencesManager.getBreachAddresses(this)
        val selected = currentSelectedEmail()
        val inflater = LayoutInflater.from(this)

        addresses.forEachIndexed { i, email ->
            val row = inflater.inflate(R.layout.item_breach_address, monitoredList, false)
            row.findViewById<ImageView>(R.id.rowIcon).setImageResource(R.drawable.ic_glyph_email)
            row.findViewById<TextView>(R.id.tvRowLabel).text = email

            val statusText = when {
                resultsByEmail[email]?.isClean == true -> getString(R.string.breach_v4_status_clean)
                resultsByEmail.containsKey(email) ->
                    resources.getQuantityString(
                        R.plurals.breach_v4_n_breaches,
                        resultsByEmail[email]!!.breachCount,
                        resultsByEmail[email]!!.breachCount
                    )
                else -> getString(R.string.breach_v4_status_not_checked)
            }
            val tvVal = row.findViewById<TextView>(R.id.tvRowValue)
            tvVal.text = statusText
            if (resultsByEmail[email] != null && !resultsByEmail[email]!!.isClean) {
                tvVal.setTextColor(ContextCompat.getColor(this, R.color.v4_bad))
            } else {
                tvVal.setTextColor(ContextCompat.getColor(this, R.color.v4_fg3))
            }

            if (email.equals(selected, ignoreCase = true)) {
                row.setBackgroundColor(ContextCompat.getColor(this, R.color.v4_accent_bg))
            }

            row.setOnClickListener {
                PreferencesManager.setSelectedBreachAddress(this, email)
                rerenderAll()
            }
            row.setOnLongClickListener {
                confirmRemoveAddress(email)
                true
            }
            monitoredList.addView(row)
            if (i < addresses.size - 1) monitoredList.addView(thinDivider())
        }

        // "+ Add another address" row, disabled at limit.
        val addRow = inflater.inflate(R.layout.item_breach_address, monitoredList, false)
        if (addresses.isNotEmpty()) monitoredList.addView(thinDivider(), monitoredList.childCount)
        addRow.findViewById<ImageView>(R.id.rowIcon).setImageResource(R.drawable.ic_glyph_plus)
        val tvAddLabel = addRow.findViewById<TextView>(R.id.tvRowLabel)
        tvAddLabel.text = if (addresses.size >= PreferencesManager.BREACH_ADDRESS_LIMIT)
            getString(R.string.breach_v4_address_limit_reached, PreferencesManager.BREACH_ADDRESS_LIMIT)
        else
            getString(R.string.breach_v4_btn_add_address_full)
        tvAddLabel.typeface = android.graphics.Typeface.DEFAULT
        tvAddLabel.setTextColor(
            ContextCompat.getColor(
                this,
                if (addresses.size >= PreferencesManager.BREACH_ADDRESS_LIMIT) R.color.v4_fg3 else R.color.v4_accent_deep
            )
        )
        addRow.findViewById<TextView>(R.id.tvRowValue).text = ""
        addRow.isEnabled = addresses.size < PreferencesManager.BREACH_ADDRESS_LIMIT
        addRow.setOnClickListener {
            if (addresses.size < PreferencesManager.BREACH_ADDRESS_LIMIT) showAddAddressDialog()
        }
        monitoredList.addView(addRow)

        btnAddAddress.isEnabled = addresses.size < PreferencesManager.BREACH_ADDRESS_LIMIT
    }

    private fun thinDivider(): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { marginStart = dp(46) }
        v.setBackgroundColor(ContextCompat.getColor(this, R.color.v4_hairline))
        return v
    }

    private fun showResults(result: BreachChecker.BreachResult) {
        renderHero(result.email, result)
        breachList.removeAllViews()
        if (result.isClean) {
            tvHistoryLabel.visibility = View.GONE
            return
        }
        tvHistoryLabel.visibility = View.VISIBLE
        for (breach in result.breaches) {
            val itemView = layoutInflater.inflate(R.layout.item_breach, breachList, false)
            bindBreachCard(itemView, breach)
            breachList.addView(itemView)
        }
    }

    private fun bindBreachCard(itemView: View, breach: BreachChecker.BreachInfo) {
        val sevTile = itemView.findViewById<View>(R.id.sevTile)
        val sevIcon = itemView.findViewById<ImageView>(R.id.sevIcon)
        val tvName = itemView.findViewById<TextView>(R.id.tvBreachName)
        val tvDate = itemView.findViewById<TextView>(R.id.tvBreachDate)
        val tvSev = itemView.findViewById<TextView>(R.id.tvSeverity)
        val chips = itemView.findViewById<LinearLayout>(R.id.chipContainer)
        val actionRow = itemView.findViewById<LinearLayout>(R.id.actionRow)
        val btnChangePwd = itemView.findViewById<Button>(R.id.btnChangePassword)
        val btnResolve = itemView.findViewById<Button>(R.id.btnMarkResolved)

        tvName.text = breach.title.ifBlank { breach.name }
        tvDate.text = getString(R.string.breach_v4_disclosed_prefix, breach.breachDate)

        if (breach.resolved) {
            sevTile.setBackgroundResource(R.drawable.bg_v4_breach_tile_ok)
            sevIcon.setImageResource(R.drawable.ic_glyph_check)
            sevIcon.setColorFilter(ContextCompat.getColor(this, R.color.v4_ok))
            tvSev.setBackgroundResource(R.drawable.bg_v4_perm_pill_ok)
            tvSev.setTextColor(ContextCompat.getColor(this, R.color.v4_ok))
            tvSev.setText(R.string.breach_v4_pill_resolved)
            actionRow.visibility = View.GONE
        } else {
            sevIcon.setImageResource(R.drawable.ic_glyph_warn)
            when (breach.severity) {
                "HIGH" -> {
                    sevTile.setBackgroundResource(R.drawable.bg_v4_breach_tile_bad)
                    sevIcon.setColorFilter(ContextCompat.getColor(this, R.color.v4_bad))
                    tvSev.setBackgroundResource(R.drawable.bg_v4_perm_pill_bad)
                    tvSev.setTextColor(ContextCompat.getColor(this, R.color.v4_bad))
                    tvSev.setText(R.string.breach_v4_pill_action_needed)
                }
                "MEDIUM" -> {
                    sevTile.setBackgroundResource(R.drawable.bg_v4_breach_tile_warn)
                    sevIcon.setColorFilter(ContextCompat.getColor(this, R.color.v4_warn))
                    tvSev.setBackgroundResource(R.drawable.bg_v4_perm_pill_warn)
                    tvSev.setTextColor(ContextCompat.getColor(this, R.color.v4_warn))
                    tvSev.setText(R.string.breach_v4_pill_caution)
                }
                else -> {
                    sevTile.setBackgroundResource(R.drawable.bg_v4_breach_tile_warn)
                    sevIcon.setColorFilter(ContextCompat.getColor(this, R.color.v4_warn))
                    tvSev.setBackgroundResource(R.drawable.bg_v4_perm_pill_warn)
                    tvSev.setTextColor(ContextCompat.getColor(this, R.color.v4_warn))
                    tvSev.setText(R.string.breach_v4_pill_low)
                }
            }
            actionRow.visibility = View.VISIBLE
            if (breach.domain.isBlank()) {
                btnChangePwd.visibility = View.GONE
            } else {
                btnChangePwd.visibility = View.VISIBLE
                btnChangePwd.setOnClickListener {
                    val url = if (breach.domain.startsWith("http")) breach.domain
                    else "https://${breach.domain}"
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }.onFailure {
                        Toast.makeText(this, R.string.breach_v4_err_open_url, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            btnResolve.setOnClickListener {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        BreachChecker.markResolved(this@BreachCheckerActivity, breach.id, true)
                    }
                    refreshSelectedFromDb()
                }
            }
        }

        chips.removeAllViews()
        breach.dataExposed.forEachIndexed { i, label -> chips.addView(makeChip(label, i > 0)) }
    }

    private suspend fun refreshSelectedFromDb() {
        val email = currentSelectedEmail() ?: return
        val fresh = withContext(Dispatchers.IO) {
            BreachChecker.getCached(this@BreachCheckerActivity, email)
        } ?: return
        resultsByEmail[email] = fresh
        showResults(fresh)
        renderMonitoredAddresses()
    }

    private fun makeChip(text: String, withMarginStart: Boolean): TextView {
        val chip = TextView(this).apply {
            this.text = text
            setBackgroundResource(R.drawable.bg_v4_perm_tag)
            setTextColor(ContextCompat.getColor(this@BreachCheckerActivity, R.color.v4_fg1))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        if (withMarginStart) lp.marginStart = dp(6)
        chip.layoutParams = lp
        return chip
    }

    private fun renderHero(email: String?, result: BreachChecker.BreachResult?) {
        val display = email ?: FirebaseAuth.getInstance().currentUser?.email ?: ""
        tvHeroEmail.text = display.ifBlank { getString(R.string.breach_v4_no_email) }

        when {
            result == null -> {
                heroDot.setBackgroundResource(R.drawable.bg_v4_sev_dot_warn)
                tvHeroEyebrow.setText(R.string.breach_v4_eyebrow_idle)
                tvHeroEyebrow.setTextColor(ContextCompat.getColor(this, R.color.v4_fg3))
                tvHeroMeta.setText(R.string.breach_v4_meta_idle)
            }
            result.isClean -> {
                heroDot.setBackgroundResource(R.drawable.bg_v4_sev_dot_ok)
                tvHeroEyebrow.setText(R.string.breach_v4_eyebrow_clean)
                tvHeroEyebrow.setTextColor(ContextCompat.getColor(this, R.color.v4_ok))
                tvHeroMeta.text = getString(
                    R.string.breach_v4_meta_last_checked,
                    formatRelativeTime(result.checkedAt)
                )
            }
            else -> {
                heroDot.setBackgroundResource(R.drawable.bg_v4_sev_dot_bad)
                tvHeroEyebrow.text = resources.getQuantityString(
                    R.plurals.breach_v4_eyebrow_exposed, result.breachCount, result.breachCount
                )
                tvHeroEyebrow.setTextColor(ContextCompat.getColor(this, R.color.v4_bad))
                tvHeroMeta.text = getString(
                    R.string.breach_v4_meta_last_checked,
                    formatRelativeTime(result.checkedAt)
                )
            }
        }
    }

    private fun formatRelativeTime(checkedAt: Long): String {
        val delta = System.currentTimeMillis() - checkedAt
        if (delta < TimeUnit.MINUTES.toMillis(1)) return getString(R.string.breach_v4_time_just_now)
        val mins = TimeUnit.MILLISECONDS.toMinutes(delta)
        if (mins < 60) return resources.getQuantityString(
            R.plurals.breach_v4_time_minutes, mins.toInt(), mins.toInt()
        )
        val hours = TimeUnit.MILLISECONDS.toHours(delta)
        if (hours < 24) return resources.getQuantityString(
            R.plurals.breach_v4_time_hours, hours.toInt(), hours.toInt()
        )
        val days = TimeUnit.MILLISECONDS.toDays(delta)
        return resources.getQuantityString(
            R.plurals.breach_v4_time_days, days.toInt(), days.toInt()
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun currentSelectedEmail(): String? {
        val sel = PreferencesManager.getSelectedBreachAddress(this)
        if (sel.isNotBlank()) return sel
        return PreferencesManager.getBreachAddresses(this).firstOrNull()
    }

    // Bundled HIBP API key for demo / tester builds. The value is injected at build time from
    // the gitignored local.properties (HIBP_API_KEY) via BuildConfig, so it is NOT committed to
    // source control. Blank when unconfigured — the screen then prompts to add a key.
    private fun getApiKey(): String? = BuildConfig.HIBP_API_KEY.ifBlank { null }

    @Suppress("unused")
    private fun saveApiKey(key: String) { /* no-op: key is provided at build time */ }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
