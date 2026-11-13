package com.example.mirareply

import android.content.Context
import android.util.Log
import java.io.File

/**
 * APK に埋め込んだ Gemma モデルを、初回だけ端末の保存領域へ展開する。
 * （LiteRT-LM はファイルのパスが必要なため、APK の中から直接は読めない）
 * モデルは 2GB を超えるので assets/model/ に分割して入れてある。
 */
object BundledModel {
    private const val TAG = "BundledModel"
    private const val DIR = "model"
    const val MODEL_ID = "gemma4-e2b"

    private val lock = Any()

    fun parts(context: Context): List<String> =
        (context.assets.list(DIR) ?: emptyArray()).filter { it.contains(".part") }.sorted()

    fun isBundled(context: Context) = parts(context).isNotEmpty()

    /**
     * 必要なら展開する。すでに展開済みなら何もしない。
     * @param progress 書き込んだバイト数を受け取る（UI表示用）
     * @return 展開済み（または展開した）なら true
     */
    fun ensureInstalled(context: Context, progress: ((Long, Long) -> Unit)? = null): Boolean = synchronized(lock) {
        val prefs = Prefs(context)
        val info = Prefs.MODELS.first { it.id == MODEL_ID }
        val dest = prefs.modelFile(info)
        if (dest.exists() && dest.length() == info.sizeBytes) return true
        val parts = parts(context)
        if (parts.isEmpty()) return false

        val tmp = File(dest.path + ".part")
        try {
            var total = 0L
            var lastUi = 0L
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                for (p in parts) {
                    context.assets.open("$DIR/$p").use { input ->
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); total += n
                            if (progress != null && total - lastUi > (32L shl 20)) {
                                lastUi = total; progress(total, info.sizeBytes)
                            }
                        }
                    }
                }
            }
            if (total != info.sizeBytes) error("サイズ不一致 $total / ${info.sizeBytes}")
            dest.delete()
            if (!tmp.renameTo(dest)) error("ファイル名を変更できません")
            progress?.invoke(total, info.sizeBytes)
            Log.i(TAG, "installed ${dest.path}")
            return true
        } catch (t: Throwable) {
            tmp.delete()
            throw IllegalStateException("内蔵モデルの展開に失敗（空き容量 約3GB 必要）: ${t.message}", t)
        }
    }
}
