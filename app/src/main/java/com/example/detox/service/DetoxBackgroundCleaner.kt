package com.example.detox.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.example.detox.data.DetoxMode
import com.example.detox.data.DetoxSessionRepository

/**
 * Terminates cached background app processes and halts background media playback
 * (music, podcasts, video, browser audio) when a detox session starts.
 */
object DetoxBackgroundCleaner {

    private val audioFocusLock = Any()
    private var activeFocusRequest: AudioFocusRequest? = null
    private val noopAudioFocusListener = AudioManager.OnAudioFocusChangeListener { }

    fun terminateBackgroundApps(context: Context, mode: DetoxMode): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val pm = context.packageManager
        val repo = DetoxSessionRepository.getInstance(context)

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, 0)
            }
        }.getOrDefault(emptyList())

        val targetPackages = resolved
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
            .filterNot { pkg -> repo.isAllowedForegroundPackage(pkg, mode) }

        var killedCount = 0
        for (pkg in targetPackages) {
            runCatching {
                am.killBackgroundProcesses(pkg)
                killedCount++
            }
        }
        return killedCount
    }

    fun pauseBackgroundMediaPlayback(context: Context) {
        synchronized(audioFocusLock) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            // Do not steal audio focus if a phone call is ringing or active
            if (audioManager.mode == AudioManager.MODE_RINGTONE ||
                audioManager.mode == AudioManager.MODE_IN_CALL ||
                audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
            ) {
                return
            }

            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener(noopAudioFocusListener)
                        .build()
                    audioManager.requestAudioFocus(request)
                    activeFocusRequest = request
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(
                        noopAudioFocusListener,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN
                    )
                }
            }
        }
    }

    fun releaseMediaAudioFocus(context: Context) {
        synchronized(audioFocusLock) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activeFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                    activeFocusRequest = null
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.abandonAudioFocus(noopAudioFocusListener)
                }
            }
        }
    }
}
