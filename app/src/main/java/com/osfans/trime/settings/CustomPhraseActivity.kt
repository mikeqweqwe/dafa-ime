package com.osfans.trime.settings

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.blankj.utilcode.util.ToastUtils
import com.osfans.trime.data.AppPrefs
import com.osfans.trime.util.RimeUtils
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import timber.log.Timber

/**
 * 自訂短語管理：字詞＋輸入碼（注音或英文字母／數字）寫入 RIME custom_phrase 表
 * （iridium_bpmf_phrase.txt，schema 的 table_translator@custom_phrase 已掛載，
 * 候選排在一般組字之前）。輸入碼以大千鍵位 raw 儲存，注音在此轉換。
 */
class CustomPhraseActivity : AppCompatActivity() {

    private data class Entry(val text: String, val code: String)

    private val entries = mutableListOf<Entry>()
    private var dirty = false
    private lateinit var adapter: ArrayAdapter<String>

    private val phraseFile: File
        get() = File(AppPrefs.defaultInstance().conf.sharedDataDir, PHRASE_FILE_NAME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "自訂短語"

        val dp = resources.displayMetrics.density
        fun Int.px() = (this * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.px(), 8.px(), 16.px(), 8.px())
        }

        root.addView(
            TextView(this).apply {
                text = "打出輸入碼時字詞會出現在候選列。輸入碼欄會自動切成英文鍵盤：" +
                    "直接打字母或數字（bbbb）；要用注音當碼，按注音在鍵盤上的相同位置" +
                    "（ㄏㄆ 就按 ㄏ、ㄆ 位置的鍵）。長按條目可刪除。"
                setPadding(0, 8.px(), 0, 8.px())
            }
        )

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        val listView = ListView(this).apply {
            adapter = this@CustomPhraseActivity.adapter
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(listView)

        val textInput = EditText(this).apply {
            hint = "字詞"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
        }
        val codeInput = EditText(this).apply {
            hint = "輸入碼"
            // VISIBLE_PASSWORD：本輸入法對密碼欄自動切英文鍵盤——輸入碼是鍵位字母，
            // 讓使用者不必手動切換；注音碼＝按注音在鍵盤上的同位置（大千鍵位與 qwerty 同位）
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }
        val addButton = Button(this).apply { text = "新增" }
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(textInput)
                addView(codeInput)
                addView(addButton)
            }
        )
        setContentView(root)

        addButton.setOnClickListener {
            val text = textInput.text.toString().trim()
            val rawCode = codeInput.text.toString().trim()
            if (text.isEmpty() || rawCode.isEmpty()) {
                ToastUtils.showShort("字詞與輸入碼都要填")
                return@setOnClickListener
            }
            // Tab／換行是 tabledb 檔案的欄位／行分隔符，字詞含它們會破壞往返
            if (text.any { it == '\t' || it == '\n' || it == '\r' }) {
                ToastUtils.showShort("字詞不能包含 Tab 或換行")
                return@setOnClickListener
            }
            val code = normalizeCode(rawCode)
            if (code == null) {
                ToastUtils.showLong("輸入碼只能用注音符號、英文字母或數字（不含一聲／空白）")
                return@setOnClickListener
            }
            // 先入列再存檔，失敗回滾：確保記憶體與檔案一致
            entries.add(Entry(text, code))
            if (!save()) {
                entries.removeAt(entries.size - 1)
                return@setOnClickListener
            }
            refreshList()
            textInput.text.clear()
            codeInput.text.clear()
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val entry = entries[position]
            AlertDialog.Builder(this)
                .setMessage("刪除「${entry.text}」（${codeToDisplay(entry.code)}）？")
                .setPositiveButton("刪除") { _, _ ->
                    val removed = entries.removeAt(position)
                    if (save()) refreshList() else entries.add(position, removed)
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }

        load()
        refreshList()
    }

    // 變更過才重新部署（幾秒鐘，完成有 toast）。必須用 GlobalScope：
    // 退出頁面時 onStop 後緊接 onDestroy，綁 Activity 的 scope 會把部署取消掉
    @OptIn(DelicateCoroutinesApi::class)
    override fun onStop() {
        super.onStop()
        if (dirty) {
            dirty = false
            GlobalScope.launch { RimeUtils.deploy(applicationContext) }
        }
    }

    private fun load() {
        entries.clear()
        val file = phraseFile
        if (!file.exists()) return
        try {
            file.forEachLine { line ->
                // 只跳 tabledb 表頭（"# Rime table"／"#@/..."），不誤殺以 # 開頭的使用者字詞
                if (line.isBlank() || line.startsWith("#@") || line.startsWith("# ")) {
                    return@forEachLine
                }
                val parts = line.split('\t')
                if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    entries.add(Entry(parts[0], parts[1]))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "load custom phrase failed")
        }
    }

    /** @return 寫檔成功與否；失敗時 toast 提示、不設 dirty（不觸發部署） */
    private fun save(): Boolean {
        val sb = StringBuilder(FILE_HEADER)
        for (e in entries) sb.append(e.text).append('\t').append(e.code).append("\t1\n")
        return try {
            phraseFile.apply { parentFile?.mkdirs() }.writeText(sb.toString())
            dirty = true
            true
        } catch (e: Exception) {
            Timber.e(e, "save custom phrase failed")
            ToastUtils.showLong("寫入失敗，請檢查共享資料夾設定")
            false
        }
    }

    private fun refreshList() {
        adapter.clear()
        // 附上 raw 鍵位碼：ColorOS 主題字型缺部分注音 glyph（如 ㄖ 顯示成方框），
        // 括號裡的英文碼永遠可讀
        adapter.addAll(entries.map { "${it.text}    ←  ${codeToDisplay(it.code)}（${it.code}）" })
    }

    companion object {
        private const val PHRASE_FILE_NAME = "iridium_bpmf_phrase.txt"
        private const val FILE_HEADER =
            "# Rime table\n#@/db_name\tiridium_bpmf_phrase.txt\n#@/db_type\ttabledb\n"

        // 大千鍵位對照（注音→raw）；一聲 ˉ＝空白會破壞 tab 分隔格式，不開放
        private const val BPMF = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˊˇˋ˙"
        private const val RAW = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-6347"

        /** 注音轉大千 raw；英文字母轉小寫、數字原樣；含其他字元回 null */
        private fun normalizeCode(s: String): String? {
            val sb = StringBuilder()
            for (c in s) {
                val i = BPMF.indexOf(c)
                when {
                    i >= 0 -> sb.append(RAW[i])
                    c in 'a'..'z' || c in '0'..'9' -> sb.append(c)
                    c in 'A'..'Z' -> sb.append(c.lowercaseChar())
                    else -> return null
                }
            }
            return sb.toString()
        }

        /** raw 碼顯示為注音（英文字根位優先顯示注音；表外字元原樣） */
        private fun codeToDisplay(code: String): String {
            val sb = StringBuilder()
            for (c in code) {
                val i = RAW.indexOf(c)
                sb.append(if (i >= 0) BPMF[i] else c)
            }
            return sb.toString()
        }
    }
}
