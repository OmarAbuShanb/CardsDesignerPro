package dev.anonymous.cardsdesignerpro.app.ui.exportedfiles

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder

class LocalShareServer(
    private val context: Context,
    port: Int = 8080
) : NanoHTTPD(port) {

    private var files: List<PdfFileInfo> = emptyList()

    fun updateFiles(newFiles: List<PdfFileInfo>) {
        files = newFiles
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

        return when {
            uri == "/" || uri == "" || uri == "/files" -> serveFilesPage(session)
            uri == "/download" -> handleDownload(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }

    private fun isArabic(session: IHTTPSession): Boolean {
        val acceptLang = session.headers["accept-language"] ?: return false
        return acceptLang.lowercase().startsWith("ar")
    }

    private fun serveFilesPage(session: IHTTPSession): Response {
        val ar = isArabic(session)
        val dir = if (ar) "rtl" else "ltr"
        val title = if (ar) "الملفات المصدّرة" else "Exported Files"
        val dlText = if (ar) "تحميل" else "Download"
        val noFiles = if (ar) "لا توجد ملفات" else "No files available"

        val fileRows = if (files.isEmpty()) {
            "<div class='empty'>$noFiles</div>"
        } else {
            files.joinToString("") { file ->
                val encodedName = URLEncoder.encode(file.name, "UTF-8")
                val sizeStr = formatFileSize(file.size)
                """
                <div class="file-row">
                    <div class="file-icon">
                        <svg width="28" height="28" viewBox="0 0 24 24" fill="none">
                            <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" stroke="#BDB8FF" stroke-width="1.5" fill="rgba(91,79,242,0.15)"/>
                            <polyline points="14,2 14,8 20,8" stroke="#BDB8FF" stroke-width="1.5" fill="none"/>
                            <text x="12" y="17" text-anchor="middle" fill="#BDB8FF" font-size="6" font-weight="700">PDF</text>
                        </svg>
                    </div>
                    <div class="file-info">
                        <div class="file-name">${file.name}</div>
                        <div class="file-size">$sizeStr</div>
                    </div>
                    <a class="dl-btn" href="/download?file=$encodedName" download="${file.name}">$dlText</a>
                </div>
                """
            }
        }

        val html = """
        <!DOCTYPE html>
        <html lang="${if (ar) "ar" else "en"}" dir="$dir">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>$title — Cards Designer Pro</title>
            <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    background: linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%);
                    min-height: 100vh;
                    color: #e5e3ff;
                    padding: 24px;
                }
                .container { max-width: 720px; margin: 0 auto; }
                .header {
                    text-align: center; padding: 32px 0 24px;
                }
                .header .logo { font-size: 24px; font-weight: 700; color: #BDB8FF; }
                .header .title { font-size: 16px; color: #9593C3; margin-top: 4px; }
                .file-row {
                    display: flex; align-items: center; gap: 16px;
                    background: rgba(255,255,255,0.06);
                    border: 1px solid rgba(255,255,255,0.08);
                    border-radius: 16px; padding: 16px 20px;
                    margin-bottom: 12px;
                    transition: background 0.2s;
                }
                .file-row:hover { background: rgba(255,255,255,0.1); }
                .file-icon { flex-shrink: 0; }
                .file-info { flex: 1; min-width: 0; }
                .file-name { font-weight: 600; font-size: 14px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                .file-size { font-size: 12px; color: #9593C3; margin-top: 2px; }
                .dl-btn {
                    flex-shrink: 0; padding: 10px 20px; font-size: 13px; font-weight: 600;
                    background: linear-gradient(135deg, #5B4FF2, #3E31DB);
                    color: #fff; border: none; border-radius: 12px;
                    text-decoration: none; cursor: pointer;
                    transition: transform 0.2s, box-shadow 0.2s;
                }
                .dl-btn:hover { transform: translateY(-1px); box-shadow: 0 4px 12px rgba(91,79,242,0.4); }
                .empty {
                    text-align: center; padding: 48px; color: #9593C3; font-size: 15px;
                }
            </style>
        </head>
        <body>
            <div class="container">
                <div class="header">
                    <div class="logo">Cards Designer Pro</div>
                    <div class="title">$title</div>
                </div>
                $fileRows
            </div>
        </body>
        </html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun handleDownload(session: IHTTPSession): Response {
        val fileName = session.parms["file"]?.let { URLDecoder.decode(it, "UTF-8") }
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing file parameter")

        val fileInfo = files.firstOrNull { it.name == fileName }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")

        return try {
            val inputStream: InputStream = context.contentResolver.openInputStream(fileInfo.uri)
                ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Cannot open file")

            val response = newFixedLengthResponse(
                Response.Status.OK,
                "application/pdf",
                inputStream,
                fileInfo.size
            )
            val encodedFileName = URLEncoder.encode(fileInfo.name, "UTF-8").replace("+", "%20")
            response.addHeader("Content-Disposition", "attachment; filename=\"${fileInfo.name}\"; filename*=UTF-8''$encodedFileName")
            response
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
