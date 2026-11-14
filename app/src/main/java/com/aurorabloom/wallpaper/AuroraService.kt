package com.aurorabloom.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.view.Choreographer
import android.view.SurfaceHolder
import android.service.wallpaper.WallpaperService
import kotlin.math.sin
import kotlin.random.Random

class AuroraService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return AuroraEngine()
    }

    private inner class AuroraEngine : WallpaperService.Engine(), Choreographer.FrameCallback, SensorEventListener {

        private val choreographer = Choreographer.getInstance()
        private var isRendering = false

        private lateinit var sensorManager: SensorManager
        private var accelerometer: Sensor? = null
        private var filteredAccelX = 0f
        private var batteryLevel = 0.5f

        private val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        batteryLevel = level.toFloat() / scale.toFloat()
                    }
                }
            }
        }

        private var wavePhase = 0f
        private var sloshOffset = 0f
        private val bubbles = mutableListOf<Bubble>()
        private val random = Random(System.currentTimeMillis())

        private val bgPaint = Paint().apply { color = Color.parseColor("#0B0B0F") }
        private val liquidPaint = Paint().apply { isAntiAlias = true }
        private val glowPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 12f }
        private val textPaint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            color = Color.argb(35, 255, 255, 255)
            isFakeBoldText = true
        }

        data class Bubble(var x: Float, var y: Float, val radius: Float, val speed: Float)

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                registerReceiver(batteryReceiver, filter)

                accelerometer?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
                startRendering()
            } else {
                try {
                    unregisterReceiver(batteryReceiver)
                } catch (_: Exception) {}
                sensorManager.unregisterListener(this)
                stopRendering()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            super.onSurfaceDestroyed(holder)
            stopRendering()
            try {
                unregisterReceiver(batteryReceiver)
            } catch (_: Exception) {}
            sensorManager.unregisterListener(this)
        }

        private fun startRendering() {
            if (!isRendering) {
                isRendering = true
                choreographer.postFrameCallback(this)
            }
        }

        private fun stopRendering() {
            if (isRendering) {
                isRendering = false
                choreographer.removeFrameCallback(this)
            }
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!isRendering) return
            updatePhysics()
            drawFrame()
            choreographer.postFrameCallback(this)
        }

        private fun updatePhysics() {
            wavePhase += 0.05f
            val targetSlosh = -filteredAccelX * 12f
            sloshOffset += (targetSlosh - sloshOffset) * 0.15f

            val width = surfaceHolder.surfaceFrame.width().toFloat()
            val height = surfaceHolder.surfaceFrame.height().toFloat()

            if (bubbles.isEmpty() && width > 0 && height > 0) {
                for (i in 0..15) {
                    bubbles.add(
                        Bubble(
                            x = random.nextFloat() * width,
                            y = random.nextFloat() * height,
                            radius = random.nextFloat() * 6f + 2f,
                            speed = random.nextFloat() * 2f + 1f
                        )
                    )
                }
            }

            val liquidTopY = height * (1f - batteryLevel)
            for (bubble in bubbles) {
                bubble.y -= bubble.speed
                bubble.x += sin(wavePhase + bubble.y * 0.05f) * 0.8f
                if (bubble.y < liquidTopY) {
                    bubble.y = height + 20f
                    bubble.x = random.nextFloat() * width
                }
            }
        }

        private fun drawFrame() {
            val holder = surfaceHolder ?: return
            val canvas: Canvas = holder.lockCanvas() ?: return

            val width = canvas.width.toFloat()
            val height = canvas.height.toFloat()

            try {
                canvas.drawRect(0f, 0f, width, height, bgPaint)

                val liquidColor = when {
                    batteryLevel > 0.5f -> Color.parseColor("#2ECC71")
                    batteryLevel > 0.2f -> Color.parseColor("#F39C12")
                    else -> Color.parseColor("#E74C3C")
                }
                liquidPaint.color = liquidColor
                glowPaint.color = liquidColor
                glowPaint.alpha = 100

                textPaint.textSize = width * 0.22f
                val percentText = "${(batteryLevel * 100).toInt()}%"
                val textY = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
                canvas.drawText(percentText, width / 2f, textY, textPaint)

                val liquidTopY = height * (1f - batteryLevel) + sloshOffset
                val path = Path().apply {
                    moveTo(0f, height)
                    var x = 0f
                    val step = 15f
                    while (x <= width + step) {
                        val angle = (x * 0.012f) + wavePhase
                        val waveY = liquidTopY + sin(angle.toDouble()).toFloat() * 16f + (x - width / 2f) * (filteredAccelX * 0.003f)
                        if (x == 0f) moveTo(x, waveY) else lineTo(x, waveY)
                        x += step
                    }
                    lineTo(width, height)
                    close()
                }

                canvas.drawPath(path, glowPaint)
                canvas.drawPath(path, liquidPaint)

                for (bubble in bubbles) {
                    if (bubble.y >= liquidTopY) {
                        canvas.drawCircle(bubble.x, bubble.y, bubble.radius, bubble.paintSafe())
                    }
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun Paint.paintSafe(): Paint = this

        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                val alpha = 0.15f
                filteredAccelX = filteredAccelX * (1 - alpha) + event.values[0] * alpha
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
}
