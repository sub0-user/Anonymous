package org.server.anonymous.ui

import javafx.application.Platform
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Starts the JavaFX toolkit once per JVM and runs blocks on the FX thread. */
object JavaFxTestSupport {
    private var started = false

    private fun hasDisplay(): Boolean =
        !System.getenv("DISPLAY").isNullOrEmpty() ||
            !System.getenv("WAYLAND_DISPLAY").isNullOrEmpty()

    @Synchronized
    fun init() {
        if (started || Platform.isFxApplicationThread()) return
        val latch = CountDownLatch(1)
        Platform.startup {
            started = true
            latch.countDown()
        }
        check(latch.await(15, TimeUnit.SECONDS)) { "JavaFX toolkit did not start" }
    }

    fun <T> onFxThread(block: () -> T): T {
        assumeTrue(hasDisplay(), "Skipping FX test: no display available (use xvfb-run on headless CI)")
        init()
        if (Platform.isFxApplicationThread()) return block()
        var result: T? = null
        var error: Throwable? = null
        val latch = CountDownLatch(1)
        Platform.runLater {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(30, TimeUnit.SECONDS)) { "Timed out waiting for the FX thread" }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
