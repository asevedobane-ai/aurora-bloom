
package com.aurorabloom.wallpaper

import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.SurfaceHolder
import kotlin.math.*
import kotlin.random.Random

private data class Bubble(
    var x: Float,
    var y: Float,
    val radius: Float,
    val speed: Float
)

class AuroraService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return LiquidEngine()
    }

    inner class LiquidEngine : Engine(), SensorEventListener {

        private val handler = Handler(Looper.getMainLooper())

        private var running = false
        private var batteryLevel = 100

        private var gravityX = 0f
        private var gravityY = 0f

        private var tilt = 0f
        private var tiltVelocity = 0f

        private var wavePhase = 0f
        private var lastFrameTime = 0L

        private val bubbles = mutableListOf<Bubble>()

        private val sensorManager =
            getSystemService(Context.SENSOR_SERVICE) as SensorManager

        private val accelerometer =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_ACCELEROMETER
            )

        private val liquidPaint =
            Paint(Paint.ANTI_ALIAS_FLAG)

        private val surfacePaint =
            Paint(Paint.ANTI_ALIAS_FLAG)

        private val bubblePaint =
            Paint(Paint.ANTI_ALIAS_FLAG)

        private val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG)

        private val liquidPath = Path()
        private val surfacePath = Path()

        // Receive battery updates while the wallpaper is visible.
        private val batteryReceiver = object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                intent ?: return

                val level = intent.getIntExtra("level", -1)
                val scale = intent.getIntExtra("scale", 100)

                if (level >= 0 && scale > 0) {
                    batteryLevel = (
                        level * 100 / scale
                    ).coerceIn(0, 100)
                }
            }
        }

        // Main animation loop: approximately 60 FPS.
        private val frameRunnable = object : Runnable {

            override fun run() {
                if (!running) return

                drawFrame()

                handler.postDelayed(this, 16L)
            }
        }

        // Low-pass filter for accelerometer movement.
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return

            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {

                val alpha = 0.12f

                gravityX += alpha *
                    (event.values[0] - gravityX)

                gravityY += alpha *
                    (event.values[1] - gravityY)
            }
        }

        override fun onAccuracyChanged(
            sensor: Sensor?,
            accuracy: Int
        ) {
            // Not needed.
        }

        // Start and stop depending on wallpaper visibility.
        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                startWallpaper()
            } else {
                stopWallpaper()
            }
        }

        private fun startWallpaper() {
            if (running) return

            running = true

            lastFrameTime = SystemClock.uptimeMillis()

            // Get the current battery level immediately.
            val filter = IntentFilter(
                Intent.ACTION_BATTERY_CHANGED
            )

            val stickyIntent = registerReceiver(
                batteryReceiver,
                filter
            )

            batteryReceiver.onReceive(
                this@AuroraService,
                stickyIntent
            )

            // Only listen to the accelerometer while visible.
            accelerometer?.let {
                sensorManager.registerListener(
                    this,
                    it,
                    SensorManager.SENSOR_DELAY_GAME
                )
            }

            handler.removeCallbacks(frameRunnable)
            handler.post(frameRunnable)
        }

        private fun stopWallpaper() {
            if (!running) return

            running = false

            handler.removeCallbacks(frameRunnable)

            try {
                unregisterReceiver(batteryReceiver)
            } catch (_: Exception) {
                // Already unregistered.
            }

            sensorManager.unregisterListener(this)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopWallpaper()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stopWallpaper()
            super.onDestroy()
        }
        
        // Smoothly blend between two colors.
        private fun blendColors(
            start: Int,
            end: Int,
            amount: Float
        ): Int {

            val t = amount.coerceIn(0f, 1f)

            return Color.rgb(
                (
                    Color.red(start) +
                    (Color.red(end) - Color.red(start)) * t
                ).toInt(),

                (
                    Color.green(start) +
                    (Color.green(end) - Color.green(start)) * t
                ).toInt(),

                (
                    Color.blue(start) +
                    (Color.blue(end) - Color.blue(start)) * t
                ).toInt()
            )
        }

        // Battery color changes from green to amber to red.
        private fun getLiquidColor(): Int {

            val green = Color.rgb(45, 220, 130)
            val amber = Color.rgb(255, 180, 55)
            val red = Color.rgb(255, 65, 65)

            return if (batteryLevel >= 50) {

                blendColors(
                    green,
                    amber,
                    (100 - batteryLevel) / 50f
                )

            } else {

                blendColors(
                    amber,
                    red,
                    (50 - batteryLevel) / 50f
                )
            }
        }

        // Calculate the liquid's wavy surface.
        private fun getSurfaceY(
            x: Float,
            width: Float,
            baseSurface: Float
        ): Float {

            if (width <= 0f) return baseSurface

            val waveAmplitude = 13f

            val wave = sin(
                x / width * 9f + wavePhase
            ) * waveAmplitude

            // Tilt the liquid according to gravity.
            val slope = tilt * (x - width / 2f)

            return baseSurface + wave + slope
        }

        // Update the spring physics.
        private fun updatePhysics(deltaTime: Float) {

            val targetTilt = (
                -gravityX / SensorManager.GRAVITY_EARTH
            ).coerceIn(-1f, 1f) * 0.18f

            val spring = 7f
            val damping = 4f

            val acceleration =
                (targetTilt - tilt) * spring -
                tiltVelocity * damping

            tiltVelocity += acceleration * deltaTime
            tilt += tiltVelocity * deltaTime

            wavePhase += deltaTime * 2f
        }

        // Draw the liquid body.
        private fun drawLiquid(
            canvas: Canvas,
            width: Float,
            height: Float,
            baseSurface: Float,
            color: Int
        ) {

            liquidPath.reset()

            liquidPath.moveTo(0f, height)

            var x = 0f

            while (x <= width) {

                val y = getSurfaceY(
                    x,
                    width,
                    baseSurface
                )

                liquidPath.lineTo(x, y)

                x += 8f
            }

            liquidPath.lineTo(width, height)
            liquidPath.close()

            liquidPaint.shader = LinearGradient(
                0f,
                baseSurface,
                0f,
                height,

                Color.argb(
                    205,
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                ),

                Color.argb(
                    245,
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                ),

                Shader.TileMode.CLAMP
            )

            // Soft glow around the liquid.
            liquidPaint.setShadowLayer(
                25f,
                0f,
                -5f,
                color
            )

            canvas.drawPath(
                liquidPath,
                liquidPaint
            )

            liquidPaint.clearShadowLayer()
            liquidPaint.shader = null
        }

        // Draw the thin glowing surface line.
        private fun drawSurface(
            canvas: Canvas,
            width: Float,
            baseSurface: Float
        ) {

            surfacePath.reset()

            var x = 0f

            while (x <= width) {

                val y = getSurfaceY(
                    x,
                    width,
                    baseSurface
                )

                if (x == 0f) {
                    surfacePath.moveTo(x, y)
                } else {
                    surfacePath.lineTo(x, y)
                }

                x += 8f
            }

            surfacePaint.color = Color.WHITE
            surfacePaint.alpha = 110
            surfacePaint.strokeWidth = 2.5f
            surfacePaint.style = Paint.Style.STROKE

            canvas.drawPath(
                surfacePath,
                surfacePaint
            )

            surfacePaint.style = Paint.Style.FILL
        }
        
        // Create bubbles and animate them upwards.
        private fun updateBubbles(
            width: Float,
            height: Float,
            baseSurface: Float,
            deltaTime: Float
        ) {

            // Randomly create new bubbles.
            if (Random.nextFloat() < deltaTime * 2.5f) {

                bubbles.add(
                    Bubble(
                        x = Random.nextFloat() * width,
                        y = height + 10f,
                        radius = Random.nextFloat() * 4f + 2f,
                        speed = Random.nextFloat() * 35f + 25f
                    )
                )
            }

            val iterator = bubbles.iterator()

            while (iterator.hasNext()) {

                val bubble = iterator.next()

                // Bubbles rise through the liquid.
                bubble.y -= bubble.speed * deltaTime

                // Small horizontal movement.
                bubble.x += sin(
                    wavePhase + bubble.y * 0.02f
                ) * 0.35f

                // Remove bubbles that leave the liquid.
                val surfaceY = getSurfaceY(
                    bubble.x,
                    width,
                    baseSurface
                )

                if (bubble.y < surfaceY - 15f ||
                    bubble.y < -20f
                ) {
                    iterator.remove()
                }
            }

            // Prevent too many bubbles.
            while (bubbles.size > 35) {
                bubbles.removeAt(0)
            }
        }

        private fun drawBubbles(
            canvas: Canvas,
            width: Float,
            baseSurface: Float
        ) {

            bubblePaint.color = Color.WHITE
            bubblePaint.alpha = 95
            bubblePaint.style = Paint.Style.STROKE
            bubblePaint.strokeWidth = 1.5f

            for (bubble in bubbles) {

                val surfaceY = getSurfaceY(
                    bubble.x,
                    width,
                    baseSurface
                )

                if (bubble.y > surfaceY) {

                    canvas.drawCircle(
                        bubble.x,
                        bubble.y,
                        bubble.radius,
                        bubblePaint
                    )
                }
            }

            bubblePaint.style = Paint.Style.FILL
        }

        // Display a large faint battery percentage.
        private fun drawPercentage(
            canvas: Canvas,
            width: Float,
            height: Float,
            baseSurface: Float
        ) {

            val fillHeight = height - baseSurface

            textPaint.color = Color.WHITE
            textPaint.alpha = 65
            textPaint.textAlign = Paint.Align.CENTER

            textPaint.typeface = Typeface.create(
                "sans-serif",
                Typeface.BOLD
            )

            textPaint.textSize = width * 0.19f

            if (fillHeight < textPaint.textSize * 1.5f) {
                return
            }

            val textY =
                (baseSurface + height) / 2f +
                textPaint.textSize * 0.35f

            canvas.drawText(
                "$batteryLevel%",
                width / 2f,
                textY,
                textPaint
            )
        }
        
        // Draw one complete wallpaper frame.
        private fun drawFrame() {

            var canvas: Canvas? = null

            try {

                canvas = surfaceHolder.lockCanvas()

                if (canvas != null) {
                    render(canvas)
                }

            } catch (_: Exception) {

                // The Android wallpaper surface may be unavailable.
                // The next animation frame will try again.

            } finally {

                if (canvas != null) {
                    try {
                        surfaceHolder.unlockCanvasAndPost(canvas)
                    } catch (_: Exception) {
                        // Surface was destroyed.
                    }
                }
            }
        }

        // Render the complete scene.
        private fun render(canvas: Canvas) {

            val width = canvas.width.toFloat()
            val height = canvas.height.toFloat()

            if (width <= 0f || height <= 0f) return

            val now = SystemClock.uptimeMillis()

            val deltaTime = (
                (now - lastFrameTime) / 1000f
            ).coerceIn(0f, 0.05f)

            lastFrameTime = now

            // Dark charcoal background.
            canvas.drawColor(
                Color.rgb(9, 13, 20)
            )

            // Update liquid movement.
            updatePhysics(deltaTime)

            // Battery level determines the fill height.
            val fillHeight =
                height * batteryLevel / 100f

            val baseSurface = height - fillHeight

            val liquidColor = getLiquidColor()

            // Draw liquid.
            drawLiquid(
                canvas,
                width,
                height,
                baseSurface,
                liquidColor
            )

            // Draw glowing surface.
            drawSurface(
                canvas,
                width,
                baseSurface
            )

            // Animate and draw bubbles.
            updateBubbles(
                width,
                height,
                baseSurface,
                deltaTime
            )

            drawBubbles(
                canvas,
                width,
                baseSurface
            )

            // Draw battery percentage.
            drawPercentage(
                canvas,
                width,
                height,
                baseSurface
            )
        }
    }
}
