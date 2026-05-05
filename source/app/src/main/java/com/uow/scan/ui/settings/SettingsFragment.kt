package com.uow.scan.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.uow.scan.AboutActivity
import com.uow.scan.BreachCheckerActivity
import com.uow.scan.BuildConfig
import com.uow.scan.DataStorageActivity
import com.uow.scan.ExportReportActivity
import com.uow.scan.LoginActivity
import com.uow.scan.NotificationsActivity
import com.uow.scan.PrivacyPolicyActivity
import com.uow.scan.ProfileActivity
import com.uow.scan.R
import com.uow.scan.ScanScheduleActivity
import com.uow.scan.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsFragment : Fragment() {

    // Profile card
    private lateinit var cardProfile: LinearLayout
    private lateinit var ivProfilePhoto: ImageView
    private lateinit var tvProfileInitial: TextView
    private lateinit var tvUserEmail: TextView

    // Tools section

    // Account section
    private lateinit var btnProfile: LinearLayout
    private lateinit var btnNotifications: LinearLayout
    private lateinit var btnScanSchedule: LinearLayout
    private lateinit var btnDataStorage: LinearLayout
    private lateinit var tvDataStorageDesc: TextView

    // General section
    private lateinit var btnAbout: LinearLayout
    private lateinit var btnPrivacy: LinearLayout
    private lateinit var btnExportReport: LinearLayout
    private lateinit var btnBreachChecker: LinearLayout

    // Footer
    private lateinit var btnLogout: LinearLayout
    private lateinit var tvVersionFooter: TextView

    private lateinit var auth: FirebaseAuth

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        auth = FirebaseAuth.getInstance()
        bindViews(view)
        setupListeners()
        displayUserInfo()
        renderVersion()
        refreshDatabaseSize()
    }

    override fun onResume() {
        super.onResume()
        refreshDatabaseSize()
    }

    private fun bindViews(view: View) {
        cardProfile = view.findViewById(R.id.cardProfile)
        ivProfilePhoto = view.findViewById(R.id.ivProfilePhoto)
        tvProfileInitial = view.findViewById(R.id.tvProfileInitial)
        tvUserEmail = view.findViewById(R.id.tvUserEmail)

        btnProfile = view.findViewById(R.id.btnProfile)
        btnNotifications = view.findViewById(R.id.btnNotifications)
        btnScanSchedule = view.findViewById(R.id.btnScanSchedule)
        btnDataStorage = view.findViewById(R.id.btnDataStorage)
        tvDataStorageDesc = view.findViewById(R.id.tvDataStorageDesc)

        btnAbout = view.findViewById(R.id.btnAbout)
        btnPrivacy = view.findViewById(R.id.btnPrivacy)
        btnExportReport = view.findViewById(R.id.btnExportReport)
        btnBreachChecker = view.findViewById(R.id.btnBreachChecker)

        btnLogout = view.findViewById(R.id.btnLogout)
        tvVersionFooter = view.findViewById(R.id.tvVersionFooter)
    }

    private fun setupListeners() {
        cardProfile.setOnClickListener { launch(ProfileActivity::class.java) }

        btnProfile.setOnClickListener { launch(ProfileActivity::class.java) }
        btnNotifications.setOnClickListener { launch(NotificationsActivity::class.java) }
        btnScanSchedule.setOnClickListener { launch(ScanScheduleActivity::class.java) }
        btnDataStorage.setOnClickListener { launch(DataStorageActivity::class.java) }

        btnAbout.setOnClickListener { launch(AboutActivity::class.java) }
        btnPrivacy.setOnClickListener { launch(PrivacyPolicyActivity::class.java) }
        btnExportReport.setOnClickListener { launch(ExportReportActivity::class.java) }
        btnBreachChecker.setOnClickListener { launch(BreachCheckerActivity::class.java) }

        btnLogout.setOnClickListener { confirmLogout() }
    }

    private fun launch(cls: Class<*>) {
        startActivity(Intent(requireContext(), cls))
        @Suppress("DEPRECATION")
        requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private fun displayUserInfo() {
        val user = auth.currentUser
        val email = user?.email ?: user?.displayName ?: "Not signed in"
        tvUserEmail.text = email
        val initial = email.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
        tvProfileInitial.text = initial

        renderProfilePhoto()

        // FirebaseUser caches user metadata locally. If photoUrl wasn't available at
        // first sign-in (or the cache is stale), reload it from the server and re-render.
        if (user != null) {
            user.reload().addOnCompleteListener {
                if (isAdded) renderProfilePhoto()
            }
        }

        // FirebaseUser.photoUrl is sometimes null even when the Google account has a
        // photo. A silent Google sign-in refreshes the cached GoogleSignInAccount,
        // which carries the public profile photo URL we then mirror into prefs.
        refreshGooglePhotoSilently()
    }

    private fun refreshGooglePhotoSilently() {
        val ctx = requireContext()
        val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(
            com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN
        )
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(ctx, gso)
            .silentSignIn()
            .addOnSuccessListener { account ->
                val url = account?.photoUrl?.toString()
                if (!url.isNullOrBlank()) {
                    PreferencesManager.setGoogleProfilePhotoUrl(ctx, url)
                    if (isAdded) renderProfilePhoto()
                }
                // Even if the cached GoogleSignInAccount didn't carry a photoUrl,
                // hit Google's userinfo endpoint — that's the live source of truth
                // and surfaces the photo when other channels don't.
                lifecycleScope.launch {
                    val fetched = com.uow.scan.util.GoogleProfilePhotoFetcher.refresh(ctx)
                    if (!fetched.isNullOrBlank() && isAdded) renderProfilePhoto()
                }
            }
            // Failure here usually means the GoogleSignIn cache was cleared (Firebase
            // session can survive without it). User just needs to sign in again next
            // time; LoginActivity captures the photo URL on success.
    }

    private fun renderProfilePhoto() {
        val user = auth.currentUser
        val photoUri = user?.photoUrl
            ?: user?.providerData?.firstNotNullOfOrNull { it.photoUrl }
            ?: com.google.android.gms.auth.api.signin.GoogleSignIn
                .getLastSignedInAccount(requireContext())?.photoUrl
            ?: PreferencesManager.getGoogleProfilePhotoUrl(requireContext())
                ?.let { android.net.Uri.parse(it) }

        if (photoUri != null) {
            tvProfileInitial.visibility = View.GONE
            ivProfilePhoto.visibility = View.VISIBLE
            Glide.with(this)
                .load(photoUri)
                .circleCrop()
                .into(ivProfilePhoto)
        } else {
            ivProfilePhoto.visibility = View.GONE
            ivProfilePhoto.setImageDrawable(null)
            tvProfileInitial.visibility = View.VISIBLE
        }
    }

    private fun renderVersion() {
        tvVersionFooter.text = getString(R.string.settings_v4_version_format, BuildConfig.VERSION_NAME)
    }

    /**
     * Computes the on-disk size of the Room database (plus its WAL/SHM siblings)
     * and surfaces it as the Data &amp; Storage row description.
     */
    private fun refreshDatabaseSize() {
        val ctx = requireContext().applicationContext
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    val db = ctx.getDatabasePath("scan_db")
                    val wal = File(db.parentFile, "scan_db-wal")
                    val shm = File(db.parentFile, "scan_db-shm")
                    listOf(db, wal, shm).filter { it.exists() }.sumOf { it.length() }
                }.getOrDefault(0L)
            }
            tvDataStorageDesc.text = if (bytes > 0)
                getString(R.string.settings_v4_account_data_desc_format, formatBytes(bytes))
            else
                getString(R.string.settings_v4_account_data_desc_unknown)
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    private fun confirmLogout() {
        AlertDialog.Builder(requireContext())
            .setTitle("Log out?")
            .setMessage("You'll need to sign in again to access your audit data.")
            .setPositiveButton("Log out") { _, _ -> logout() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun logout() {
        auth.signOut()
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        GoogleSignIn.getClient(requireContext(), gso).signOut()
        PreferencesManager.clearAll(requireContext())

        val intent = Intent(requireContext(), LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        requireActivity().finish()
    }
}
