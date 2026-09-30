package com.qui.wordpopup

import android.os.Build
import android.os.Environment
import java.io.File
import java.util.Locale

object Dictionary {

    @Volatile
    private var map: HashMap<String, ByteArray> = HashMap()

    @Volatile
    private var signature = ""

    @Volatile
    var loading = false
        private set

    @Volatile
    var error: String? = null
        private set

    val size: Int
        get() = map.size

    /** /storage/emulated/0/WordPopup */
    fun folder(): File =
        File(Environment.getExternalStorageDirectory(), "WordPopup")

    /** Android 11+ cần quyền "Truy cập mọi tệp" */
    fun hasAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                Environment.isExternalStorageManager()

    /** Nạp lại toàn bộ thư mục nếu có file thêm/sửa/xóa. Gọi trên thread nền. */
    @Synchronized
    fun loadIfChanged() {
        if (!hasAccess()) {
            error = "Chưa cấp quyền 'Truy cập mọi tệp' cho app"
            return
        }

        val dir = folder().also { it.mkdirs() }

        val files = dir
            .listFiles { f ->
                f.isFile && f.name.endsWith(".txt", ignoreCase = true)
            }
            ?.sortedBy { it.name }
            ?: emptyList()

        val sig = files.joinToString("|") {
            "${it.name}:${it.length()}:${it.lastModified()}"
        }

        if (sig == signature) return

        loading = true

        try {
            val fresh = HashMap<String, ByteArray>(1 shl 17)

            for (f in files) {
                parseFile(f, fresh)
            }

            map = fresh
            error = null

        } catch (e: OutOfMemoryError) {
            error =
                "Hết RAM khi nạp từ điển (thêm android:largeHeap=\"true\" hoặc giảm dung lượng)"

        } catch (e: Exception) {
            error = "Lỗi đọc từ điển: ${e.message}"

        } finally {
            // Giữ behavior cũ:
            // lỗi thì không thử lại liên tục, chỉ load lại khi file thay đổi.
            signature = sig
            loading = false
        }
    }

    /**
     * Tìm từ.
     *
     * Ví dụ:
     * running  -> run
     * carried  -> carry
     * studied  -> study
     * stopped  -> stop
     * planned  -> plan
     * making   -> make
     * walks    -> walk
     *
     * Trả về:
     * Pair(từ_gốc_tìm_được, nghĩa)
     */
    fun lookup(raw: String): Pair<String, String>? {
        val w = raw
            .trim()
            .lowercase(Locale.ROOT)

        if (w.isEmpty()) return null

        val m = map

        for (candidate in candidates(w)) {
            val bytes = m[candidate] ?: continue

            return candidate to String(
                bytes,
                Charsets.UTF_8
            )
        }

        return null
    }

