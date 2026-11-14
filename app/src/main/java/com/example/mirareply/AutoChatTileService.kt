package com.example.mirareply

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** クイック設定パネルのタイル（通知を下ろした所）で自動会話をオン/オフ */
class AutoChatTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        val svc = ReplyAccessibilityService.instance
        if (svc != null) svc.toggleAutoFromOutside()
        else Prefs(this).let { it.autoSend = !it.autoSend }
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val on = Prefs(this).autoSend
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "自動会話"
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "ON" else "OFF"
        tile.updateTile()
    }

    companion object {
        /** 状態が変わった時にタイルの表示を更新させる */
        fun refresh(c: Context) {
            runCatching { requestListeningState(c, ComponentName(c, AutoChatTileService::class.java)) }
        }
    }
}
