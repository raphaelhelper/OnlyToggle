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
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

/**
 * BẢN DEBUG: lớp phủ hiện đúng khung hình mà app chụp được (mờ 70%),
 * kèm toast "rộng x cao, số từ". Dùng để xem khung hình ở app khác có đúng không.
 */
class FloatingService : Service() {

    private lateinit var wm: WindowManager
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val handler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null   // frame màn hình mới nhất

    private var bubble: View? = null
    private var overlay: View? = null
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
        val img = latest ?: return toast("Chưa có ảnh màn hình, thử lại")

        // Bitmap giữ nguyên phần đệm bên phải; tọa độ chữ vẫn khớp màn hình
        val plane = img.planes[0]
        val bmp = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        bmp.copyPixelsFromBuffer(plane.buffer)

        scanning = true
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener {
                val n = it.textBlocks.sumOf { b -> b.lines.sumOf { l -> l.elements.size } }
                toast("${bmp.width}x${bmp.height}, $n từ")
                showOverlay(it, bmp)
            }
            .addOnFailureListener { toast("OCR lỗi: ${it.message}") }
            .addOnCompleteListener { scanning = false }
    }

    // ---------- Lớp phủ: hiện khung hình đã chụp, chạm vào từ nào thì lấy từ đó ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay(text: Text, bmp: Bitmap) {
        val view = ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.MATRIX   // không co giãn, canh góc trên trái
            alpha = 0.7f
            setBackgroundColor(0x3300FF00)
        }

        view.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                val x = e.rawX.toInt()
                val y = e.rawY.toInt()
                val word = text.textBlocks
                    .flatMap { it.lines }
                    .flatMap { it.elements }
                    .firstOrNull { it.boundingBox?.contains(x, y) == true }
                    ?.text

                toast(word?.let { "TỪ: [$it]" } ?: "Không tìm thấy từ")
                closeOverlay()
            }
            true
        }

        overlay = view
        wm.addView(
            view,
            layoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        )
    }

    private fun closeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    // ---------- Tiện ích ----------

    private fun layoutParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
    )

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

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
