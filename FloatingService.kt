package com.example.testwordtouch

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var overlayTouchView: View? = null

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        initScreenMetrics()
        startForegroundServiceNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_OK) ?: 0
        val dataIntent = intent?.getParcelableExtra<Intent>("DATA_INTENT")

        if (dataIntent != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, dataIntent)
            setupImageReader()
            showBubble()
        }
        return START_NOT_STICKY
    }

    private fun initScreenMetrics() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    @SuppressLint("WrongConstant")
    private fun setupImageReader() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
    }

    // 1. TẠO BONG BÓNG NỔI
    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.layout_bubble, null)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(bubbleView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Nếu người dùng nhấp vào (không kéo)
                    if (Math.abs(event.rawX - initialTouchX) < 10 && Math.abs(event.rawY - initialTouchY) < 10) {
                        captureAndStartToggleMode()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubbleView, layoutParams)
    }

    // 2. KÍCH HOẠT CHẾ ĐỘ RÀ TỪ (Chụp ảnh & OCR)
    private fun captureAndStartToggleMode() {
        Toast.makeText(this, "Đang quét màn hình...", Toast.LENGTH_SHORT).show()

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        // Chờ 200ms để màn hình render xong vào ImageReader
        bubbleView?.postDelayed({
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val bitmap = imageToBitmap(image)
                image.close()
                stopVirtualDisplay()

                if (bitmap != null) {
                    processImageWithMLKit(bitmap)
                }
            } else {
                stopVirtualDisplay()
                Toast.makeText(this, "Lỗi chụp màn hình, thử lại!", Toast.LENGTH_SHORT).show()
            }
        }, 200)
    }

    private fun stopVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
    }

    // 3. QUÉT ML KIT TÌM KHUNG VỊ TRÍ CÁC TỪ
    private fun processImageWithMLKit(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                enableTouchOverlay(visionText)
            }
            .addOnFailureListener {
                Toast.makeText(this, "Không thể nhận diện văn bản!", Toast.LENGTH_SHORT).show()
            }
    }

    // 4. PHỦ LỚP TRONG SUỐT VÀ BẮT TỌA ĐỘ CHẠM (X, Y)
    @SuppressLint("ClickableViewAccessibility")
    private fun enableTouchOverlay(visionText: Text) {
        if (overlayTouchView != null) return

        overlayTouchView = View(this).apply {
            setBackgroundColor(0x3300FF00.toInt()) // Màu xanh lá nhạt trong suốt để nhận biết đang bật mode
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        overlayTouchView?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val touchX = event.rawX.toInt()
                val touchY = event.rawY.toInt()

                // Tìm từ vựng chứa tọa độ touchX, touchY
                val matchedWord = findWordAtCoordinates(visionText, touchX, touchY)

                if (matchedWord != null) {
                    Toast.makeText(this, "TỪ BẤM: [$matchedWord] tại ($touchX, $touchY)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Không bấm trúng từ nào tại ($touchX, $touchY)", Toast.LENGTH_SHORT).show()
                }

                // Tắt lớp phủ sau khi bấm 1 lần (Toggle Off)
                disableTouchOverlay()
            }
            true
        }

        windowManager.addView(overlayTouchView, params)
    }

    private fun disableTouchOverlay() {
        overlayTouchView?.let {
            windowManager.removeView(it)
            overlayTouchView = null
        }
    }

    // ALGORITHM: Dò vị trí tọa độ ngón tay vào BoundingBox nào
    private fun findWordAtCoordinates(visionText: Text, x: Int, y: Int): String? {
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) { // Element chính là từng TỪ ĐƠN LẺ (Word)
                    val box: Rect? = element.boundingBox
                    if (box != null && box.contains(x, y)) {
                        return element.text
                    }
                }
            }
        }
        return null
    }

    // HÀM PHỤ: Chuyển Image thành Bitmap
    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
    }

    private fun startForegroundServiceNotification() {
        val channelId = "floating_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Floating Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("App Tra Từ Đang Chạy")
            .setContentText("Bấm bong bóng để tra từ trên màn hình")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { windowManager.removeView(it) }
        disableTouchOverlay()
        mediaProjection?.stop()
    }
}
