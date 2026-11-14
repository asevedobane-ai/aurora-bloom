
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
        
