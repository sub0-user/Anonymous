package org.server.anonymous.business

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/** Owner-only (0600) permissions for local data stores; best-effort on non-POSIX systems. */
object PrivateFileOps {
    fun setPrivateFile(path: Path) = setPrivate(path, directory = false)

    fun setPrivateDir(path: Path) = setPrivate(path, directory = true)

    @Suppress("SwallowedException") // best-effort: non-POSIX filesystems simply skip chmod
    private fun setPrivate(
        path: Path,
        directory: Boolean,
    ) {
        if (System.getProperty("os.name").lowercase().contains("win")) return
        try {
            Files.setPosixFilePermissions(
                path,
                if (directory) {
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    )
                } else {
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                },
            )
        } catch (e: UnsupportedOperationException) {
            // Non-POSIX filesystem — best effort.
        }
    }
}
