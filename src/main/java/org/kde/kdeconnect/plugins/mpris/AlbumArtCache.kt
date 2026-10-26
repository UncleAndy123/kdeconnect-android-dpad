/*
 * SPDX-FileCopyrightText: 2017 Matthijs Tijink <matthijstijink@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mpris

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.collection.LruCache
import androidx.core.content.getSystemService
import androidx.core.net.ConnectivityManagerCompat
import androidx.core.net.toUri
import org.kde.kdeconnect.NetworkPacket.Payload
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Handles the cache for album art: downloading (http/https), art transferred from the connected device, decoding at
 * the size the screen needs, and a memory and a disk cache. Plain Android code (this used to be Coil + OkHttp).
 */
internal object AlbumArtCache {
    private const val TAG = "KDE/Mpris/AlbumArtCache"

    /**
     * Urls we failed to fetch, so we don't retry them. Bounded, so they get retried eventually.
     */
    private val failedUrls = LruCache<String, Boolean>(10)

    /**
     * Urls currently being loaded or written to the disk cache.
     */
    private val pendingUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Urls we asked the connected device to transfer, with the time we asked, so we don't ask again while waiting.
     */
    private val requestedTransfers = ConcurrentHashMap<String, Long>()

    /**
     * After this long without an answer, we ask the connected device to transfer the art again.
     */
    private const val TRANSFER_REQUEST_TIMEOUT_MS = 30_000L

    /** At most this many bytes of decoded bitmaps in memory */
    private const val MEMORY_CACHE_BYTES = 8 * 1024 * 1024

    /** At most this many bytes of image files on disk; the least recently used go first */
    private const val DISK_CACHE_BYTES = 12L * 1000L * 1000L

    /** Downloads bigger than this are not album art */
    private const val MAX_DOWNLOAD_BYTES = 8L * 1024L * 1024L

    private val memoryCache = object : LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private lateinit var appContext: Context
    private lateinit var diskDir: File

    /** Bitmaps are decoded no bigger than this (the screen's long side, capped at 1024): no more is ever shown */
    private var maxSidePx = 1024

    /**
     * Used to check if the connection is metered
     */
    private lateinit var connectivityManager: ConnectivityManager

    /**
     * A list of plugins to notify on fetched album art
     */
    private val registeredPlugins = CopyOnWriteArrayList<MprisPlugin>()

    val ALLOWED_SCHEMES = listOf("http", "https", "file", "kdeconnect")

    /**
     * A list of art url schemes that require a transfer from the connected device.
     */
    private val DEVICE_FETCH_SCHEMES = listOf("file", "kdeconnect")

