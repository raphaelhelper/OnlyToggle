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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

class FloatingService : Service() {

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var bubble: View? = null
    private var overlay: View? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var captureRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_OK) ?: 0
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent?.getParcelableExtra("DATA_INTENT", Intent::class.java)
        else
            intent?.getParcelableExtra<Intent>("DATA_INTENT")

        if (data == null) return START_NOT_STICKY

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = pm.getMediaProjection(code, data)
        if (projection == null) {
            toast("Không tạo được MediaProjection!")
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+ bắt buộc đăng ký callback trước khi tạo VirtualDisplay
        projection!!.registerCallback(object : MediaProjection.Callback() {}, handler)

        val m = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)

        reader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (captureRequested) {
                    captureRequested = false
                    toBitmap(img)?.let { ocr(it) } ?: toast("Không chuyển được ảnh màn hình!")
                }
                img.close()
            }, handler)
        }

        display = projection!!.createVirtualDisplay(
            "ScreenCapture", m.widthPixels, m.heightPixels, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null
        )

        showBubble()
        return START_NOT_STICKY
    }

    // ---------- Bong bóng ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.layout_bubble, null)
        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f

        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = e.rawX; touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - touchX).toInt()
                    lp.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(view, lp)
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(e.rawX - touchX) < 10 && abs(e.rawY - touchY) < 10) requestCapture()
                }
            }
            true
        }

        bubble = view
        wm.addView(view, lp)
    }

    private fun requestCapture() {
        if (captureRequested) return
        captureRequested = true
        toast("Đang quét màn hình...")
    }

    // ---------- OCR + chạm chọn từ ----------

    private fun ocr(bitmap: Bitmap) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { showTouchOverlay(it) }
            .addOnFailureListener { toast("Không thể nhận diện văn bản!") }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showTouchOverlay(text: Text) {
        if (overlay != null) return

        val view = View(this).apply { setBackgroundColor(0x3300FF00) }

        view.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                val x = e.rawX.toInt()
                val y = e.rawY.toInt()
                val word = text.textBlocks
                    .flatMap { it.lines }
                    .flatMap { it.elements }
                    .firstOrNull { it.boundingBox?.contains(x, y) == true }
                    ?.text

                toast(if (word != null) "TỪ BẤM: [$word]" else "KHÔNG TÌM THẤY TỪ")
                removeOverlay()
            }
            true
        }

        overlay = view
        wm.addView(view, overlayParams(WindowManager.LayoutParams.MATCH_PARENT).apply {
            height = WindowManager.LayoutParams.MATCH_PARENT
        })
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    // ---------- Tiện ích ----------

    @Suppress("DEPRECATION")
    private fun overlayParams(width: Int) = WindowManager.LayoutParams(
        width,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    )

    private fun toBitmap(image: Image): Bitmap? = try {
        val p = image.planes[0]
        val fullWidth = p.rowStride / p.pixelStride
        val raw = Bitmap.createBitmap(fullWidth, image.height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(p.buffer)
        Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
    } catch (e: Exception) {
        null
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun startForegroundNotification() {
        val channelId = "floating_service_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "Floating Service", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("App Tra Từ Đang Chạy")
            .setContentText("Bấm bong bóng để tra từ trên màn hình")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else
            startForeground(1, notification)
    }

    override fun onDestroy() {
        removeOverlay()
        bubble?.let { runCatching { wm.removeView(it) } }
        display?.release()
        reader?.close()
        projection?.stop()
        recognizer.close()
        super.onDestroy()
    }
}
