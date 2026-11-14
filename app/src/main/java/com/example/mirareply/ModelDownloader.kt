package com.example.mirareply

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri

/** 端末内AIモデルのダウンロード（設定画面・バックグラウンド共通） */
object ModelDownloader {

    data class Progress(val status: Int, val done: Long, val total: Long)

    private fun sp(c: Context) = c.getSharedPreferences("dl", Context.MODE_PRIVATE)

    fun currentId(c: Context) = sp(c).getLong("id", -1L)

    fun clear(c: Context) = sp(c).edit().remove("id").apply()

    fun progress(c: Context): Progress? {
        val id = currentId(c); if (id < 0) return null
        val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id))?.use { cur ->
            if (!cur.moveToFirst()) return null
            return Progress(
                cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
        return null
    }

    fun isRunning(c: Context): Boolean {
        val p = progress(c) ?: return false
        return p.status == DownloadManager.STATUS_RUNNING || p.status == DownloadManager.STATUS_PENDING ||
            p.status == DownloadManager.STATUS_PAUSED
    }

    /** ダウンロード開始。ログインが必要なモデルなら false */
    fun start(c: Context, overMetered: Boolean = true): Boolean {
        val prefs = Prefs(c)
        val m = prefs.model
        if (m.needsLogin) return false
        val f = prefs.modelFile()
        if (f.exists()) f.delete()
        val req = DownloadManager.Request(Uri.parse(m.url))
            .setTitle("AIモデル ${m.fileName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(c, null, m.fileName)
            .setAllowedOverMetered(overMetered)
        val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        sp(c).edit().putLong("id", dm.enqueue(req)).apply()
        return true
    }

    private fun onWifi(c: Context): Boolean {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * 初回の自動ダウンロード。モデルが無く、Wi-Fi 接続中で、まだ落としていなければ始める。
     * モバイル回線では始めない（容量 2.6GB のため）。
     */
    fun autoStartIfNeeded(c: Context): Boolean {
        val prefs = Prefs(c)
        if (!prefs.autoModelDownload || prefs.modelReady() || prefs.model.needsLogin) return false
        if (isRunning(c) || !onWifi(c)) return false
        return start(c, overMetered = false)
    }
}
