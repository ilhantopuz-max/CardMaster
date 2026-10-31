package com.cardmaster

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Backup {
    fun export(ctx: Context, uri: Uri, cards: List<BizCard>) {
        val os = ctx.contentResolver.openOutputStream(uri) ?: throw Exception("Dosya açılamadı")
        os.use { out ->
            ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                val added = HashSet<String>()
                fun addFile(path: String): String {
                    if (path.isBlank()) return ""
                    val f = File(path)
                    if (!f.exists()) return ""
                    if (added.add(f.name)) {
                        zip.putNextEntry(ZipEntry("files/" + f.name))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    return f.name
                }
                val arr = JSONArray()
                cards.forEach { c ->
                    arr.put(JSONObject()
                        .put("name", c.name).put("company", c.company).put("jobTitle", c.jobTitle)
                        .put("mobile", c.mobile).put("phone", c.phone).put("email", c.email)
                        .put("website", c.website).put("address", c.address).put("city", c.city)
                        .put("note", c.note).put("createdAt", c.createdAt)
                        .put("logo", addFile(c.logoPath)).put("photo", addFile(c.photoPath)))
                }
                zip.putNextEntry(ZipEntry("cards.json"))
                zip.write(JSONObject().put("version", 1).put("cards", arr).toString().toByteArray())
                zip.closeEntry()
            }
        }
    }

    fun read(ctx: Context, uri: Uri): List<BizCard> {
        val dir = cardsDir(ctx)
        var json: String? = null
        val input = ctx.contentResolver.openInputStream(uri) ?: throw Exception("Dosya açılamadı")
        input.use { ins ->
            ZipInputStream(ins).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (e.name == "cards.json") {
                        json = zip.readBytes().toString(Charsets.UTF_8)
                    } else if (e.name.startsWith("files/") && !e.isDirectory) {
                        File(dir, File(e.name).name).outputStream().use { zip.copyTo(it) }
                    }
                    e = zip.nextEntry
                }
            }
        }
        val j = json ?: throw Exception("Bu bir CardMaster yedek dosyası değil")
        val arr = JSONObject(j).getJSONArray("cards")
        fun path(n: String) = if (n.isBlank()) "" else File(dir, n).takeIf { it.exists() }?.absolutePath ?: ""
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            BizCard(
                name = o.optString("name"), company = o.optString("company"),
                jobTitle = o.optString("jobTitle"), mobile = o.optString("mobile"),
                phone = o.optString("phone"), email = o.optString("email"),
                website = o.optString("website"), address = o.optString("address"),
                city = o.optString("city"), note = o.optString("note"),
                logoPath = path(o.optString("logo")), photoPath = path(o.optString("photo")),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}
