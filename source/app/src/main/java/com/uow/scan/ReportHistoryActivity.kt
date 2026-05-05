package com.uow.scan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists every PDF report saved under `<external-files>/Documents/SCAN Reports/`.
 *
 * The directory is the primary save location used by [com.uow.scan.util.PdfReportGenerator].
 * Each row lets the user open, share or delete a past report. The list is refreshed
 * in `onResume()` so newly generated reports show up automatically after returning
 * from [ExportReportActivity].
 */
class ReportHistoryActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvSubtitle: TextView

    private val adapter = ReportAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report_history)

        btnBack = findViewById(R.id.btnBack)
        recyclerView = findViewById(R.id.recyclerView)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvSubtitle = findViewById(R.id.tvSubtitle)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnBack.setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
    }

    override fun onResume() {
        super.onResume()
        loadReports()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    private fun reportsDir(): File {
        val base = getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
        return File(base, "SCAN Reports").apply { mkdirs() }
    }

    private fun loadReports() {
        val files = reportsDir().listFiles { f -> f.isFile && f.name.endsWith(".pdf") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        adapter.submit(files)

        if (files.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            tvSubtitle.text = "No reports yet"
        } else {
            tvEmpty.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            tvSubtitle.text = "${files.size} report${if (files.size == 1) "" else "s"} saved"
        }
    }

    private fun openReport(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No PDF viewer installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareReport(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "S'CAN Security Report")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Share Report"))
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteReport(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete report?")
            .setMessage("${file.name} will be permanently removed from this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                if (file.delete()) {
                    Toast.makeText(this, "Report deleted", Toast.LENGTH_SHORT).show()
                    loadReports()
                } else {
                    Toast.makeText(this, "Could not delete file", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    // -------------------------------------------------------------------------
    // Adapter
    // -------------------------------------------------------------------------

    private inner class ReportAdapter : RecyclerView.Adapter<ReportAdapter.VH>() {

        private val items = mutableListOf<File>()
        private val dateFmt = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())

        fun submit(newItems: List<File>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_report_history, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName: TextView = itemView.findViewById(R.id.tvName)
            private val tvMeta: TextView = itemView.findViewById(R.id.tvMeta)
            private val btnOpen: TextView = itemView.findViewById(R.id.btnOpen)
            private val btnShare: TextView = itemView.findViewById(R.id.btnShare)
            private val btnDelete: TextView = itemView.findViewById(R.id.btnDelete)

            fun bind(file: File) {
                tvName.text = file.name
                val size = formatBytes(file.length())
                val when_ = dateFmt.format(Date(file.lastModified()))
                tvMeta.text = "$when_  •  $size"

                itemView.setOnClickListener { openReport(file) }
                btnOpen.setOnClickListener { openReport(file) }
                btnShare.setOnClickListener { shareReport(file) }
                btnDelete.setOnClickListener { deleteReport(file) }
            }
        }

        private fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
            val mb = kb / 1024.0
            return String.format(Locale.getDefault(), "%.1f MB", mb)
        }
    }
}
