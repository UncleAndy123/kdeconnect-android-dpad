/*
 * SPDX-FileCopyrightText: 2014 Samoilenko Yuri <kinnalru@gmail.com>
 * SPDX-FileCopyrightText: 2024 ShellWen Chen <me@shellwen.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.sftp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import org.apache.sshd.common.keyprovider.AbstractKeyPairProvider
import org.apache.sshd.common.session.SessionContext
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.common.util.security.SecurityUtils.SECURITY_PROVIDER_REGISTRARS
import org.apache.sshd.scp.server.ScpCommandFactory
import org.apache.sshd.server.ServerBuilder
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.server.subsystem.SubsystemFactory
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpFileSystemAccessor
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.sftp.server.SftpSubsystemProxy
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.helpers.MediaStoreHelper
import org.kde.kdeconnect.helpers.RandomHelper
import org.kde.kdeconnect.helpers.security.RsaHelper
import org.kde.kdeconnect.helpers.security.constantTimeCompare
import org.kde.kdeconnect.plugins.sftp.saf.SafFileSystemFactory
import org.kde.kdeconnect.plugins.sftp.saf.SafPath
import org.slf4j.impl.HandroidLoggerAdapter
import java.io.IOException
import java.nio.channels.Channel
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets
import java.nio.file.CopyOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.security.KeyPair
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.PublicKey
import java.security.Security

/**
 * Android 7.1 and older have no AlgorithmParameters for "EC", but sshd's ECCurves needs it in its static initializer
 * (to sort the curves by key size), so without it starting the server throws ExceptionInInitializerError ("No EC params
 * for nistp384"). This exposes only BouncyCastle's (already bundled) implementation of it, as the last provider, so
 * nothing that already worked changes which provider it uses.
 */
private class EcParametersProvider : Provider(NAME, 1.0, "EC AlgorithmParameters for Android < 8") {
    init {
        put("AlgorithmParameters.EC", "org.bouncycastle.jcajce.provider.asymmetric.ec.AlgorithmParametersSpi")
    }

    companion object {
        const val NAME = "KdeConnectEcParameters"
    }
}

internal class SimpleSftpServer {
    private lateinit var sshd: SshServer

    val port: Int
        get() {
            if (!::sshd.isInitialized) return -1
            return sshd.port
        }

    val isStarted: Boolean
        get() {
            if (!::sshd.isInitialized) return false
            return sshd.isStarted
        }

    val isClosed: Boolean
        get() {
            if (!::sshd.isInitialized) return false
            return sshd.isClosed
        }

    private val passwordAuth = SimplePasswordAuthenticator()
    private val keyAuth = SimplePublicKeyAuthenticator()

    val isInitialized: Boolean
        get() = ::sshd.isInitialized

    /** Whether the running server exposes the native file system (true) or the SAF storage locations (false). */
    var isNativeFs: Boolean = false
        private set

    private lateinit var safFileSystemFactory: SafFileSystemFactory
    private lateinit var nativeFileSystemFactory: NativeStorageFileSystemFactory

    fun setSafStorageDirs(storageInfoList: List<SftpPlugin.StorageInfo>) {
        safFileSystemFactory.initRoots(storageInfoList)
    }

    fun setNativeStorageDirs(paths: List<Path>) {
        nativeFileSystemFactory.storageDirs = paths
    }

    fun initialize(context: Context, device: Device, nativeFs: Boolean = usesNativeFs(context)) {
        isNativeFs = nativeFs
        val sshd = ServerBuilder.builder().apply {
            fileSystemFactory(
                if (nativeFs) {
                    nativeFileSystemFactory = NativeStorageFileSystemFactory()
                    nativeFileSystemFactory
                } else {
                    safFileSystemFactory = SafFileSystemFactory(context)
                    safFileSystemFactory
                }
            )
        }.build()

        // Reuse this device keys for the ssh connection as well
        val keyPair = KeyPair(
            RsaHelper.getPublicKey(context),
            RsaHelper.getPrivateKey(context)
        )
        sshd.keyPairProvider = object : AbstractKeyPairProvider() {
            override fun loadKeys(session: SessionContext): Iterable<KeyPair> = listOf(keyPair)
        }

        sshd.commandFactory = ScpCommandFactory()
        sshd.subsystemFactories =
            listOf<SubsystemFactory>(SftpSubsystemFactory.Builder().apply {
                withFileSystemAccessor(object : SftpFileSystemAccessor {
                    fun notifyMediaStore(path: Path) {
                        kotlin.runCatching {
                            val uri = path.toUri().toString().toUri()
                            MediaStoreHelper.indexFile(context, uri)
                            uri
                        }.fold(
                            onSuccess = { Log.i(TAG, "Notified media store: $path, $it") },
                            onFailure = { Log.w(TAG, "Failed to notify media store: $path", it) }
                        )
                    }

                    override fun openFile(
                        subsystem: SftpSubsystemProxy?,
                        fileHandle: FileHandle?,
                        file: Path?,
                        handle: String?,
                        options: MutableSet<out OpenOption>?,
                        vararg attrs: FileAttribute<*>?
                    ): SeekableByteChannel {
                        if (file is SafPath) {
                            return file.fileSystem.provider().newByteChannel(file, options, *attrs)
                        }
                        return super.openFile(subsystem, fileHandle, file, handle, options, *attrs)
                    }

                    override fun removeFile(
                        subsystem: SftpSubsystemProxy?,
                        path: Path?,
                        isDirectory: Boolean
                    ) {
                        super.removeFile(subsystem, path, isDirectory)
                        path?.let { notifyMediaStore(it) }
                    }

                    override fun copyFile(
                        subsystem: SftpSubsystemProxy?,
                        src: Path?,
                        dst: Path?,
                        opts: MutableCollection<CopyOption>?
                    ) {
                        super.copyFile(subsystem, src, dst, opts)
                        dst?.let { notifyMediaStore(it) }
                    }

                    override fun renameFile(
                        subsystem: SftpSubsystemProxy?,
                        oldPath: Path?,
                        newPath: Path?,
                        opts: MutableCollection<CopyOption>?
                    ) {
                        super.renameFile(subsystem, oldPath, newPath, opts)
                        oldPath?.let { notifyMediaStore(it) }
                        newPath?.let { notifyMediaStore(it) }
                    }

                    override fun createLink(
                        subsystem: SftpSubsystemProxy?,
                        link: Path?,
                        existing: Path?,
                        symLink: Boolean
                    ) {
                        super.createLink(subsystem, link, existing, symLink)
                        link?.let { notifyMediaStore(it) }
                        existing?.let { notifyMediaStore(it) }
                    }

                    override fun closeFile(
                        subsystem: SftpSubsystemProxy?,
                        fileHandle: FileHandle?,
                        file: Path?,
                        handle: String?,
                        channel: Channel?,
                        options: MutableSet<out OpenOption>?
                    ) {
                        super.closeFile(subsystem, fileHandle, file, handle, channel, options)
                        if (options?.contains(StandardOpenOption.WRITE) == true) {
                            file?.let { notifyMediaStore(it) }
                        }
                    }
                })
            }.build())

        keyAuth.deviceKey = device.certificate.publicKey

        sshd.publickeyAuthenticator = keyAuth
        sshd.passwordAuthenticator = passwordAuth

        this.sshd = sshd
    }

