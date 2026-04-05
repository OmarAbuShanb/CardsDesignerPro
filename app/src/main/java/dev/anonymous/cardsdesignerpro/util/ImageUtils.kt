package dev.anonymous.cardsdesignerpro.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Result of [ImageUtils.copyAndFixExif].
 * @param file  The final saved JPEG file (already exif-corrected).
 * @param width Pixel width of the corrected image.
 * @param height Pixel height of the corrected image.
 */
data class CopiedImage(val file: File, val width: Int, val height: Int)

/**
 * Utility object for image import operations.
 * Centralises EXIF auto-rotation so every place that copies gallery images
 * (background image, image elements, QR logos) behaves consistently.
 */
object ImageUtils {

    /**
     * Copies [uri] to [destDir], auto-corrects EXIF rotation, and returns
     * a [CopiedImage] with the final file and its pixel dimensions.
     *
     * Always writes a JPEG to avoid format ambiguity.
     * Returns `null` if the copy fails.
     */
    fun copyAndFixExif(context: Context, uri: Uri, destDir: File): CopiedImage? {
        destDir.mkdirs()
        val destFile = File(destDir, "${UUID.randomUUID()}.jpg")
        return try {
            // 1. Write raw bytes
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { out -> input.copyTo(out) }
            }

            // 2. Read EXIF orientation
            val exif = ExifInterface(destFile.absolutePath)
            val orientDeg = when (exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90  -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }

            // 3. Read dimensions without decoding pixels
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(destFile.absolutePath, opts)
            var imgW = opts.outWidth
            var imgH = opts.outHeight

            // 4. Rotate pixel data if needed, overwrite the file
            if (orientDeg != 0) {
                val bmp = BitmapFactory.decodeFile(destFile.absolutePath)
                val matrix = Matrix().apply { postRotate(orientDeg.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                FileOutputStream(destFile).use { out ->
                    rotated.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                imgW = rotated.width; imgH = rotated.height
                bmp.recycle(); rotated.recycle()
            }

            CopiedImage(destFile, imgW, imgH)
        } catch (e: Exception) {
            e.printStackTrace()
            destFile.delete()
            null
        }
    }
}
