package ru.fortport.client

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Показывает причину аварийного завершения. Запускается в отдельном процессе (:crash), поэтому открывается,
 * даже если основное приложение падает прямо при старте. Текст можно скопировать и прислать разработчику.
 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent.getStringExtra("trace").orEmpty().ifEmpty { "(нет данных)" }
        val d = resources.displayMetrics.density
        val pad = (12 * d).toInt()

        val title = TextView(this).apply {
            text = "Приложение аварийно закрылось"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(pad, pad, pad, pad / 2)
        }
        val body = TextView(this).apply {
            text = trace
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(pad, pad / 2, pad, pad)
        }
        val copy = Button(this).apply {
            text = "Скопировать"
            setOnClickListener {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("crash", trace))
                Toast.makeText(this@CrashActivity, "Скопировано", Toast.LENGTH_SHORT).show()
            }
        }
        val close = Button(this).apply { text = "Закрыть"; setOnClickListener { finishAndRemoveTask() } }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            addView(close, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(ScrollView(this@CrashActivity).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root)
    }
}