    fun start(): Boolean {
        if (isStarted) return true

        regeneratePassword()

        PORT_RANGE.forEach { port ->
            try {
                sshd.port = port
                sshd.start()

                return true
            } catch (e: IOException) {
                Log.w("SftpServer", "Failed to start server on port $port, trying next port", e)
            }
        }

        Log.e("SftpServer", "No more ports available")
        return false
    }

    fun stop() {
        if (!::sshd.isInitialized) return

        try {
            sshd.stop(true)
        } catch (e: Exception) {
            Log.e("SFTP", "Exception while stopping the server", e)
        }
    }

    fun regeneratePassword(): String {
        return RandomHelper.randomString(28).also {
            passwordAuth.setPassword(it)
        }
    }

    internal class SimplePasswordAuthenticator : PasswordAuthenticator {
        private val sha: MessageDigest = try {
            MessageDigest.getInstance("SHA-256")
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException(e)
        }
        private var passwordHash: ByteArray = byteArrayOf()

        fun setPassword(password: String) {
            passwordHash = sha.digest(password.toByteArray(StandardCharsets.UTF_8))
        }

        override fun authenticate(user: String, password: String, session: ServerSession): Boolean {
            val receivedPasswordHash = sha.digest(password.toByteArray(StandardCharsets.UTF_8))
            return user == USER && constantTimeCompare(passwordHash, receivedPasswordHash)
        }
    }

    internal class SimplePublicKeyAuthenticator : PublickeyAuthenticator {
        var deviceKey: PublicKey? = null

        override fun authenticate(user: String, key: PublicKey, session: ServerSession): Boolean =
            user == USER && deviceKey == key
    }

    companion object {
        private const val TAG = "SimpleSftpServer"

        val SUPPORTS_NATIVEFS = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        /**
         * Up to Android 9 the storage permission alone gives full access to shared storage, so the native file
         * system works there too, without making the user pick folders one by one. Android 10 is the only
         * version where only SAF works (scoped storage, and no "all files access" yet).
         */
        val LEGACY_NATIVEFS = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

        fun hasLegacyStoragePermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

        /** Whether the server should expose the native file system instead of the SAF storage locations. */
        fun usesNativeFs(context: Context): Boolean =
            SUPPORTS_NATIVEFS || (LEGACY_NATIVEFS && hasLegacyStoragePermission(context))

        private val PORT_RANGE = 1739..1764
        const val USER: String = "kdeconnect"

        init {
            System.setProperty(SECURITY_PROVIDER_REGISTRARS, "") // disable BouncyCastle
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && Security.getProvider(EcParametersProvider.NAME) == null) {
                Security.addProvider(EcParametersProvider())
            }
            System.setProperty(
                "org.apache.sshd.common.io.IoServiceFactoryFactory",
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    // Use MINA instead NIO2 due to compatibility issues
                    // Android 7.1 (API 25) and below have issues with NIO2
                    // When we require API 26, we can remove this and the Mina dependency.
                    "org.apache.sshd.mina.MinaServiceFactoryFactory"
                } else {
                    "org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory"
                }
            )
            // Remove it when SSHD Core is fixed.
            // Android has no user home folder, so we need to set it to something.
            // `System.getProperty("user.home")` is not available on Android,
            // but it exists in SSHD Core's `org.apache.sshd.common.util.io.PathUtils.LazyDefaultUserHomeFolderHolder`.
            PathUtils.setUserHomeFolderResolver { Path.of("/") }

            // Disable SSHD logging due to performance degradation and being very noisy even in development
            HandroidLoggerAdapter.DEBUG = false
        }
    }
}
