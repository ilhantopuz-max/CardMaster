package com.cardmaster

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class Extracted(
    val name: String, val company: String, val jobTitle: String,
    val mobile: String, val phone: String, val email: String,
    val website: String, val address: String, val city: String
)

object Gemini {
    private const val PROMPT = """You are an OCR engine for business cards (mostly Turkish). Read the card image and return ONLY a JSON object with these keys:
"name": person's full name (first name + surname),
"company": company / business title as printed,
"job_title": person's position, empty if none,
"mobile": GSM / cell phone number (the one that looks like a mobile, e.g. 05xx or +90 5xx),
"phone": landline / office phone number (fax NOT included),
"email": email address,
"website": web address,
"address": full postal address as printed,
"city": city (il) taken from the address, e.g. Bursa.
Use an empty string for anything not on the card. Never invent data. Keep Turkish characters exactly. Keep phone numbers as printed."""

    private fun scaleDown(b: Bitmap, max: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= max) return b
        val f = max.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    fun scan(bmp: Bitmap, key: String, model: String): Extracted {
        val bos = ByteArrayOutputStream()
        scaleDown(bmp, 1600).compress(Bitmap.CompressFormat.JPEG, 90, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)

        val parts = JSONArray()
            .put(JSONObject().put("text", PROMPT))
            .put(JSONObject().put("inline_data",
                JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json"))

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20000
        c.readTimeout = 90000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) {
            val detail = try {
                val e = JSONObject(text).getJSONObject("error")
                e.optString("status") + ": " + e.optString("message")
            } catch (ex: Exception) { text.take(600) }
            throw Exception("HTTP $code (model: $model)\n$detail")
        }

        val out = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        val j = JSONObject(out.trim().removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim())

        fun s(k: String) = if (j.isNull(k)) "" else j.optString(k, "").trim()
        return Extracted(s("name"), s("company"), s("job_title"), s("mobile"), s("phone"),
            s("email"), s("website"), s("address"), s("city"))
    }
}
