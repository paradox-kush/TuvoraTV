package com.nuvio.tv.core.recordings.api

import java.util.concurrent.atomic.AtomicReference

/**
 * Where the Application publishes the one [RecordingsPort] (built by Hilt). Alarm receivers and the
 * foreground service are not injected, so they read it here; screens inject [RecordingsPort] directly.
 */
object RecordingsAccess {
    private val port = AtomicReference<RecordingsPort?>(null)

    fun register(value: RecordingsPort) {
        port.set(value)
    }

    fun current(): RecordingsPort? = port.get()
}