    /**
     * Sinh các từ có khả năng là từ gốc.
     *
     * Thứ tự rất quan trọng:
     * 1. Từ OCR nguyên bản
     * 2. Các biến thể thông dụng
     * 3. Một số bất quy tắc
     */
    private fun candidates(w: String): List<String> {

        val result = ArrayList<String>(24)
        val seen = HashSet<String>()

        fun add(s: String?) {
            if (s == null) return

            val x = s.trim().lowercase(Locale.ROOT)

            if (x.length < 2) return
            if (x == w) {
                if (seen.add(x)) result.add(x)
                return
            }

            if (seen.add(x)) {
                result.add(x)
            }
        }

        // ============================================================
        // 0. TỪ GỐC NGUYÊN BẢN
        // ============================================================

        add(w)

        // ============================================================
        // 1. MỘT SỐ DẠNG BẤT QUY TẮC PHỔ BIẾN
        // ============================================================

        when (w) {

            // be
            "am", "is", "are", "was", "were",
            "been", "being" -> {
                add("be")
            }

            // have
            "has", "had", "having" -> {
                add("have")
            }

            // do
            "does", "did", "doing" -> {
                add("do")
            }

            // go
            "goes", "went", "gone", "going" -> {
                add("go")
            }

            // come
            "came", "coming" -> {
                add("come")
            }

            // make
            "made", "making" -> {
                add("make")
            }

            // take
            "took", "taken", "taking" -> {
                add("take")
            }

            // see
            "saw", "seen", "seeing" -> {
                add("see")
            }

            // get
            "got", "gotten", "getting" -> {
                add("get")
            }

            // give
            "gave", "given", "giving" -> {
                add("give")
            }

            // find
            "found" -> {
                add("find")
            }

            // know
            "knew", "known" -> {
                add("know")
            }

            // think
            "thought" -> {
                add("think")
            }

            // say
            "said" -> {
                add("say")
            }

            // tell
            "told" -> {
                add("tell")
            }

            // speak
            "spoke", "spoken" -> {
                add("speak")
            }

            // write
            "wrote", "written", "writing" -> {
                add("write")
            }

            // read
            "reading" -> {
                add("read")
            }

            // eat
            "ate", "eaten", "eating" -> {
                add("eat")
            }

            // drink
            "drank", "drunk", "drinking" -> {
                add("drink")
            }

            // run
            "ran" -> {
                add("run")
            }

            // sleep
            "slept", "sleeping" -> {
                add("sleep")
            }

            // leave
            "left" -> {
                add("leave")
            }

            // feel
            "felt" -> {
                add("feel")
            }

            // keep
            "kept" -> {
                add("keep")
            }

            // bring
            "brought" -> {
                add("bring")
            }

            // buy
            "bought" -> {
                add("buy")
            }

            // build
            "built" -> {
                add("build")
            }

            // think
            "thought" -> {
                add("think")
            }

            // teach
            "taught" -> {
                add("teach")
            }

            // catch
            "caught" -> {
                add("catch")
            }
        }

        // ============================================================
        // 2. -IES
        //
        // carries  -> carry
        // studies  -> study
        // tries    -> try
        // flies    -> fly
        // ============================================================

        if (w.endsWith("ies") && w.length > 3) {
            add(w.dropLast(3) + "y")
        }

        // ============================================================
        // 3. -VES
        //
        // knives -> knife
        // wives  -> wife
        // wolves -> wolf
        // leaves -> leaf
        //
        // Có cả f và fe vì tiếng Anh dùng cả hai kiểu.
        // ============================================================

        if (w.endsWith("ves") && w.length > 3) {
            val stem = w.dropLast(3)

            add(stem + "f")
            add(stem + "fe")
        }

        // ============================================================
        // 4. -ES
        //
        // boxes   -> box
        // watches -> watch
        // washes  -> wash
        // passes  -> pass
        // classes -> class
        // buses   -> bus
        // dishes  -> dish
        // goes    -> go
        //
        // Thử bỏ "es" trước, sau đó bỏ "s" để xử lý:
        // cases -> case
        // horses -> horse
        // ============================================================

        if (w.endsWith("es") && w.length > 2) {
            add(w.dropLast(2))
            add(w.dropLast(1))
        }

        // ============================================================
        // 5. -S
        //
        // dogs  -> dog
        // cars  -> car
        // books -> book
        // walks -> walk
        //
        // Tránh một số trường hợp dễ phá:
        // ss, us, is
        // ============================================================

        if (w.endsWith("s") &&
            !w.endsWith("ss") &&
            !w.endsWith("us") &&
            !w.endsWith("is") &&
            w.length > 2
        ) {
            add(w.dropLast(1))
        }

        // ============================================================
        // 6. -IED
        //
        // carried -> carry
        // tried   -> try
        // studied -> study
        // copied  -> copy
        // cried   -> cry
        //
        // Đồng thời thử:
        // tied -> tie
        // died -> die
        // lied -> lie
        // ============================================================

        if (w.endsWith("ied") && w.length > 4) {
            val stem = w.dropLast(3)

            // carry, study, try, copy...
            add(stem + "y")

            // tie, die, lie...
            if (stem.endsWith("t") ||
                stem.endsWith("d") ||
                stem.endsWith("l")
            ) {
                add(stem + "e")
            }
        }

        // ============================================================
        // 7. -ED
        //
        // worked   -> work
        // liked    -> like
        // loved    -> love
        // played   -> play
        // stopped  -> stop
        // planned  -> plan
        // dropped  -> drop
        // admitted -> admit
        //
        // QUAN TRỌNG:
        // Thử stem trước:
        //
        // called -> call
        //
        // chứ không biến call -> cal.
        // ============================================================

        if (w.endsWith("ed") && w.length > 3) {

            val stem = w.dropLast(2)

            // worked -> work
            add(stem)

            // liked -> like
            // loved -> love
            // used -> use
            add(stem + "e")

            // stopped -> stopp -> stop
            // planned -> plann -> plan
            // dropped -> dropp -> drop
            // admitted -> admitt -> admit
            // preferred -> preferr -> prefer
            val dedoubled = removeFinalDoubleConsonant(stem)

            if (dedoubled != stem) {
                add(dedoubled)
            }

            // Một số từ có thể cần bỏ thêm 1 ký tự cuối
            // trong spelling đặc biệt.
            //
            // Ví dụ:
            // hoped -> hop + e đã xử lý ở trên.
        }

        // ============================================================
        // 8. -ING
        //
        // running   -> run
        // swimming  -> swim
        // sitting   -> sit
        // getting   -> get
        // making    -> make
        // taking    -> take
        // coming    -> come
        // using     -> use
        // studying  -> study
        // carrying  -> carry
        // ============================================================

        if (w.endsWith("ing") && w.length > 4) {

            val stem = w.dropLast(3)

            // running -> runn
            add(stem)

            // making -> mak -> make
            // taking -> tak -> take
            // using  -> us  -> use
            add(stem + "e")

            // running -> run
            // swimming -> swim
            // sitting -> sit
            val dedoubled = removeFinalDoubleConsonant(stem)

            if (dedoubled != stem) {
                add(dedoubled)
            }

            // ========================================================
            // lying -> lie
            // dying -> die
            // tying -> tie
            //
            // Không áp dụng bừa cho every "...ying":
            // studying -> study
            // carrying -> carry
            // ========================================================

            when (w) {
                "lying" -> add("lie")
                "dying" -> add("die")
                "tying" -> add("tie")
            }
        }

        // ============================================================
        // 9. -LY
        //
        // quickly -> quick
        // slowly  -> slow
        //
        // Chỉ là fallback vì một số từ kết thúc bằng ly
        // vốn đã là từ gốc.
        // ============================================================

        if (w.endsWith("ly") && w.length > 3) {
            add(w.dropLast(2))
        }

        return result
    }

