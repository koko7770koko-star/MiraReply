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
class ApiReplyEngine(private val prefs: Prefs, private val usage: UsageStore) {

    /** 直前の呼び出しの料金（ドル） */
    @Volatile var lastCostUsd = 0.0
        private set

    fun generate(provider: String, req: ReplyRequest): List<String> {
        val raw = complete(provider, ReplyPrompt.system(req, json = true), ReplyPrompt.user(req), json = true, maxTokens = 600)
        return ReplyPrompt.parse(raw, req.history)
    }

    /** 古い会話を記憶（要約）にまとめる */
    fun summarize(provider: String, charName: String, oldSummary: String, entries: List<LogEntry>): String {
        val raw = complete(provider, ReplyPrompt.summarySystem(charName),
            ReplyPrompt.summaryUser(charName, oldSummary, entries), json = false, maxTokens = 900)
        return raw.trim()
    }

    private var inTok = 0L
    private var outTok = 0L

    fun complete(provider: String, system: String, user: String, json: Boolean, maxTokens: Int): String {
        val key = prefs.apiKey(provider)
        require(key.isNotBlank()) { "${Prefs.ENGINE_LABELS[provider]} のAPIキーが未設定です（設定画面で入力）" }
        val model = prefs.apiModel(provider)
        inTok = 0; outTok = 0
        val raw = when (provider) {
            Prefs.ENGINE_CLAUDE -> claude(key, model, system, user, maxTokens)
            Prefs.ENGINE_OPENAI -> openai(key, model, system, user, json)
            Prefs.ENGINE_GEMINI -> gemini(key, model, system, user, json, maxTokens)
            else -> error("不明なエンジン: $provider")
        }
        usage.add(provider, inTok, outTok)
        lastCostUsd = usage.costOf(provider, inTok, outTok)
        Log.d(TAG, "raw($provider in=$inTok out=$outTok):\n$raw")
        return raw
    }

    private fun claude(key: String, model: String, system: String, user: String, maxTokens: Int): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.9)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        val res = post(
            "https://api.anthropic.com/v1/messages", body,
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
        )
        res.optJSONObject("usage")?.let {
            inTok = it.optLong("input_tokens") + it.optLong("cache_read_input_tokens") + it.optLong("cache_creation_input_tokens")
            outTok = it.optLong("output_tokens")
        }
        val content = res.getJSONArray("content")
        return buildString {
            for (i in 0 until content.length()) {
                val c = content.getJSONObject(i)
                if (c.optString("type") == "text") append(c.optString("text"))
            }
        }
    }

    private fun openai(key: String, model: String, system: String, user: String, json: Boolean): String {
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
        res.optJSONObject("usage")?.let { inTok = it.optLong("prompt_tokens"); outTok = it.optLong("completion_tokens") }
        return res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
    }

    private fun gemini(key: String, model: String, system: String, user: String, json: Boolean, maxTokens: Int): String {
        val gen = JSONObject()
            .put("temperature", 0.9)
            .put("maxOutputTokens", maxTokens + 200)
        if (json) gen.put("responseMimeType", "application/json")
        if (model.contains("flash")) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", gen)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            URLEncoder.encode(model, "UTF-8") + ":generateContent"
        val res = post(url, body, mapOf("x-goog-api-key" to key))
        res.optJSONObject("usageMetadata")?.let {
            inTok = it.optLong("promptTokenCount")
            outTok = it.optLong("candidatesTokenCount") + it.optLong("thoughtsTokenCount")
        }
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
