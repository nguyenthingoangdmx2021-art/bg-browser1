package com.senior.bgbrowser

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

class SilentAudioPlayer(private val context: Context) {

    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    @Volatile
    private var isPlaying = false
    private var mediaSession: MediaSessionCompat? = null

    fun start() {
        if (isPlaying) return
        isPlaying = true

        setupMediaSession()

        playbackThread = Thread({
            val sampleRate = 8000
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1024)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack = track
            val silentBuffer = ByteArray(bufferSize)

            try {
                track.play()
                while (isPlaying) {
                    track.write(silentBuffer, 0, silentBuffer.size)
                }
            } catch (_: Exception) {
            } finally {
                try {
                    track.stop()
                    track.release()
                } catch (_: Exception) {}
            }
        }, "SilentAudioKeepAliveThread").apply {
            isDaemon = true
            start()
        }
    }

    private fun setupMediaSession() {
        if (mediaSession == null) {
            mediaSession = MediaSessionCompat(context, "BgBrowserMediaSession").apply {
                setMetadata(
                    MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Background Tab Active")
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "BG Chromium Browser")
                        .build()
                )
                setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1.0f)
                        .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE)
                        .build()
                )
                isActive = true
            }
        }
    }

    fun stop() {
        isPlaying = false
        try {
            playbackThread?.interrupt()
            playbackThread = null
        } catch (_: Exception) {}

        mediaSession?.apply {
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0f)
                    .build()
            )
            isActive = false
            release()
        }
        mediaSession = null
    }
}
