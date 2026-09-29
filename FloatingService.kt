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
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 * Kết quả KHÔNG dùng Toast nữa (Toast bị hệ thống ẩn khi app chạy nền).
 * Mọi thông báo đều là cửa sổ overlay vẽ đè lên app khác, có nút ✕ để tắt.
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
    private var overlay: View? = null   // lớp xanh chờ chạm
    private var popup: View? = null     // popup kết quả
    private var scanning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()   // phải gọi trước getMediaProjection (Android 14+)
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

    // ---------- Bong bóng: kéo được, bấm để quét ----------

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
                    if (abs(e.rawX - downX) < 10 && abs(e.rawY - downY) < 10) scan()
            }
            true
        }

        bubble = view
        wm.addView(view, lp)
    }

    // ---------- Quét: OCR frame mới nhất ----------

    private fun scan() {
        if (scanning || overlay != null) return
        closePopup()
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

    // ---------- Lớp xanh: chạm vào từ nào thì lấy từ đó ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay(text: Text) {
        val elements = text.textBlocks.flatMap { it.lines }.flatMap { it.elements }
        val view = View(this).apply { setBackgroundColor(0x2200FF00) }

        view.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                val x = e.rawX.toInt()
                val y = e.rawY.toInt()
                val word = elements
                    .firstOrNull { it.boundingBox?.contains(x, y) == true }
                    ?.text
                    ?.trim { !it.isLetterOrDigit() }

                closeOverlay()
                if (word.isNullOrEmpty()) showPopup("Không tìm thấy từ (đọc được ${elements.size} từ)", x, y)
                else showPopup(word, x, y)
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
    }

    private fun closeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    // ---------- Popup kết quả: vẽ đè lên mọi app, chỉ tắt khi bấm ✕ ----------

    private fun showPopup(message: String, atX: Int, atY: Int) {
        closePopup()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(0xF2212121.toInt())
                cornerRadius = 16 * dp
            }
            addView(TextView(context).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 20f
                maxWidth = (240 * dp).toInt()
            })
            addView(TextView(context).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                textSize = 20f
                setPadding((16 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
                setOnClickListener { closePopup() }
            })
        }

        val maxX = (resources.displayMetrics.widthPixels - 300 * dp).toInt().coerceAtLeast(0)
        val lp = layoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (atX - 60 * dp).toInt().coerceIn(0, maxX)
            y = (atY - 90 * dp).toInt().coerceAtLeast(0)   // nằm phía trên chỗ chạm để không che từ
        }

        popup = box
        wm.addView(box, lp)
    }

    private fun closePopup() {
        popup?.let { runCatching { wm.removeView(it) } }
        popup = null
    }

    // thông báo lỗi/trạng thái cũng là popup overlay, không dùng Toast
    private fun note(msg: String) = showPopup(msg, (40 * dp).toInt(), (160 * dp).toInt())

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
        closePopup()
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