    /**
     * Bỏ phụ âm cuối bị lặp do thêm -ed / -ing.
     *
     * stopped  -> stopp -> stop
     * planned  -> plann -> plan
     * running  -> runn  -> run
     * swimming -> swimm -> swim
     *
     * Nhưng:
     * call -> call
     * vì parser chỉ gọi hàm này như một candidate fallback.
     */
    private fun removeFinalDoubleConsonant(s: String): String {

        if (s.length < 3) return s

        val last = s[s.length - 1]
        val prev = s[s.length - 2]

        // Chỉ xử lý phụ âm đôi
        if (last != prev) return s

        val vowels = setOf('a', 'e', 'i', 'o', 'u')

        // Không xử lý nguyên âm đôi kiểu:
        // see, feel, keep...
        if (last.lowercaseChar() in vowels) {
            return s
        }

        return s.dropLast(1)
    }

    /**
     * Parse một file dictionary dạng:
     *
     * @word /pronunciation/
     * line 1
     * line 2
     * line 3
     *
     * @nextword /pronunciation/
     * ...
     */
    private fun parseFile(
        f: File,
        out: HashMap<String, ByteArray>
    ) {

        var key: String? = null
        val sb = StringBuilder()

        fun flush() {

            val k = key ?: return

            val bytes = sb
                .toString()
                .trim()
                .toByteArray(Charsets.UTF_8)

            val old = out[k]

            out[k] =
                if (old == null) {
                    bytes
                } else {
                    old +
                            "\n\n".toByteArray(Charsets.UTF_8) +
                            bytes
                }

            sb.setLength(0)
            key = null
        }

        f.bufferedReader(
            Charsets.UTF_8,
            64 * 1024
        ).useLines { lines ->

            var first = true

            for (raw in lines) {

                var line = raw

                // Xóa BOM đầu file
                if (first) {
                    line = line.removePrefix("\uFEFF")
                    first = false
                }

                // Entry mới
                if (line.startsWith("@")) {

                    flush()

                    val header =
                        line.substring(1).trim()

                    // Tìm " /"
                    //
                    // @listen /lɪsən/
                    // ↓
                    // listen
                    // /lɪsən/
                    val idx = header.indexOf(" /")

                    val word =
                        if (idx >= 0) {
                            header.substring(0, idx)
                        } else {
                            header
                        }

                    val rest =
                        if (idx >= 0) {
                            header.substring(idx + 1)
                        } else {
                            ""
                        }

                    key = word
                        .trim()
                        .lowercase(Locale.ROOT)

                    if (rest.isNotBlank()) {
                        sb
                            .append(rest)
                            .append('\n')
                    }

                } else if (key != null) {

                    sb
                        .append(line)
                        .append('\n')
                }
            }

            // Flush entry cuối cùng
            flush()
        }
    }
}
