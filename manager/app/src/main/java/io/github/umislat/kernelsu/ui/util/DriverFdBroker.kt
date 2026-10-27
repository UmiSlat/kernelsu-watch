package io.github.umislat.kernelsu.ui.util

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.util.Log
import io.github.umislat.kernelsu.BuildConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watch-only bridge for the 32-bit manager.
 *
 * The manager receives the real `[ksu_driver]` fd from the lab watcher, but
 * libsu starts `libksud.so` with only stdin/stdout/stderr.  The official
 * `/proc/self/fd` scan in ksud therefore cannot see the fd, and the 32-bit
 * reboot syscall is blocked by the app's seccomp filter.
 *
 * This broker runs in the manager process and hands a duplicate of the real
 * driver fd to same-UID `ksud` children over an abstract Unix socket.
 */
object DriverFdBroker {
    private const val TAG = "KsuFdBroker"
    private val socketName = "ksu_driver_broker.${BuildConfig.APPLICATION_ID}"
    private const val DRIVER_FD_NAME = "anon_inode:[ksu_driver]"

    private val started = AtomicBoolean(false)
    @Volatile
    private var driverPfd: ParcelFileDescriptor? = null

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        Thread({ serve() }, "ksu-fd-broker").apply {
            isDaemon = true
            start()
        }
    }

    private fun serve() {
        val server = try {
            // The one-argument constructor binds an abstract-namespace socket,
            // matching the `\0ksu_driver_broker.<applicationId>` address used
            // by this package's ksud. Keeping the package in the name lets the
            // watch build coexist with the official manager during testing.
            LocalServerSocket(socketName)
        } catch (t: Throwable) {
            Log.w(TAG, "cannot start fd broker", t)
            return
        }

        while (true) {
            val client = try {
                server.accept()
            } catch (t: Throwable) {
                Log.w(TAG, "accept failed", t)
                continue
            }
            Thread({ handle(client) }, "ksu-fd-client").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(client: LocalSocket) {
        try {
            val credentials = runCatching { client.peerCredentials }.getOrNull()
            if (credentials == null || credentials.uid != Process.myUid()) {
                Log.w(TAG, "reject peer uid=${credentials?.uid}")
                return
            }

            val driver = getDriverPfd() ?: return
            val duplicate = ParcelFileDescriptor.dup(driver.fileDescriptor)
            try {
                client.setFileDescriptorsForSend(arrayOf(duplicate.fileDescriptor))
                client.outputStream.write(1)
                client.outputStream.flush()
            } finally {
                duplicate.close()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fd send failed", t)
        } finally {
            runCatching { client.close() }
        }
    }

    @Synchronized
    private fun getDriverPfd(): ParcelFileDescriptor? {
        driverPfd?.let { return it }
        val fd = waitForDriverFd(3_000) ?: return null
        // fromFd takes ownership of the raw fd. Keep the wrapper alive for the
        // lifetime of the process: closing it would close the manager's real
        // [ksu_driver] fd.
        return ParcelFileDescriptor.fromFd(fd).also { driverPfd = it }
    }

    private fun waitForDriverFd(timeoutMs: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            findDriverFd()?.let { return it }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return null
            }
        } while (System.currentTimeMillis() < deadline)
        return null
    }

    private fun findDriverFd(): Int? {
        val names = File("/proc/self/fd").list() ?: return null
        for (name in names) {
            val fd = name.toIntOrNull() ?: continue
            val target = runCatching { Os.readlink("/proc/self/fd/$fd") }.getOrNull() ?: continue
            if (target == DRIVER_FD_NAME) return fd
        }
        return null
    }
}
