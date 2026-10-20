package com.osfans.trime

import android.app.Application
import android.os.Process
import android.util.Log
import cat.ereza.customactivityoncrash.config.CaocConfig
import com.osfans.trime.data.AppPrefs
import com.osfans.trime.settings.LogActivity
import timber.log.Timber

/**
 * Custom Application class.
 * Application class will only be created once when the app run,
 * so you can init a "global" class here, whose methods serve other
 * classes everywhere.
 */
class TrimeApplication : Application() {
    companion object {
        private var instance: TrimeApplication? = null
        private var lastPid: Int? = null

        fun getInstance() =
            instance ?: throw IllegalStateException("Trime application is not created!")

        fun getLastPid() = lastPid
    }

    override fun onCreate() {
        super.onCreate()
        CaocConfig.Builder
            .create()
            .errorActivity(LogActivity::class.java)
            .enabled(!BuildConfig.DEBUG)
            .apply()
        instance = this
        try {
            // 非 debuggable 版只記 WARN 以上：DebugTree 每筆 log 都 new Throwable() 抓 stack
            // 當 tag，按鍵／繪製／候選路徑每次觸發幾十筆，是打字卡頓的主因之一。
            // 保留 WARN/ERROR 讓設定頁「即時日誌」還看得到問題
            Timber.plant(if (BuildConfig.DEBUG) Timber.DebugTree() else WarnTree)
            val prefs = AppPrefs.initDefault(this)
            prefs.initDefaultPreferences()
            // 遷移 legacy /sdcard/rime：該目錄跨安裝會孤兒化（MediaProvider owner 消失、
            // 無儲存權限讀寫不到）→ 部署失敗 → 鍵盤崩潰。必須在任何 Config/Rime 載入前改掉
            // （Config 把目錄存成 static final，class load 後改 prefs 不生效）
            val legacyDirs = setOf("/sdcard/rime", "/storage/emulated/0/rime")
            if (prefs.conf.userDataDir.trimEnd('/') in legacyDirs) {
                prefs.conf.userDataDir = prefs.conf.sharedDataDir
                Timber.i("Migrated legacy user_data_dir /sdcard/rime -> %s", prefs.conf.userDataDir)
            }
            // record last pid for crash logs
            val appPrefs = AppPrefs.defaultInstance()
            val currentPid = Process.myPid()
            appPrefs.general.pid.apply {
                lastPid = this
                Timber.d("Last pid is $lastPid. Set it to current pid: $currentPid")
            }
            appPrefs.general.pid = currentPid
        } catch (e: Exception) {
            e.fillInStackTrace()
            return
        }
    }

    /** 固定 tag、只收 WARN 以上；isLoggable 為 false 時 Timber 不會格式化訊息 */
    private object WarnTree : Timber.Tree() {
        override fun isLoggable(tag: String?, priority: Int) = priority >= Log.WARN

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            Log.println(priority, tag ?: "Trime", message)
        }
    }
}
