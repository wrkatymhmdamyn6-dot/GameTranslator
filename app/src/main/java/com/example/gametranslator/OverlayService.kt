package com.example.gametranslator

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
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var buttonView: View? = null
    private var resultView: View? = null
    private var resultDismiss: Runnable? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var isCapturing = false

    companion object {
        const val CHANNEL_ID = "game_translator_channel"
        const val NOTIF_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>("data")
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundServiceNotification()
        try {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            startCaptureSession()
            showFloatingButton()
        } catch (error: Exception) {
            Toast.makeText(this, "شروع ضبط صفحه ناموفق بود: ${error.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "مترجم بازی", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("مترجم زنده فعال است")
            .setContentText("برای ترجمه، روی دکمه شناور بزن")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // A MediaProjection token creates exactly one VirtualDisplay for this session.
    // Each tap reads the latest frame from the same ImageReader.
    private fun startCaptureSession() {
        val projection = mediaProjection ?: error("مجوز ضبط صفحه موجود نیست")
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 3
        )

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                releaseCaptureResources()
                stopSelf()
            }
        }
        projection.registerCallback(projectionCallback!!, mainHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "GameTranslatorCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            mainHandler
        )
    }

    private fun showFloatingButton() {
        if (buttonView != null) return

        buttonView = LayoutInflater.from(this).inflate(R.layout.overlay_button, null)
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
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
        var isDrag = false

        buttonView!!.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDrag = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) isDrag = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDrag) captureAndTranslate()
                    true
                }
                else -> false
            }
        }
        windowManager.addView(buttonView, params)
    }

    private fun captureAndTranslate() {
        if (isCapturing) return
        if (imageReader == null || virtualDisplay == null) {
            showResult("ضبط صفحه فعال نیست. برنامه را دوباره راه‌اندازی کن.")
            return
        }
        isCapturing = true
        mainHandler.postDelayed({
            var image: Image? = null
            try {
                image = imageReader?.acquireLatestImage()
                if (image == null) {
                    showResult("تصویر صفحه دریافت نشد. دوباره تلاش کن.")
                    isCapturing = false
                    return@postDelayed
                }
                val bitmap = imageToBitmap(image!!)
                runOcrAndTranslate(bitmap)
            } catch (error: Exception) {
                showResult("خطا در دریافت تصویر: ${error.message}")
                isCapturing = false
            } finally {
                image?.close()
            }
        }, 250)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        val padded = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight)
        if (cropped !== padded) padded.recycle()
        return cropped
    }

    private fun runOcrAndTranslate(bitmap: Bitmap) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { visionText ->
                val extracted = visionText.text.trim()
                if (extracted.isEmpty()) {
                    showResult("متنی روی صفحه پیدا نشد.")
                    isCapturing = false
                } else {
                    translateToPersian(extracted)
                }
            }
            .addOnFailureListener { error ->
                showResult("خطا در تشخیص متن: ${error.localizedMessage}")
                isCapturing = false
            }
            .addOnCompleteListener {
                recognizer.close()
                if (!bitmap.isRecycled) bitmap.recycle()
            }
    }

    private fun translateToPersian(text: String) {
        val languageIdentifier = LanguageIdentification.getClient()
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { langCode ->
                val sourceLang = if (langCode == "und") {
                    TranslateLanguage.ENGLISH
                } else {
                    TranslateLanguage.fromLanguageTag(langCode) ?: TranslateLanguage.ENGLISH
                }

                if (sourceLang == TranslateLanguage.PERSIAN) {
                    showResult(text)
                    languageIdentifier.close()
                    isCapturing = false
                    return@addOnSuccessListener
                }

                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLang)
                    .setTargetLanguage(TranslateLanguage.PERSIAN)
                    .build()
                val translator = Translation.getClient(options)
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener {
                        translator.translate(text)
                            .addOnSuccessListener { translated ->
                                showResult(translated)
                                isCapturing = false
                            }
                            .addOnFailureListener { error ->
                                showResult("خطا در ترجمه: ${error.localizedMessage}")
                                isCapturing = false
                            }
                            .addOnCompleteListener { translator.close() }
                    }
                    .addOnFailureListener { error ->
                        showResult(
                            "دانلود مدل زبان ناموفق بود. برای اولین استفاده اینترنت لازم است: ${error.localizedMessage}"
                        )
                        translator.close()
                        isCapturing = false
                    }
                    .addOnCompleteListener { languageIdentifier.close() }
            }
            .addOnFailureListener { error ->
                showResult("خطا در تشخیص زبان متن: ${error.localizedMessage}")
                languageIdentifier.close()
                isCapturing = false
            }
    }

    private fun showResult(text: String) {
        mainHandler.post {
            removeResultView()
            resultView = LayoutInflater.from(this).inflate(R.layout.overlay_result, null).apply {
                findViewById<TextView>(R.id.tvResult).text = text
                setOnClickListener { removeResultView() }
            }

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                y = 150
            }

            windowManager.addView(resultView, params)
            val dismiss = Runnable { removeResultView() }
            resultDismiss = dismiss
            mainHandler.postDelayed(dismiss, 8000)
        }
    }

    private fun removeResultView() {
        resultDismiss?.let { mainHandler.removeCallbacks(it) }
        resultDismiss = null
        resultView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        resultView = null
    }

    private fun releaseCaptureResources() {
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) { }
        imageReader = null
    }

    override fun onDestroy() {
        removeResultView()
        buttonView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { }
        }
        buttonView = null
        releaseCaptureResources()
        projectionCallback?.let { callback ->
            try { mediaProjection?.unregisterCallback(callback) } catch (_: Exception) { }
        }
        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
    }
}