    /** One background thread: downloads, disk reads and decoding, one at a time (gentle on a slow phone) */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "KDE/AlbumArt").apply { priority = Thread.MIN_PRIORITY } }
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Initializes the disk cache. Needs to be called at least once before trying to use the cache
     *
     * @param context The context
     */
    @Synchronized
    fun initializeDiskCache(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
        connectivityManager = appContext.getSystemService()!!
        diskDir = File(appContext.cacheDir, "album_art_files")
        // The old Coil cache (its own journal format) is not read any more
        worker.execute { File(appContext.cacheDir, "album_art").deleteRecursively() }
        val metrics = appContext.resources.displayMetrics
        maxSidePx = maxOf(metrics.widthPixels, metrics.heightPixels).coerceIn(256, 1024)
    }

    /**
     * Registers a mpris plugin, such that it gets notified of fetched album art
     *
     * @param mpris The mpris plugin
     */
    fun registerPlugin(mpris: MprisPlugin) {
        registeredPlugins.add(mpris)
    }

    /**
     * Deregister a mpris plugin
     *
     * @param mpris The mpris plugin
     */
    fun deregisterPlugin(mpris: MprisPlugin?) {
        registeredPlugins.remove(mpris)
    }

    /**
     * Get the album art for the given url.
     * If it's not in the in-memory cache, will initiate a request to load it, and
     * [MprisPlugin.fetchedAlbumArt] will be called with it once loaded.
     *
     * @param albumUrl The album art url
     * @return A bitmap for the album art. Can be null if not (yet) found
     */
    fun getAlbumArt(albumUrl: String?, plugin: MprisPlugin, player: String?): Bitmap? {
        // If the url is invalid, return "no album art"
        if (albumUrl.isNullOrEmpty()) {
            return null
        }
        val url = albumUrl.toUri()

        // We currently only support http(s), file, and kdeconnect urls
        if (url.scheme !in ALLOWED_SCHEMES) {
            return null
        }

        if (!this::appContext.isInitialized) {
            Log.e(TAG, "The cache is not initialized!")
            return null
        }

        // First, check the in-memory cache
        memoryCache.get(albumUrl)?.let { return it }

        // Do not retry failed fetches
        if (failedUrls[albumUrl] != null) {
            return null
        }

        if (albumUrl in pendingUrls) {
            return null
        }

        val fromDevice = url.scheme in DEVICE_FETCH_SCHEMES
        if (fromDevice && !diskFile(albumUrl).exists()) {
            // Special-case file or kdeconnect, since we need to fetch it from the connected device
            val now = SystemClock.elapsedRealtime()
            var shouldAsk = false
            requestedTransfers.compute(albumUrl) { _, askedAt ->
                if (askedAt != null && now - askedAt < TRANSFER_REQUEST_TIMEOUT_MS) {
                    askedAt // Already waiting for it
                } else {
                    shouldAsk = true
                    now
                }
            }
            if (shouldAsk && !plugin.askTransferAlbumArt(albumUrl, player)) {
                // It doesn't support transferring the art, so mark it as failed
                requestedTransfers.remove(albumUrl)
                failedUrls.put(albumUrl, true)
            }
            return null
        }

        load(albumUrl, fromDevice)
        return null
    }

    /** The disk cache file for [albumUrl]: the SHA-1 of the url */
    private fun diskFile(albumUrl: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(albumUrl.toByteArray())
        return File(diskDir, digest.joinToString("") { "%02x".format(it) })
    }

    /**
     * Loads the album art (from the disk cache or the network) and hands it to the plugins
     *
     * @param albumUrl   The url
     * @param fromDevice Whether the art was transferred from the connected device, so it can only come from the disk cache
     */
    private fun load(albumUrl: String, fromDevice: Boolean) {
        val file = diskFile(albumUrl)
        // Only download art on unmetered networks (wifi etc.), but still use what's in the disk cache
        if (!fromDevice && !file.exists() && ConnectivityManagerCompat.isActiveNetworkMetered(connectivityManager)) {
            // Not a failure, it will be downloaded once we are on an unmetered network
            return
        }
        if (!pendingUrls.add(albumUrl)) {
            // Was already pending
            return
        }
        worker.execute {
            var bitmap: Bitmap? = null
            try {
                if (!file.exists()) {
                    if (fromDevice) throw IOException("Album art not in the disk cache: $albumUrl")
                    download(albumUrl, file)
                }
                file.setLastModified(System.currentTimeMillis()) // least recently used goes first
                bitmap = decode(file) ?: throw IOException("Not an image: $albumUrl")
                memoryCache.put(albumUrl, bitmap)
            } catch (e: Exception) {
                Log.d(TAG, "Failed to load album art: $albumUrl", e)
                failedUrls.put(albumUrl, true)
                // It might be an invalid image, don't keep it
                file.delete()
            } finally {
                pendingUrls.remove(albumUrl)
            }
            val art = bitmap ?: return@execute
            mainHandler.post {
                for (mpris in registeredPlugins) {
                    mpris.fetchedAlbumArt(albumUrl, art)
                }
            }
        }
    }

    /** Downloads [albumUrl] (following redirects) into [file], then trims the disk cache */
    private fun download(albumUrl: String, file: File) {
        var url = URL(albumUrl)
        var connection: HttpURLConnection
        var redirects = 0
        while (true) {
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            if (code in 300..399 && redirects++ < 5) {
                val location = connection.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                connection.disconnect()
                url = URL(url, location) // http <-> https redirects too
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw IOException("HTTP $code for $albumUrl")
            }
            break
        }
        try {
            if (connection.contentLengthLong > MAX_DOWNLOAD_BYTES) throw IOException("Too big: $albumUrl")
            writeToDisk(file) { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_DOWNLOAD_BYTES) throw IOException("Too big: $albumUrl")
                        out.write(buffer, 0, n)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Writes through a temporary file so a half-written image is never in the cache */
    private fun writeToDisk(file: File, write: (java.io.OutputStream) -> Unit) {
        diskDir.mkdirs()
        val tmp = File(diskDir, file.name + ".tmp")
        try {
            tmp.outputStream().buffered().use(write)
            if (!tmp.renameTo(file)) throw IOException("Can't store ${file.name}")
        } finally {
            tmp.delete()
        }
        trimDiskCache()
    }

    private fun trimDiskCache() {
        val files = diskDir.listFiles()?.filter { !it.name.endsWith(".tmp") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_CACHE_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_CACHE_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    /** Decodes [file] subsampled to about [maxSidePx] on its long side (never upscaled) */
    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSidePx) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val side = maxOf(bitmap.width, bitmap.height)
        if (side <= maxSidePx) return bitmap
        val scale = maxSidePx.toFloat() / side
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }

    /**
     * Transfer an asked-for album art payload to the disk cache.
     *
     * @param albumUrl The url of the album art (must be one of the [DEVICE_FETCH_SCHEMES])
     * @param payload  The payload input stream
     */
    fun payloadToDiskCache(albumUrl: String, payload: Payload?) {
        if (payload == null) {
            return
        }
        if (!this::appContext.isInitialized) {
            Log.e(TAG, "The disk cache is not initialized!")
            payload.close()
            return
        }
        val url = albumUrl.toUri()
        if (url.scheme !in DEVICE_FETCH_SCHEMES) {
            // Shouldn't happen (checked on receival of the url), but just to be sure
            Log.e(TAG, "Got invalid art url with payload: $albumUrl")
            payload.close()
            return
        }

        val file = diskFile(albumUrl)
        // Check if we already have this art, or are already fetching it
        val alreadyHandled = memoryCache.get(albumUrl) != null || file.exists() || !pendingUrls.add(albumUrl)
        // We got an answer, and from now on pendingUrls prevents asking again
        requestedTransfers.remove(albumUrl)
        if (alreadyHandled) {
            payload.close()
            return
        }

        worker.execute {
            var stored = false
            try {
                val inputStream = payload.inputStream ?: throw IOException("Payload without input stream")
                writeToDisk(file) { out -> inputStream.copyTo(out, 16 * 1024) }
                stored = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to receive album art: $albumUrl", e)
                failedUrls.put(albumUrl, true)
            } finally {
                payload.close()
                pendingUrls.remove(albumUrl)
            }
            // Now it's in the disk cache, so it can be loaded
            if (stored) {
                load(albumUrl, fromDevice = true)
            }
        }
    }
}
