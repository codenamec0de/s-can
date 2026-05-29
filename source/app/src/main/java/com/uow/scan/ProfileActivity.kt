package com.uow.scan

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.uow.scan.util.GoogleProfilePhotoFetcher
import com.uow.scan.util.PreferencesManager
import com.uow.scan.util.ScanDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProfileActivity : AppCompatActivity() {

    private data class Session(val device: String, val loc: String, val current: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        findViewById<TextView>(R.id.tvTopBarTitle).setText(R.string.profile_v4_title)
        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        renderIdentity()
        // Pull the live photo URL from Google's userinfo endpoint — backstop for
        // accounts where FirebaseUser.photoUrl and the JWT picture claim are silent.
        lifecycleScope.launch {
            val fetched = GoogleProfilePhotoFetcher.refresh(this@ProfileActivity)
            if (!fetched.isNullOrBlank() && !isFinishing) renderIdentity()
        }
        renderPasswordRow()
        wire2faSwitch()
        renderSessions()

        findViewById<FrameLayout>(R.id.btnEdit).setOnClickListener { showEditDialog() }
        findViewById<View>(R.id.rowPassword).setOnClickListener { showChangePasswordDialog() }
        findViewById<FrameLayout>(R.id.btnDeleteAccount).setOnClickListener { confirmDeleteAccount() }
    }

    private fun renderIdentity() {
        val user = FirebaseAuth.getInstance().currentUser
        val displayName = user?.displayName?.takeIf { it.isNotBlank() }
            ?: user?.email?.substringBefore("@")?.replaceFirstChar { it.uppercase() }
            ?: "Anonymous"
        val email = user?.email ?: "Not signed in"

        findViewById<TextView>(R.id.tvProfileName).text = displayName
        findViewById<TextView>(R.id.tvProfileEmail).text = email
        findViewById<TextView>(R.id.tvProfileEmailValue).text = email

        val tvInitial = findViewById<TextView>(R.id.tvProfileBigInitial)
        val ivPhoto = findViewById<ImageView>(R.id.ivProfileBigPhoto)
        tvInitial.text = displayName.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

        val photoUri = user?.photoUrl
            ?: user?.providerData?.firstNotNullOfOrNull { it.photoUrl }
            ?: GoogleSignIn.getLastSignedInAccount(this)?.photoUrl
            ?: PreferencesManager.getGoogleProfilePhotoUrl(this)
                ?.let { android.net.Uri.parse(it) }
        if (photoUri != null) {
            tvInitial.visibility = View.GONE
            ivPhoto.visibility = View.VISIBLE
            Glide.with(this)
                .load(photoUri)
                .circleCrop()
                .into(ivPhoto)
        } else {
            ivPhoto.visibility = View.GONE
            ivPhoto.setImageDrawable(null)
            tvInitial.visibility = View.VISIBLE
        }
    }

    private fun renderPasswordRow() {
        val updatedAt = FirebaseAuth.getInstance().currentUser?.metadata?.lastSignInTimestamp
        val tv = findViewById<TextView>(R.id.tvPasswordMeta)
        tv.text = if (updatedAt != null) {
            val days = ((System.currentTimeMillis() - updatedAt) / (24L * 60 * 60 * 1000)).toInt()
            getString(R.string.profile_v4_row_password_meta,
                if (days <= 0) "today" else "$days d ago")
        } else {
            getString(R.string.profile_v4_row_password_meta, "—")
        }
    }

    private fun wire2faSwitch() {
        // 2FA enrolment isn't implemented yet, so the switch starts OFF and is presented as a
        // roadmap item rather than a working toggle (avoids implying protection that isn't there).
        val sw = findViewById<SwitchCompat>(R.id.switch2fa)
        sw.isChecked = false
        sw.setOnCheckedChangeListener { btn, checked ->
            if (checked) {
                ScanDialog.notice(
                    context = this,
                    title = getString(R.string.profile_v4_row_2fa),
                    message = "Two-factor authentication is coming soon — it'll build on Firebase Auth multi-factor.",
                )
                btn.isChecked = false // not enabled yet; revert so the UI stays honest
            }
        }
    }

    private fun renderSessions() {
        val container = findViewById<LinearLayout>(R.id.sessionsContainer)
        val device = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val locale = Locale.getDefault().displayCountry.ifBlank { Locale.getDefault().country.ifBlank { "—" } }
        val now = SimpleDateFormat("d MMM, HH:mm", Locale.US).format(Date())
        val sessions = listOf(
            Session(device, getString(R.string.profile_v4_session_current, locale + " · " + now), true),
        )
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for ((index, s) in sessions.withIndex()) {
            val v = inflater.inflate(R.layout.item_v4_session_row, container, false)
            val dot = v.findViewById<View>(R.id.sessionDot)
            val tvDevice = v.findViewById<TextView>(R.id.tvSessionDevice)
            val tvLoc = v.findViewById<TextView>(R.id.tvSessionLoc)
            val tvAction = v.findViewById<TextView>(R.id.tvSessionAction)
            val divider = v.findViewById<View>(R.id.sessionDivider)
            dot.setBackgroundResource(if (s.current) R.drawable.bg_v4_sev_dot_ok else R.drawable.bg_v4_sev_dot_warn)
            tvDevice.text = s.device
            tvLoc.text = s.loc
            tvAction.visibility = if (s.current) View.GONE else View.VISIBLE
            tvAction.text = getString(R.string.profile_v4_session_signout)
            divider.visibility = if (index == sessions.size - 1) View.GONE else View.VISIBLE
            container.addView(v)
        }
    }

    private fun showEditDialog() {
        ScanDialog.notice(
            context = this,
            title = getString(R.string.profile_v4_btn_edit),
            message = "In-app profile editing is coming soon. For now, manage your name and avatar " +
                "through your Google account.",
        )
    }

    private fun showChangePasswordDialog() {
        val email = FirebaseAuth.getInstance().currentUser?.email
        if (email.isNullOrBlank()) {
            ScanDialog.notice(
                context = this,
                title = getString(R.string.profile_v4_row_password),
                message = "No email on file — sign in with email to enable password reset.",
            )
            return
        }
        ScanDialog.confirm(
            context = this,
            title = getString(R.string.profile_v4_row_password),
            message = "Send a password reset link to $email?",
            confirmText = "Send",
        ) {
            FirebaseAuth.getInstance().sendPasswordResetEmail(email)
        }
    }

    private fun confirmDeleteAccount() {
        ScanDialog.confirm(
            context = this,
            title = getString(R.string.profile_v4_btn_delete),
            message = "This permanently removes your S'CAN account and clears every preference, scan, " +
                "and verdict on this device. This can't be undone.",
            confirmText = "Delete",
        ) { performDelete() }
    }

    private fun performDelete() {
        val auth = FirebaseAuth.getInstance()
        // Best-effort delete; ignore failures and still wipe local state so the UX never gets stuck.
        auth.currentUser?.delete()
        auth.signOut()
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        GoogleSignIn.getClient(this, gso).signOut()
        PreferencesManager.clearAll(this)

        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finishAffinity()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
