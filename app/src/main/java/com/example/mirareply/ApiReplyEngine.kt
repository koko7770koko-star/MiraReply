package com.example.mirareply

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * クラウドAPI（Claude / OpenAI / Gemini）で返信候補を作る。
 * 会話の直近部分だけを送る。バックグラウンドスレッドから呼ぶこと。
 */
class ApiReplyEngine(private val prefs: Prefs) {

    fun generate(provider: String, req: ReplyRequest): List<String> {
        val key = prefs.apiKey(provider)
        require(key.isNotBlank()) { "${Prefs.ENGINE_LABELS[provider]} のAPIキーが未設定です（設定画面で入力）" }
        val model = prefs.apiModel(provider)
        val system = ReplyPrompt.system(req, json = true)
        val user = ReplyPrompt.user(req)
        val raw = when (provider) {
            Prefs.ENGINE_CLAUDE -> claude(key, model, system, user)
            Prefs.ENGINE_OPENAI -> openai(key, model, system, user)
            Prefs.ENGINE_GEMINI -> gemini(key, model, system, user)
            else -> error("不明なエンジン: $provider")
        }
        Log.d(TAG, "raw($provider):\n$raw")
        return ReplyPrompt.parse(raw, req.history)
    }

    private fun claude(key: String, model: String, system: String, user: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 600)
            .put("temperature", 0.9)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        val res = post(
            "https://api.anthropic.com/v1/messages", body,
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
        )
        val content = res.getJSONArray("content")
        return buildString {
            for (i in 0 until content.length()) {
                val c = content.getJSONObject(i)
                if (c.optString("type") == "text") append(c.optString("text"))
            }
        }
    }

    private fun openai(key: String, model: String, system: String, user: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .put("max_completion_tokens", 2000)
        // gpt-5 系（推論モデル）は考える量を最小にして速く・安く
        if (model.startsWith("gpt-5") || model.startsWith("o")) body.put("reasoning_effort", "minimal")
        else body.put("temperature", 0.9)
        val res = post(
            "https://api.openai.com/v1/chat/completions", body,
            mapOf("Authorization" to "Bearer $key"),
        )
        return res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
    }

    private fun gemini(key: String, model: String, system: String, user: String): String {
        val gen = JSONObject()
            .put("temperature", 0.9)
            .put("maxOutputTokens", 800)
            .put("responseMimeType", "application/json")
        if (model.contains("flash")) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", gen)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            URLEncoder.encode(model, "UTF-8") + ":generateContent"
        val res = post(url, body, mapOf("x-goog-api-key" to key))
        val parts = res.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        return buildString { for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }
    }

    private fun post(url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try {
                    val j = JSONObject(text)
                    j.optJSONObject("error")?.optString("message") ?: text
                } catch (_: Throwable) { text }
                throw IllegalStateException("API エラー $code: ${msg.take(160)}")
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    companion object { private const val TAG = "ApiReplyEngine" }
}
