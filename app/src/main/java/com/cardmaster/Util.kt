package com.cardmaster

import android.content.ContentProviderOperation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds as CDK
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

fun cardsDir(ctx: Context): File = File(ctx.filesDir, "cards").apply { mkdirs() }

fun loadBitmap(path: String, maxSide: Int = 2000): Bitmap {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    var s = 1
    while (max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
    val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
        ?: throw Exception("Fotoğraf okunamadı")
    val deg = when (ExifInterface(path).getAttributeInt(
        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (deg == 0f) return bmp
    val m = Matrix().apply { postRotate(deg) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}

/** box = [ymin, xmin, ymax, xmax], 0..1000 normalized */
fun cropLogo(bmp: Bitmap, box: List<Int>?, dir: File): String {
    if (box == null || box.size != 4) return ""
    val x1 = (box[1] / 1000f * bmp.width).toInt().coerceIn(0, bmp.width - 1)
    val y1 = (box[0] / 1000f * bmp.height).toInt().coerceIn(0, bmp.height - 1)
    val x2 = (box[3] / 1000f * bmp.width).toInt().coerceIn(x1, bmp.width)
    val y2 = (box[2] / 1000f * bmp.height).toInt().coerceIn(y1, bmp.height)
    if (x2 - x1 < 12 || y2 - y1 < 12) return ""
    val crop = Bitmap.createBitmap(bmp, x1, y1, x2 - x1, y2 - y1)
    val f = File(dir, "logo_${System.currentTimeMillis()}.png")
    FileOutputStream(f).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return f.absolutePath
}

fun norm(s: String): String =
    Normalizer.normalize(s.replace('ı', 'i').replace('İ', 'I'), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)

fun matches(c: BizCard, query: String): Boolean {
    val tokens = norm(query).split(" ").filter { it.isNotBlank() }
    if (tokens.isEmpty()) return true
    val hay = norm(listOf(c.name, c.company, c.jobTitle, c.mobile, c.phone, c.email,
        c.website, c.address, c.city, c.note).joinToString(" "))
    return tokens.all { hay.contains(it) }
}

object ContactsWriter {
    fun insert(ctx: Context, c: BizCard) {
        val display = c.name.ifBlank { c.company }
        if (display.isBlank()) return
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build())

        fun data(mime: String, vararg v: Pair<String, Any?>) {
            val b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, mime)
            v.forEach { b.withValue(it.first, it.second) }
            ops.add(b.build())
        }

        data(CDK.StructuredName.CONTENT_ITEM_TYPE, CDK.StructuredName.DISPLAY_NAME to display)
        if (c.mobile.isNotBlank()) data(CDK.Phone.CONTENT_ITEM_TYPE,
            CDK.Phone.NUMBER to c.mobile, CDK.Phone.TYPE to CDK.Phone.TYPE_MOBILE)
        if (c.phone.isNotBlank()) data(CDK.Phone.CONTENT_ITEM_TYPE,
            CDK.Phone.NUMBER to c.phone, CDK.Phone.TYPE to CDK.Phone.TYPE_WORK)
        if (c.email.isNotBlank()) data(CDK.Email.CONTENT_ITEM_TYPE,
            CDK.Email.ADDRESS to c.email, CDK.Email.TYPE to CDK.Email.TYPE_WORK)
        if (c.company.isNotBlank() || c.jobTitle.isNotBlank()) data(CDK.Organization.CONTENT_ITEM_TYPE,
            CDK.Organization.COMPANY to c.company, CDK.Organization.TITLE to c.jobTitle)
        if (c.website.isNotBlank()) data(CDK.Website.CONTENT_ITEM_TYPE, CDK.Website.URL to c.website)
        if (c.address.isNotBlank()) data(CDK.StructuredPostal.CONTENT_ITEM_TYPE,
            CDK.StructuredPostal.FORMATTED_ADDRESS to c.address, CDK.StructuredPostal.TYPE to CDK.StructuredPostal.TYPE_WORK)
        if (c.note.isNotBlank()) data(CDK.Note.CONTENT_ITEM_TYPE, CDK.Note.NOTE to c.note)

        ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }
}
