package com.qui.wordpopup

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

/**
 * Bấm bubble -> quét màn hình, hiện lớp phủ xanh che toàn màn hình.
 * Bấm từ nào -> popup nghĩa của từ đó (bấm lần nữa thì tắt), nhiều popup cùng lúc.
 * Bấm bubble lần nữa -> tắt lớp phủ + tắt hết popup.
 * Thứ tự z (dưới -> trên): lớp phủ < bubble < popup.
 */
class FloatingService : Service() {

    private lateinit var wm: WindowManager
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val handler = Handler(Looper.getMainLooper())
    private val dp by lazy { resources.displayMetrics.density }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null   // frame màn hình mới nhất

    private var bubble: View? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var overlay: View? = null   // lớp xanh chờ chạm
    private val popups = LinkedHashMap<String, View>()   // mỗi từ 1 popup
    private var scanning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()   // phải gọi trước getMediaProjection (Android 14+)
        reloadDictionary()
    }

    private fun reloadDictionary() {
        Thread { Dictionary.loadIfChanged() }.start()
    }

    // Chưa có quyền "Truy cập mọi tệp" -> mở thẳng trang cấp quyền của app
    private fun ensureFileAccess(): Boolean {
        if (Dictionary.hasAccess()) return true
        val i = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(i) }
        return false
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = intent?.let { IntentCompat.getParcelableExtra(it, "DATA_INTENT", Intent::class.java) }
        if (data == null || projection != null) return START_NOT_STICKY

        val code = intent.getIntExtra("RESULT_CODE", Activity.RESULT_OK)
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = pm.getMediaProjection(code, data) ?: run { stopSelf(); return START_NOT_STICKY }
        projection = mp

        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        val m = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }

        reader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                latest?.close()
                latest = img
            }, handler)
        }

        display = mp.createVirtualDisplay(
            "WordPopupCapture", m.widthPixels, m.heightPixels, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler
        )

        showBubble()
        return START_NOT_STICKY
    }

    // ---------- Bong bóng: kéo được, bấm để quét / tắt lớp phủ ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val view = LayoutInflater.from(this).inflate(R.layout.layout_bubble, null)
        val lp = layoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100; y = 300
        }

        var startX = 0; var startY = 0
        var downX = 0f; var downY = 0f

        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    downX = e.rawX; downY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    wm.updateViewLayout(view, lp)
                }
                MotionEvent.ACTION_UP ->
                    if (abs(e.rawX - downX) < 10 && abs(e.rawY - downY) < 10) onBubbleTap()
            }
            true
        }

        bubble = view
        bubbleLp = lp
        wm.addView(view, lp)
    }

    private fun onBubbleTap() {
        if (overlay != null) {      // đang có lớp phủ -> tắt hết
            closeOverlay()
            closeAllPopups()
        } else {
            scan()
        }
    }

    // Window add sau thì nằm trên. Remove rồi add lại để nổi lên trên lớp phủ.
    private fun bringBubbleToFront() {
        val v = bubble ?: return
        val lp = bubbleLp ?: return
        runCatching { wm.removeView(v) }
        wm.addView(v, lp)
    }

    private fun bringPopupToFront(v: View) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        runCatching { wm.removeView(v) }
        wm.addView(v, lp)
    }

    // ---------- Quét: OCR frame mới nhất ----------

    private fun scan() {
        if (scanning || overlay != null) return
        closeAllPopups()

        if (!ensureFileAccess()) {
            return note("Bật 'Cho phép truy cập mọi tệp' rồi quay lại bấm bubble")
        }
        reloadDictionary()   // file từ điển đổi thì tự nạp lại (không đổi thì bỏ qua ngay)

        val img = latest ?: return note("Chưa có ảnh màn hình, thử lại")

        // Bitmap giữ nguyên phần đệm bên phải; tọa độ chữ vẫn khớp màn hình
        val plane = img.planes[0]
        val bmp = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        bmp.copyPixelsFromBuffer(plane.buffer)

        scanning = true
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { showOverlay(it) }
            .addOnFailureListener { note("OCR lỗi: ${it.message}") }
            .addOnCompleteListener { scanning = false }
    }

    // ---------- Lớp xanh: bấm từ nào toggle popup từ đó, lớp phủ giữ nguyên ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay(text: Text) {
        val elements = text.textBlocks.flatMap { it.lines }.flatMap { it.elements }
        val view = View(this).apply { setBackgroundColor(0x2200FF00) }

        view.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                val x = e.rawX.toInt()
                val y = e.rawY.toInt()
                val el = elements.firstOrNull { it.boundingBox?.contains(x, y) == true }
                val box = el?.boundingBox
                val word = el?.text?.trim { !it.isLetterOrDigit() }
                // overlay KHÔNG đóng; bấm trúng chỗ trống thì bỏ qua
                if (box != null && !word.isNullOrEmpty()) {
                    togglePopup("${box.left},${box.top}", word, box)
                }
            }
            true
        }

        overlay = view
        wm.addView(
            view,
            layoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        )
        bringBubbleToFront()   // bubble nằm trên lớp phủ để còn bấm tắt được
        popups.values.toList().forEach { bringPopupToFront(it) }
    }

    private fun closeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    // ---------- Popup: mỗi từ 1 cái, luôn nằm trên cùng ----------

    private fun togglePopup(key: String, word: String, anchor: Rect) {
        if (popups.containsKey(key)) {   // bấm lần 2 -> tắt
            closePopup(key)
            return
        }

        val (title, body) = when {
            Dictionary.size == 0 && Dictionary.loading -> word to "Đang nạp từ điển…"
            Dictionary.size == 0 -> word to (Dictionary.error
                ?: "Thư mục từ điển trống:\n${Dictionary.folder().absolutePath}")
            else -> {
                val hit = Dictionary.lookup(word)
                if (hit == null) word to "Chưa có trong từ điển"
                else {
                    val head = if (hit.first.equals(word, ignoreCase = true)) word else "$word → ${hit.first}"
                    head to hit.second
                }
            }
        }
        showPopup(key, title, body, anchor)
    }

    private fun showPopup(key: String, title: String, body: String, anchor: Rect) {
        closePopup(key)

        val maxTextW = (260 * dp).toInt()
        val shownBody = if (body.length > 600) body.take(600) + "…" else body

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(0xF2212121.toInt())
                cornerRadius = 16 * dp
            }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    setTypeface(typeface, Typeface.BOLD)
                    maxWidth = maxTextW
                })
                if (shownBody.isNotBlank()) {
                    addView(TextView(context).apply {
                        text = shownBody
                        setTextColor(0xFFCCCCCC.toInt())
                        textSize = 15f
                        maxWidth = maxTextW
                        setPadding(0, (4 * dp).toInt(), 0, 0)
                    })
                }
            })

            addView(TextView(context).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                textSize = 20f
                setPadding((16 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
                setOnClickListener { closePopup(key) }
            })
        }

        // Đo trước để biết popup cao/rộng bao nhiêu, đặt ngay phía trên từ (hết chỗ thì xuống dưới)
        val screenW = resources.displayMetrics.widthPixels
        box.measure(
            View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val gap = (6 * dp).toInt()
        var py = anchor.top - box.measuredHeight - gap
        if (py < 0) py = anchor.bottom + gap
        val px = anchor.left.coerceIn(0, (screenW - box.measuredWidth).coerceAtLeast(0))

        val lp = layoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = px
            y = py
        }

        popups[key] = box
        wm.addView(box, lp)   // add sau cùng -> nằm trên cùng
    }

    private fun closePopup(key: String) {
        popups.remove(key)?.let { runCatching { wm.removeView(it) } }
    }

    private fun closeAllPopups() {
        popups.values.forEach { runCatching { wm.removeView(it) } }
        popups.clear()
    }

    // thông báo lỗi/trạng thái cũng là popup overlay, không dùng Toast
    private fun note(msg: String) {
        val x = (40 * dp).toInt()
        val y = (160 * dp).toInt()
        showPopup("note", msg, "", Rect(x, y, x, y))
    }

    // ---------- Tiện ích ----------

    private fun layoutParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
    ).apply {
        // Máy có tai thỏ: nếu thiếu dòng này cửa sổ bị đẩy xuống ~45px so với màn hình thật
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun startAsForeground() {
        val channelId = "word_popup_service"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "Word Popup", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("App Tra Từ Đang Chạy")
            .setContentText("Bấm bong bóng để tra từ trên màn hình")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    override fun onDestroy() {
        closeAllPopups()
        closeOverlay()
        bubble?.let { runCatching { wm.removeView(it) } }
        latest?.close()
        display?.release()
        reader?.close()
        projection?.stop()
        recognizer.close()
        super.onDestroy()
    }
}
