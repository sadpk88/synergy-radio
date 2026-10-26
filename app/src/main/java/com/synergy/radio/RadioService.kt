package com.synergy.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Service d'arrière-plan avec ExoPlayer Media3 pour Synergy Radio.
 * Maintient le flux Icecast actif en permanence, même écran éteint ou application minimisée,
 * et répond instantanément aux commandes Play/Pause du centre de notification et de l'écran de verrouillage.
 */
class RadioService : Service() {

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // URL par défaut du flux IceCast / SonicPanel
    private var streamUrl = "http://fire.doscast.com:8080/stream"
    private var trackTitle = "Synergy Radio"
    private var trackArtist = "En direct"

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PLAY -> playRadio()
                ACTION_PAUSE -> pauseRadio()
                ACTION_TOGGLE -> togglePlayPause()
                ACTION_SET_URL -> {
                    intent.getStringExtra("url")?.let { newUrl ->
                        streamUrl = newUrl
                        if (exoPlayer?.isPlaying == true) {
                            playRadio()
                        }
                    }
                }
                ACTION_UPDATE_META -> {
                    trackTitle = intent.getStringExtra("title") ?: trackTitle
                    trackArtist = intent.getStringExtra("artist") ?: trackArtist
                    updateNotification()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Maintien du CPU éveillé pendant l'écoute (anti-veille profonde)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SynergyRadio::WakeLock")

        // Source HTTP robuste avec redirection cross-protocol (HTTP <-> HTTPS) et timeout adapté à Icecast
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setUserAgent("SynergyRadio/1.1 (Android)")

        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

        // Configuration d'attributs audio multimédia
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                setAudioAttributes(audioAttributes, true) // Focus audio automatique
                setHandleAudioBecomingNoisy(true) // Pause si débranchement écouteurs
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updateMediaSessionState(isPlaying)
                        updateNotification()
                        broadcastStateToWebView(isPlaying)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        error.printStackTrace()
                        // Bascule automatique entre http et https en cas d'erreur de certificat ou de port
                        if (streamUrl.startsWith("https://")) {
                            streamUrl = streamUrl.replace("https://", "http://")
                        } else if (streamUrl.contains("/8080/")) {
                            streamUrl = streamUrl.replace("/8080/", ":8080/")
                        }
                        exoPlayer?.let { player ->
                            val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
                            player.setMediaItem(mediaItem)
                            player.prepare()
                            player.play()
                        }
                    }
                })
            }

        // Configuration de la MediaSession pour l'écran de verrouillage et appareils Bluetooth
        mediaSession = MediaSessionCompat(this, "SynergyRadioSession").apply {
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { playRadio() }
                override fun onPause() { pauseRadio() }
            })
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY)
            addAction(ACTION_PAUSE)
            addAction(ACTION_TOGGLE)
            addAction(ACTION_SET_URL)
            addAction(ACTION_UPDATE_META)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val targetUrl = intent?.getStringExtra("url")
        if (!targetUrl.isNullOrEmpty()) {
            streamUrl = targetUrl
        }

        // Démarrer la notification de premier plan immédiatement pour satisfaire Android 8-14
        startForeground(NOTIFICATION_ID, buildNotification())

        val action = intent?.action
        when (action) {
            ACTION_PLAY -> playRadio()
            ACTION_PAUSE -> pauseRadio()
            ACTION_TOGGLE -> togglePlayPause()
            else -> {
                if (intent?.getBooleanExtra("startPlaying", false) == true) {
                    playRadio()
                }
            }
        }

        return START_STICKY
    }

    private fun playRadio() {
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(10 * 60 * 1000L /* 10 minutes */)
            }
            exoPlayer?.let { player ->
                val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
                player.setMediaItem(mediaItem)
                player.prepare()
                player.play()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun pauseRadio() {
        try {
            exoPlayer?.pause()
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun togglePlayPause() {
        if (exoPlayer?.isPlaying == true) {
            pauseRadio()
        } else {
            playRadio()
        }
    }

    private fun broadcastStateToWebView(isPlaying: Boolean) {
        val intent = Intent(MainActivity.ACTION_SYNC_STATE).apply {
            putExtra("isPlaying", isPlaying)
            putExtra("title", trackTitle)
            putExtra("artist", trackArtist)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun updateMediaSessionState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
        )
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Utiliser PendingIntent.getService pour piloter directement le Service sans dépendre d'un broadcast externe
        val toggleIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RadioService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isPlaying = exoPlayer?.isPlaying == true
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseText = if (isPlaying) "Pause" else "Lecture"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(trackTitle)
            .setContentText(trackArtist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .addAction(playPauseIcon, playPauseText, toggleIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0)
                    .setMediaSession(mediaSession?.sessionToken)
            )
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Synergy Radio Stream",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Contrôles de lecture en direct pour Synergy Radio"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(controlReceiver) } catch (e: Exception) {}
        if (wakeLock?.isHeld == true) wakeLock?.release()
        exoPlayer?.release()
        exoPlayer = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "SynergyRadioLiveChannel"
        const val NOTIFICATION_ID = 101

        const val ACTION_PLAY = "com.synergy.radio.ACTION_PLAY"
        const val ACTION_PAUSE = "com.synergy.radio.ACTION_PAUSE"
        const val ACTION_TOGGLE = "com.synergy.radio.ACTION_TOGGLE"
        const val ACTION_SET_URL = "com.synergy.radio.ACTION_SET_URL"
        const val ACTION_UPDATE_META = "com.synergy.radio.ACTION_UPDATE_META"
    }
}
