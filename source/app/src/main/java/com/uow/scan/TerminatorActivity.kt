package com.uow.scan

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.uow.scan.data.ScanDatabase
import com.uow.scan.util.TerminatorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TerminatorActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var btnModeManual: LinearLayout
    private lateinit var btnModeAuto: LinearLayout
    private lateinit var ivModeManualCheck: ImageView
    private lateinit var ivModeAutoCheck: ImageView
    private lateinit var cardShizukuStatus: CardView
    private lateinit var ivShizukuStatus: ImageView
    private lateinit var tvShizukuStatus: TextView
    private lateinit var btnShizukuSetup: Button
    private lateinit var tvAutoModeDesc: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmptyApps: TextView
    private lateinit var appList: LinearLayout

    private var currentMode = "manual"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminator)

        initViews()
        setupListeners()
        loadMode()
        loadApps()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        btnModeManual = findViewById(R.id.btnModeManual)
        btnModeAuto = findViewById(R.id.btnModeAuto)
        ivModeManualCheck = findViewById(R.id.ivModeManualCheck)
        ivModeAutoCheck = findViewById(R.id.ivModeAutoCheck)
        cardShizukuStatus = findViewById(R.id.cardShizukuStatus)
        ivShizukuStatus = findViewById(R.id.ivShizukuStatus)
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        btnShizukuSetup = findViewById(R.id.btnShizukuSetup)
        tvAutoModeDesc = findViewById(R.id.tvAutoModeDesc)
        progressBar = findViewById(R.id.progressBar)
        tvEmptyApps = findViewById(R.id.tvEmptyApps)
        appList = findViewById(R.id.appList)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        btnModeManual.setOnClickListener {
            selectMode("manual")
        }

        btnModeAuto.setOnClickListener {
            selectMode("auto")
        }

        btnShizukuSetup.setOnClickListener {
            openShizukuSetup()
        }
    }

    private fun loadMode() {
        currentMode = TerminatorManager.getMode(this)
        updateModeUI()
    }

    private fun selectMode(mode: String) {
        currentMode = mode
        TerminatorManager.setMode(this, mode)
        updateModeUI()

        if (mode == "auto") {
            checkShizukuStatus()
        }
    }

    private fun updateModeUI() {
        val selectedColor = ContextCompat.getColor(this, R.color.primary)
        val unselectedColor = ContextCompat.getColor(this, R.color.text_secondary_dark)

        if (currentMode == "manual") {
            ivModeManualCheck.setImageResource(R.drawable.ic_check_circle)
            ivModeManualCheck.setColorFilter(selectedColor)
            ivModeAutoCheck.setImageResource(R.drawable.ic_circle_pending)
            ivModeAutoCheck.setColorFilter(unselectedColor)
            cardShizukuStatus.visibility = View.GONE
        } else {
            ivModeManualCheck.setImageResource(R.drawable.ic_circle_pending)
            ivModeManualCheck.setColorFilter(unselectedColor)
            ivModeAutoCheck.setImageResource(R.drawable.ic_check_circle)
            ivModeAutoCheck.setColorFilter(selectedColor)
            cardShizukuStatus.visibility = View.VISIBLE
            checkShizukuStatus()
        }
    }

    private fun checkShizukuStatus() {
        val shizukuInstalled = isShizukuInstalled()
        val shizukuRunning = isShizukuRunning()

        when {
            !shizukuInstalled -> {
                ivShizukuStatus.setImageResource(R.drawable.ic_warning_circle)
                ivShizukuStatus.setColorFilter(ContextCompat.getColor(this, R.color.risk_high))
                tvShizukuStatus.text = "Shizuku is not installed"
                btnShizukuSetup.text = "Install"
            }
            !shizukuRunning -> {
                ivShizukuStatus.setImageResource(R.drawable.ic_warning_circle)
                ivShizukuStatus.setColorFilter(ContextCompat.getColor(this, R.color.risk_medium))
                tvShizukuStatus.text = "Shizuku is installed but not running"
                btnShizukuSetup.text = "Open"
            }
            else -> {
                ivShizukuStatus.setImageResource(R.drawable.ic_check_circle)
                ivShizukuStatus.setColorFilter(ContextCompat.getColor(this, R.color.risk_low))
                tvShizukuStatus.text = "Shizuku is active"
                btnShizukuSetup.visibility = View.GONE
            }
        }
    }

    private fun isShizukuInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun isShizukuRunning(): Boolean {
        // Basic check - full Shizuku SDK integration would use Shizuku.pingBinder()
        // For now, check if the binder service is accessible
        return try {
            val shizukuPkg = packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            shizukuPkg.applicationInfo?.enabled == true
        } catch (e: Exception) {
            false
        }
    }

    private fun openShizukuSetup() {
        if (!isShizukuInstalled()) {
            // Open Play Store
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")))
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")))
            }
        } else {
            // Open Shizuku app
            val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) {
                startActivity(intent)
            } else {
                Toast.makeText(this, "Could not open Shizuku", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE
        appList.removeAllViews()

        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val db = ScanDatabase.getInstance(this@TerminatorActivity)
                val scanResults = db.scanResultDao().getAll()

                // Filter to apps that have at least one revocable permission
                scanResults.filter { entity ->
                    val perms = entity.permissions.split(",").filter { it.isNotBlank() }
                    perms.any { it in TerminatorManager.REVOCABLE_PERMISSIONS }
                }.sortedByDescending { entity ->
                    val perms = entity.permissions.split(",").filter { it.isNotBlank() }
                    perms.count { it in TerminatorManager.REVOCABLE_PERMISSIONS }
                }
            }

            progressBar.visibility = View.GONE

            if (apps.isEmpty()) {
                tvEmptyApps.visibility = View.VISIBLE
                return@launch
            }

            val pm = packageManager
            for (entity in apps) {
                val itemView = layoutInflater.inflate(R.layout.item_terminator_app, appList, false)

                val ivAppIcon = itemView.findViewById<ImageView>(R.id.ivAppIcon)
                val tvAppName = itemView.findViewById<TextView>(R.id.tvAppName)
                val tvAppPerms = itemView.findViewById<TextView>(R.id.tvAppPerms)
                val switchWatch = itemView.findViewById<SwitchCompat>(R.id.switchWatch)

                // Load app icon
                try {
                    ivAppIcon.setImageDrawable(pm.getApplicationIcon(entity.packageName))
                } catch (e: PackageManager.NameNotFoundException) {
                    ivAppIcon.setImageResource(R.drawable.ic_shield)
                }

                tvAppName.text = entity.appName

                val perms = entity.permissions.split(",").filter { it.isNotBlank() }
                val revocableLabels = TerminatorManager.getRevocablePermLabels(perms)
                tvAppPerms.text = revocableLabels.joinToString(", ")

                // Set toggle state
                switchWatch.isChecked = TerminatorManager.isAppWatched(this@TerminatorActivity, entity.packageName)

                switchWatch.setOnCheckedChangeListener { _, isChecked ->
                    TerminatorManager.setAppWatched(this@TerminatorActivity, entity.packageName, isChecked)
                    if (isChecked) {
                        Toast.makeText(
                            this@TerminatorActivity,
                            "${entity.appName} added to watchlist",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                appList.addView(itemView)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (currentMode == "auto") {
            checkShizukuStatus()
        }
    }
}
