/*
 * SPDX-FileCopyrightText: 2025 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.sftp

import android.os.Build
import org.apache.sshd.common.file.FileSystemFactory
import org.apache.sshd.common.file.root.RootedFileSystem
import org.apache.sshd.common.file.root.RootedFileSystemProvider
import org.apache.sshd.common.session.SessionContext
import java.io.IOException
import java.nio.file.AccessMode
import java.nio.file.DirectoryStream
import java.nio.file.FileSystem
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Exposes the native file system with "/" as root (like [org.apache.sshd.common.file.nativefs.NativeFileSystemFactory]),
 * but works around Android restricting access to the ancestors of the storage volumes (ie: "/", "/storage") from apps.
 * Without this, clients can't navigate from "/" to the [storageDirs], and even listing "/storage/emulated/0" fails for some
 * SFPT clients (like WinSCP), because it can't read the attributes of its ".." entry.
 *
 * When accessing an ancestor of the [storageDirs] fails, we make it look like a readable directory that only contains the path
 * towards the [storageDirs]. If it doesn't fail (eg: on a rooted phone), the real file system is exposed.
 */
class NativeStorageFileSystemFactory : FileSystemFactory {

    @Volatile
    var storageDirs: List<Path> = emptyList()
        set(value) {
            field = value.map { it.toAbsolutePath().normalize() }
        }

    override fun getUserHomeDir(session: SessionContext?): Path? = null

    override fun createFileSystem(session: SessionContext?): FileSystem {
        val provider = Provider()
        return try {
            provider.newFileSystem(Path.of("/"), emptyMap<String, Any>())
        } catch (e: IOException) {
            // Android 7.1 and older use the desugared java.nio, whose toRealPath("/") (called by newFileSystem) requires
            // read access to "/", which apps don't have. The registry newFileSystem adds it to is unused by us anyway.
            RootedFileSystem(provider, Path.of("/"), emptyMap<String, Any>())
        }
    }

    private inner class Provider : RootedFileSystemProvider() {

        // Returns the native paths of the storageDirs that are strict descendants of [path]
        private fun storageDirsUnder(path: Path): List<Path> {
            // We are rooted at "/", so the rooted and the native paths are the same
            val nativePath = Path.of(path.toAbsolutePath().normalize().toString())
            return storageDirs.filter { it.nameCount > nativePath.nameCount && it.startsWith(nativePath) }
        }

        // Returns the path of the first storage dir under path, to borrow its attributes
        private fun firstStorageDirUnder(path: Path): Path? {
            val storageDir = storageDirsUnder(path).firstOrNull() ?: return null
            return path.fileSystem.getPath(storageDir.toString())
        }

        // Android 7.1 and older use the desugared java.nio, which reports "dir/.." as both a directory and a symlink
        // (an invalid file type), so clients like WinSCP can't navigate up. Resolving the ".." first avoids it.
        private fun desugarSafe(path: Path): Path =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) path.normalize() else path

        override fun checkAccess(path: Path, vararg modes: AccessMode) {
            try {
                super.checkAccess(path, *modes)
            } catch (e: IOException) {
                if (AccessMode.WRITE in modes || storageDirsUnder(path).isEmpty()) throw e
            }
        }

        override fun <A : BasicFileAttributes> readAttributes(
            path: Path,
            type: Class<A>,
            vararg options: LinkOption
        ): A = try {
            super.readAttributes(desugarSafe(path), type, *options)
        } catch (e: IOException) {
            val validPath = firstStorageDirUnder(path) ?: throw e
            super.readAttributes(validPath, type, *options)
        }

        override fun readAttributes(
            path: Path,
            attributes: String,
            vararg options: LinkOption
        ): Map<String, Any> = try {
            super.readAttributes(desugarSafe(path), attributes, *options)
        } catch (e: IOException) {
            val validPath = firstStorageDirUnder(path) ?: throw e
            super.readAttributes(validPath, attributes, *options)
        }

        override fun newDirectoryStream(
            dir: Path,
            filter: DirectoryStream.Filter<in Path>
        ): DirectoryStream<Path> {
            val depth = dir.toAbsolutePath().normalize().nameCount
            val children = storageDirsUnder(dir)
                .map { it.getName(depth).toString() }
                .distinct()
                .map { dir.resolve(it) }
            val stream = try {
                super.newDirectoryStream(dir, filter)
            } catch (e: IOException) {
                if (children.isEmpty()) throw e
                return listStream(children.filter(filter::accept))
            }
            if (children.isEmpty()) return stream
            // The desugared java.nio of Android 7.1 and older lists a directory it can't read as empty instead of failing
            val entries = stream.use { it.toList() }
            return listStream(entries.ifEmpty { children.filter(filter::accept) })
        }

        private fun listStream(entries: List<Path>) = object : DirectoryStream<Path> {
            override fun iterator(): MutableIterator<Path> = entries.toMutableList().iterator()
            override fun close() {}
        }
    }
}
