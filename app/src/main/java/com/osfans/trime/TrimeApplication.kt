package com.osfans.trime

import android.app.Application
import android.os.Process
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
            if (BuildConfig.DEBUG) {
                Timber.plant(Timber.DebugTree())
            }
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
}
