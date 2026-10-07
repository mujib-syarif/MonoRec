package com.monorec.app.recorder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Pembungkus MediaMuxer yang aman dipakai dari beberapa thread.
 * Muxer baru dijalankan setelah semua track (video, dan audio bila ada) terdaftar;
 * sampel yang datang lebih awal ditahan dulu di memori.
 */
internal class SafeMuxer(fd: FileDescriptor, private val expectedTracks: Int) {

    private class Pending(val track: Int, val data: ByteArray, val info: MediaCodec.BufferInfo)

    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val pending = ArrayList<Pending>()
    private val lastPts = LongArray(4) { -1L }
    private var pendingBytes = 0
    private var added = 0

    val bytes = AtomicLong(0L)

    @Volatile
    var wasStarted = false
        private set
    private var released = false

    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        val track = muxer.addTrack(format)
        added++
        if (added == expectedTracks) {
            muxer.start()
            wasStarted = true
            for (p in pending) writeNow(p.track, ByteBuffer.wrap(p.data), p.info)
            pending.clear()
            pendingBytes = 0
        }
        return track
    }

    @Synchronized
    fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (released) return
        if (!wasStarted) {
            if (pendingBytes > MAX_PENDING_BYTES) return
            val copy = ByteArray(info.size)
            buf.get(copy)
            val i = MediaCodec.BufferInfo()
            i.set(0, info.size, info.presentationTimeUs, info.flags)
            pending.add(Pending(track, copy, i))
            pendingBytes += info.size
            return
        }
        writeNow(track, buf, info)
    }

    private fun writeNow(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        // Timestamp harus naik terus per track.
        val prev = lastPts[track]
        if (info.presentationTimeUs <= prev) info.presentationTimeUs = prev + 1
        lastPts[track] = info.presentationTimeUs
        muxer.writeSampleData(track, buf, info)
        bytes.addAndGet(info.size.toLong())
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        try {
            if (wasStarted) muxer.stop()
        } catch (_: Exception) {
        }
        try {
            muxer.release()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val MAX_PENDING_BYTES = 24 * 1024 * 1024
    }
}
