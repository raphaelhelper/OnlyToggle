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
import android.graphics.Rect
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

    private val mainHandler = Handler(Looper.getMainLooper())

    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Đang chờ một frame màn hình để OCR
    private var captureRequested = false

    // Timeout khi không nhận được frame
    private var captureTimeoutRunnable: Runnable? = null


    override fun onBind(intent: Intent?): IBinder? {
        return null
    }


    override fun onCreate() {
        super.onCreate()

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        initScreenMetrics()

        startForegroundServiceNotification()
    }


    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val resultCode =
            intent?.getIntExtra(
                "RESULT_CODE",
                Activity.RESULT_OK
            ) ?: 0

        val dataIntent = getDataIntent(intent)

        if (dataIntent != null) {

            val projectionManager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            mediaProjection =
                projectionManager.getMediaProjection(
                    resultCode,
                    dataIntent
                )

            if (mediaProjection == null) {

                Toast.makeText(
                    this,
                    "Không tạo được MediaProjection!",
                    Toast.LENGTH_LONG
                ).show()

                stopSelf()

                return START_NOT_STICKY
            }

            setupImageReader()

            setupVirtualDisplay()

            showBubble()
        }

        return START_NOT_STICKY
    }


    // =========================================================
    // LẤY INTENT MEDIA PROJECTION
    // =========================================================

    private fun getDataIntent(intent: Intent?): Intent? {

        if (intent == null) {
            return null
        }

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            intent.getParcelableExtra(
                "DATA_INTENT",
                Intent::class.java
            )

        } else {

            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(
                "DATA_INTENT"
            )
        }
    }


    // =========================================================
    // SCREEN METRICS
    // =========================================================

    private fun initScreenMetrics() {

        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }


    // =========================================================
    // IMAGE READER
    // =========================================================

    @SuppressLint("WrongConstant")
    private fun setupImageReader() {

        imageReader?.close()

        imageReader =
            ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )

        imageReader?.setOnImageAvailableListener(
            { reader ->

                // Nếu không yêu cầu chụp thì chỉ
                // lấy và đóng ảnh để không làm đầy buffer.
                if (!captureRequested) {

                    val image =
                        reader.acquireLatestImage()

                    image?.close()

                    return@setOnImageAvailableListener
                }

                val image =
                    reader.acquireLatestImage()

                if (image == null) {
                    return@setOnImageAvailableListener
                }

                captureRequested = false

                captureTimeoutRunnable?.let {
                    mainHandler.removeCallbacks(it)
                }

                val bitmap =
                    imageToBitmap(image)

                image.close()

                if (bitmap == null) {

                    Toast.makeText(
                        this,
                        "Không chuyển được ảnh màn hình!",
                        Toast.LENGTH_LONG
                    ).show()

                    return@setOnImageAvailableListener
                }

                processImageWithMLKit(bitmap)

            },
            mainHandler
        )
    }


    // =========================================================
    // VIRTUAL DISPLAY
    // =========================================================

    private fun setupVirtualDisplay() {

        if (virtualDisplay != null) {
            return
        }

        val projection = mediaProjection

        val surface = imageReader?.surface

        if (projection == null || surface == null) {

            Toast.makeText(
                this,
                "MediaProjection chưa sẵn sàng!",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        virtualDisplay =
            projection.createVirtualDisplay(
                "ScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )
    }


    // =========================================================
    // TẠO BONG BÓNG
    // =========================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {

        // Tránh tạo bong bóng thứ hai
        if (bubbleView != null) {
            return
        }

        bubbleView =
            LayoutInflater.from(this)
                .inflate(
                    R.layout.layout_bubble,
                    null
                )

        val layoutParams =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },

                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,

                PixelFormat.TRANSLUCENT
            ).apply {

                gravity =
                    Gravity.TOP or Gravity.START

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

                    layoutParams.x =
                        initialX +
                        (event.rawX - initialTouchX)
                            .toInt()

                    layoutParams.y =
                        initialY +
                        (event.rawY - initialTouchY)
                            .toInt()

                    bubbleView?.let {

                        windowManager.updateViewLayout(
                            it,
                            layoutParams
                        )
                    }

                    true
                }


                MotionEvent.ACTION_UP -> {

                    val dx =
                        Math.abs(
                            event.rawX - initialTouchX
                        )

                    val dy =
                        Math.abs(
                            event.rawY - initialTouchY
                        )

                    // Không kéo -> coi là click
                    if (dx < 10 && dy < 10) {

                        captureAndStartToggleMode()
                    }

                    true
                }


                else -> false
            }
        }


        windowManager.addView(
            bubbleView,
            layoutParams
        )
    }


    // =========================================================
    // BẮT ĐẦU QUÉT
    // =========================================================

    private fun captureAndStartToggleMode() {

        if (mediaProjection == null) {

            Toast.makeText(
                this,
                "MediaProjection không tồn tại!",
                Toast.LENGTH_LONG
            ).show()

            return
        }


        if (virtualDisplay == null) {

            setupVirtualDisplay()
        }


        if (captureRequested) {

            return
        }


        captureRequested = true


        Toast.makeText(
            this,
            "Đang quét màn hình...",
            Toast.LENGTH_SHORT
        ).show()


        // Nếu sau 2 giây vẫn chưa nhận frame
        captureTimeoutRunnable =
            Runnable {

                if (captureRequested) {

                    captureRequested = false

                    Toast.makeText(
                        this,
                        "Không nhận được ảnh màn hình!",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }


        mainHandler.postDelayed(
            captureTimeoutRunnable!!,
            2000
        )
    }


    // =========================================================
    // OCR
    // =========================================================

    private fun processImageWithMLKit(
        bitmap: Bitmap
    ) {

        val inputImage =
            InputImage.fromBitmap(
                bitmap,
                0
            )


        recognizer.process(inputImage)

            .addOnSuccessListener { visionText ->

                val wordCount =
                    countWords(visionText)

                Toast.makeText(
                    this,
                    "OCR xong: $wordCount từ",
                    Toast.LENGTH_SHORT
                ).show()


                enableTouchOverlay(
                    visionText,
                    bitmap.width,
                    bitmap.height
                )
            }

            .addOnFailureListener {

                Toast.makeText(
                    this,
                    "Không thể nhận diện văn bản!",
                    Toast.LENGTH_LONG
                ).show()
            }
    }


    // =========================================================
    // ĐẾM SỐ WORD OCR NHẬN ĐƯỢC
    // =========================================================

    private fun countWords(
        visionText: Text
    ): Int {

        var count = 0

        for (block in visionText.textBlocks) {

            for (line in block.lines) {

                count += line.elements.size
            }
        }

        return count
    }


    // =========================================================
    // BẬT LỚP TOUCH
    // =========================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun enableTouchOverlay(
        visionText: Text,
        imageWidth: Int,
        imageHeight: Int
    ) {

        if (overlayTouchView != null) {
            return
        }


        overlayTouchView =
            View(this).apply {

                // Xanh lá trong suốt
                setBackgroundColor(
                    0x3300FF00.toInt()
                )
            }


        val params =
            WindowManager.LayoutParams(

                WindowManager.LayoutParams.MATCH_PARENT,

                WindowManager.LayoutParams.MATCH_PARENT,

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

                } else {

                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },

                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,

                PixelFormat.TRANSLUCENT
            )


        overlayTouchView?.setOnTouchListener { _, event ->

            if (event.action ==
                MotionEvent.ACTION_DOWN
            ) {

                val rawX =
                    event.rawX.toInt()

                val rawY =
                    event.rawY.toInt()


                val mappedX =
                    if (screenWidth > 0) {

                        (
                            rawX.toFloat() *
                            imageWidth.toFloat() /
                            screenWidth.toFloat()
                        ).toInt()

                    } else {
                        rawX
                    }


                val mappedY =
                    if (screenHeight > 0) {

                        (
                            rawY.toFloat() *
                            imageHeight.toFloat() /
                            screenHeight.toFloat()
                        ).toInt()

                    } else {
                        rawY
                    }


                val matchedWord =
                    findWordAtCoordinates(
                        visionText,
                        mappedX,
                        mappedY
                    )


                if (matchedWord != null) {

                    Toast.makeText(
                        this,
                        "TỪ BẤM: [$matchedWord]\nX=$mappedX Y=$mappedY",
                        Toast.LENGTH_LONG
                    ).show()

                } else {

                    Toast.makeText(
                        this,
                        "KHÔNG TÌM THẤY TỪ\nX=$mappedX Y=$mappedY",
                        Toast.LENGTH_LONG
                    ).show()
                }


                // Test xong 1 lần -> tắt xanh
                disableTouchOverlay()
            }

            true
        }


        windowManager.addView(
            overlayTouchView,
            params
        )
    }


    // =========================================================
    // TÌM WORD THEO TỌA ĐỘ
    // =========================================================

    private fun findWordAtCoordinates(
        visionText: Text,
        x: Int,
        y: Int
    ): String? {

        for (block in visionText.textBlocks) {

            for (line in block.lines) {

                for (element in line.elements) {

                    val box: Rect? =
                        element.boundingBox

                    if (
                        box != null &&
                        box.contains(x, y)
                    ) {

                        return element.text
                    }
                }
            }
        }

        return null
    }


    // =========================================================
    // IMAGE -> BITMAP
    // =========================================================

    private fun imageToBitmap(
        image: Image
    ): Bitmap? {

        return try {

            val plane =
                image.planes[0]

            val buffer =
                plane.buffer

            val pixelStride =
                plane.pixelStride

            val rowStride =
                plane.rowStride

            val imageWidth =
                image.width

            val imageHeight =
                image.height

            val rowPadding =
                rowStride -
                pixelStride * imageWidth

            val bitmapWidth =
                imageWidth +
                rowPadding / pixelStride


            val bitmap =
                Bitmap.createBitmap(
                    bitmapWidth,
                    imageHeight,
                    Bitmap.Config.ARGB_8888
                )


            buffer.rewind()

            bitmap.copyPixelsFromBuffer(
                buffer
            )


            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                imageWidth,
                imageHeight
            )

        } catch (e: Exception) {

            null
        }
    }


    // =========================================================
    // FOREGROUND NOTIFICATION
    // =========================================================

    private fun startForegroundServiceNotification() {

        val channelId =
            "floating_service_channel"


        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    channelId,
                    "Floating Service",
                    NotificationManager.IMPORTANCE_LOW
                )

            getSystemService(
                NotificationManager::class.java
            )?.createNotificationChannel(
                channel
            )
        }


        val notification =
            NotificationCompat.Builder(
                this,
                channelId
            )

                .setContentTitle(
                    "App Tra Từ Đang Chạy"
                )

                .setContentText(
                    "Bấm bong bóng để tra từ trên màn hình"
                )

                .setSmallIcon(
                    R.drawable.ic_notification
                )

                .setOngoing(true)

                .build()


        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {

            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

        } else {

            startForeground(
                1,
                notification
            )
        }
    }


    // =========================================================
    // TẮT OVERLAY
    // =========================================================

    private fun disableTouchOverlay() {

        overlayTouchView?.let {

            try {

                windowManager.removeView(it)

            } catch (_: Exception) {
            }

            overlayTouchView = null
        }
    }


    // =========================================================
    // DỌN SERVICE
    // =========================================================

    override fun onDestroy() {

        captureRequested = false

        captureTimeoutRunnable?.let {
            mainHandler.removeCallbacks(it)
        }


        disableTouchOverlay()


        bubbleView?.let {

            try {

                windowManager.removeView(it)

            } catch (_: Exception) {
            }

            bubbleView = null
        }


        virtualDisplay?.release()
        virtualDisplay = null


        imageReader?.close()
        imageReader = null


        mediaProjection?.stop()
        mediaProjection = null


        recognizer.close()


        super.onDestroy()
    }
}
