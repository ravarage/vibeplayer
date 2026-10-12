package com.vibeplayer.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.util.Rational
import com.vibeplayer.R

object PipHelper {
    const val ACTION_REWIND = "com.vibeplayer.PIP_REWIND"
    const val ACTION_PLAY_PAUSE = "com.vibeplayer.PIP_PLAY_PAUSE"
    const val ACTION_FORWARD = "com.vibeplayer.PIP_FORWARD"

    private const val MIN_ASPECT_RATIO = 1.0 / 2.39 // ~0.4184
    private const val MAX_ASPECT_RATIO = 2.39

    fun calculateAspectRatio(videoWidth: Int, videoHeight: Int): Rational {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return Rational(16, 9)
        }
        val ratio = videoWidth.toDouble() / videoHeight.toDouble()
        return when {
            ratio > MAX_ASPECT_RATIO -> Rational(239, 100)
            ratio < MIN_ASPECT_RATIO -> Rational(100, 239)
            else -> {
                fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
                val g = gcd(videoWidth, videoHeight).coerceAtLeast(1)
                val num = videoWidth / g
                val den = videoHeight / g
                if (num > 10000 || den > 10000) {
                    Rational((ratio * 1000).toInt(), 1000)
                } else {
                    Rational(num, den)
                }
            }
        }
    }

    fun calculateSourceRectHint(
        windowWidth: Int,
        windowHeight: Int,
        videoWidth: Int,
        videoHeight: Int
    ): Rect? {
        if (windowWidth <= 0 || windowHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return null
        }
        val winAspect = windowWidth.toFloat() / windowHeight.toFloat()
        val vidAspect = videoWidth.toFloat() / videoHeight.toFloat()
        return if (winAspect > vidAspect) {
            val visibleWidth = (windowHeight * vidAspect).toInt().coerceAtMost(windowWidth)
            val left = (windowWidth - visibleWidth) / 2
            Rect(left, 0, left + visibleWidth, windowHeight)
        } else {
            val visibleHeight = (windowWidth / vidAspect).toInt().coerceAtMost(windowHeight)
            val top = (windowHeight - visibleHeight) / 2
            Rect(0, top, windowWidth, top + visibleHeight)
        }
    }

    fun buildPipParams(
        context: Context,
        videoWidth: Int,
        videoHeight: Int,
        windowWidth: Int,
        windowHeight: Int,
        isPaused: Boolean,
        hasDuration: Boolean
    ): PictureInPictureParams {
        val aspect = calculateAspectRatio(videoWidth, videoHeight)
        val rectHint = calculateSourceRectHint(windowWidth, windowHeight, videoWidth, videoHeight)

        val rewindIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(ACTION_REWIND).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIntent = PendingIntent.getBroadcast(
            context,
            2,
            Intent(ACTION_PLAY_PAUSE).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val forwardIntent = PendingIntent.getBroadcast(
            context,
            3,
            Intent(ACTION_FORWARD).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rewindAction = RemoteAction(
            Icon.createWithResource(context, R.drawable.ic_pip_rewind),
            "Rewind 10s",
            "Rewind 10 seconds",
            rewindIntent
        )

        val playPauseAction = if (isPaused) {
            RemoteAction(
                Icon.createWithResource(context, R.drawable.ic_pip_play),
                "Play",
                "Play",
                playPauseIntent
            )
        } else {
            RemoteAction(
                Icon.createWithResource(context, R.drawable.ic_pip_pause),
                "Pause",
                "Pause",
                playPauseIntent
            )
        }

        val forwardAction = RemoteAction(
            Icon.createWithResource(context, R.drawable.ic_pip_forward),
            "Forward 10s",
            "Forward 10 seconds",
            forwardIntent
        )

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(aspect)
            .setSeamlessResizeEnabled(true)
            .setAutoEnterEnabled(!isPaused && hasDuration)
            .setActions(listOf(rewindAction, playPauseAction, forwardAction))

        if (rectHint != null) {
            builder.setSourceRectHint(rectHint)
        }

        return builder.build()
    }
}
