package com.osfans.trime.ime.core

/**
 * 大千鍵位 raw ↔ 注音對照的單一真相（同 iridium_bpmf schema 的 preedit_format xlit 表）。
 * 供組字預覽（EditorInstance）與自訂短語（CustomPhraseActivity）共用，避免多份手抄漂移。
 *
 * 空白=一聲字根：raw 空格顯示為 ˉ（preedit 的音節分隔符是 U+2002 en space，不衝突）。
 */
object DaqianCode {
    const val RAW = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347"
    const val BPMF = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˉˊˇˋ˙"

    /**
     * raw 大千碼 → 注音字面。游標移進組字中間時 librime 只格式化有 translator 的分段，
     * 游標後的分段以原始碼呈現——顯示前補轉；注音字元不在表內原樣保留，冪等。
     * 殘留的軟游標「‸」(U+2038) 直接濾掉。
     */
    fun toBpmf(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c == '‸') continue // ‸
            val i = RAW.indexOf(c)
            sb.append(if (i >= 0) BPMF[i] else c)
        }
        return sb.toString()
    }
}
