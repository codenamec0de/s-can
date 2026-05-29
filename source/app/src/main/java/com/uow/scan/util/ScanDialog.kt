package com.uow.scan.util

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.uow.scan.R

/**
 * Dialogs that match the app's V4 design language (rounded dark surface, montserrat, accent
 * buttons) instead of the stock Material AlertDialog look. [input]/[confirm]/[notice] render
 * the shared [R.layout.dialog_scan] and toggle the rows they need; [choice] uses a list layout.
 */
object ScanDialog {

    /** A prompt with a single styled input field. [onConfirm] receives the trimmed text. */
    fun input(
        context: Context,
        title: String,
        hint: String,
        confirmText: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        prefill: String = "",
        onConfirm: (String) -> Unit,
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_scan, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = title
        val field = view.findViewById<EditText>(R.id.dialogInput).apply {
            visibility = View.VISIBLE
            this.inputType = inputType
            this.hint = hint
            setText(prefill)
            setSelection(text?.length ?: 0)
        }
        val dialog = build(context, view)
        view.findViewById<MaterialButton>(R.id.dialogConfirm).apply {
            text = confirmText
            setOnClickListener {
                onConfirm(field.text?.toString()?.trim().orEmpty())
                dialog.dismiss()
            }
        }
        view.findViewById<MaterialButton>(R.id.dialogCancel).setOnClickListener { dialog.dismiss() }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        field.requestFocus()
    }

    /** A confirmation with a title + body message and a confirm/cancel pair. */
    fun confirm(
        context: Context,
        title: String,
        message: String,
        confirmText: String,
        cancelText: String? = null,
        onConfirm: () -> Unit,
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_scan, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = title
        view.findViewById<TextView>(R.id.dialogMessage).apply {
            visibility = View.VISIBLE
            text = message
        }
        val dialog = build(context, view)
        view.findViewById<MaterialButton>(R.id.dialogConfirm).apply {
            text = confirmText
            setOnClickListener {
                onConfirm()
                dialog.dismiss()
            }
        }
        view.findViewById<MaterialButton>(R.id.dialogCancel).apply {
            if (cancelText != null) text = cancelText
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
    }

    /** An informational dialog with a single dismiss button (no cancel). */
    fun notice(
        context: Context,
        title: String,
        message: String,
        buttonText: String = "OK",
        onDismiss: () -> Unit = {},
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_scan, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = title
        view.findViewById<TextView>(R.id.dialogMessage).apply {
            visibility = View.VISIBLE
            text = message
        }
        view.findViewById<MaterialButton>(R.id.dialogCancel).visibility = View.GONE
        val dialog = build(context, view)
        view.findViewById<MaterialButton>(R.id.dialogConfirm).apply {
            text = buttonText
            setOnClickListener {
                onDismiss()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** A single-select list. [onSelect] receives the tapped index. */
    fun choice(
        context: Context,
        title: String,
        items: List<String>,
        onSelect: (Int) -> Unit,
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_scan_choice, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = title
        val container = view.findViewById<LinearLayout>(R.id.dialogChoiceContainer)
        val dialog = build(context, view)

        val rippleAttr = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val ripple = rippleAttr.getResourceId(0, 0)
        rippleAttr.recycle()
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        items.forEachIndexed { index, label ->
            val row = TextView(context).apply {
                text = label
                typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.montserrat)
                setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.v4_fg0))
                textSize = 15f
                setPadding(dp(6), dp(14), dp(6), dp(14))
                if (ripple != 0) setBackgroundResource(ripple)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onSelect(index)
                    dialog.dismiss()
                }
            }
            container.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        dialog.show()
    }

    private fun build(context: Context, view: View): AlertDialog {
        val dialog = AlertDialog.Builder(context).setView(view).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // Pin to ~88% width so the rounded card sits centered with even side gaps, regardless
        // of the platform dialog's default sizing.
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (context.resources.displayMetrics.widthPixels * 0.88f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return dialog
    }
}
