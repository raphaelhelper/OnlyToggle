package com.qui.wordpopup

import android.os.Build
import android.os.Environment
import java.io.File

object Dictionary {

    @Volatile private var map: HashMap<String, ByteArray> = HashMap()
    @Volatile private var signature = ""

    @Volatile var loading = false
        private set
    @Volatile var error: String? = null
        private set

    val size: Int get() = map.size

    /** /storage/emulated/0/WordPopup */
    fun folder(): File = File(Environment.getExternalStorageDirectory(), "WordPopup")

    /** Android 11+ cần quyền "Truy cập mọi tệp" mới đọc được thư mục ngoài app. */
    fun hasAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** Nạp lại toàn bộ thư mục nếu có file thêm/sửa/xóa. Gọi trên thread nền. */
    @Synchronized
    fun loadIfChanged() {
        if (!hasAccess()) {
            error = "Chưa cấp quyền 'Truy cập mọi tệp' cho app"
            return
        }
        val dir = folder().also { it.mkdirs() }
        val files = dir
            .listFiles { f -> f.isFile && f.name.endsWith(".txt", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()

        val sig = files.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
        if (sig == signature) return

        loading = true
        try {
            val fresh = HashMap<String, ByteArray>(1 shl 17)
            for (f in files) parseFile(f, fresh)
            map = fresh
            error = null
        } catch (e: OutOfMemoryError) {
            error = "Hết RAM khi nạp từ điển (thêm android:largeHeap=\"true\" hoặc giảm dung lượng)"
        } catch (e: Exception) {
            error = "Lỗi đọc từ điển: ${e.message}"
        } finally {
            signature = sig      // lỗi thì không thử lại liên tục, chờ file đổi
            loading = false
        }
    }

    /** Trả về (từ gốc tìm được, nghĩa) hoặc null. Tự thử bỏ đuôi s/es/ed/ing/ly. */
    fun lookup(raw: String): Pair<String, String>? {
        val w = raw.trim().lowercase()
        if (w.isEmpty()) return null
        val m = map
        for (c in candidates(w)) {
            val b = m[c] ?: continue
            return c to String(b, Charsets.UTF_8)
        }
        return null
    }

    private fun candidates(w: String): List<String> {
        val c = arrayListOf(w)
        fun add(s: String) { if (s.length >= 2) c.add(s) }
        if (w.endsWith("ies")) add(w.dropLast(3) + "y")
        if (w.endsWith("es")) add(w.dropLast(2))
        if (w.endsWith("s")) add(w.dropLast(1))
        if (w.endsWith("ed")) {
            add(w.dropLast(2)); add(w.dropLast(1))
            if (w.length > 4 && w[w.length - 3] == w[w.length - 4]) add(w.dropLast(3))
        }
        if (w.endsWith("ing")) {
            add(w.dropLast(3)); add(w.dropLast(3) + "e")
            if (w.length > 5 && w[w.length - 4] == w[w.length - 5]) add(w.dropLast(4))
        }
        if (w.endsWith("ly")) add(w.dropLast(2))
        return c
    }

    private fun parseFile(f: File, out: HashMap<String, ByteArray>) {
        var key: String? = null
        val sb = StringBuilder()

        fun flush() {
            val k = key ?: return
            val bytes = sb.toString().trim().toByteArray(Charsets.UTF_8)
            val old = out[k]
            out[k] = if (old == null) bytes else old + "\n\n".toByteArray() + bytes
            sb.setLength(0)
            key = null
        }

        f.bufferedReader(Charsets.UTF_8, 64 * 1024).useLines { lines ->
            var first = true
            for (raw in lines) {
                var line = raw
                if (first) { line = line.removePrefix("\uFEFF"); first = false }

                if (line.startsWith("@")) {
                    flush()
                    val header = line.substring(1).trim()
                    val idx = header.indexOf(" /")
                    val word = if (idx >= 0) header.substring(0, idx) else header
                    val rest = if (idx >= 0) header.substring(idx + 1) else ""
                    key = word.trim().lowercase()
                    if (rest.isNotBlank()) sb.append(rest).append('\n')
                } else if (key != null) {
                    sb.append(line).append('\n')
                }
            }
            flush()
        }
    }
}
