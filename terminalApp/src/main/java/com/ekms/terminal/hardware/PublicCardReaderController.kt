package com.ekms.terminal.hardware

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** UI-safe lifecycle owner for one public-card reader scan. */
class PublicCardReaderController(
    private val onStateChanged: (PublicCardReaderState) -> Unit,
    private val onCardDetected: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val startPending = AtomicBoolean(false)
    private val serialPort = SerialPortController()
    private val reader = PublicM1CardReader(
        serialPort = serialPort,
        onStatus = { publish(PublicCardReaderState.AwaitingCard(it)) },
        onCardDetected = { rawUid ->
            publish(PublicCardReaderState.CardCaptured)
            mainHandler.post { onCardDetected(rawUid) }
        },
        onError = { publish(PublicCardReaderState.Error(it)) },
    )

    fun start() {
        if (!startPending.compareAndSet(false, true)) return
        publish(PublicCardReaderState.Connecting)

        worker.execute {
            try {
                reader.connect()
                reader.startPolling()
            } catch (error: Exception) {
                publish(
                    PublicCardReaderState.Error(
                        "Unable to start the public card reader: " +
                                (error.message ?: error.javaClass.simpleName),
                    ),
                )
            } finally {
                startPending.set(false)
            }
        }
    }

    fun stop() {
        worker.execute {
            reader.disconnect()
            publish(PublicCardReaderState.Stopped)
        }
    }

    fun close() {
        reader.disconnect()
        worker.shutdownNow()
    }

    private fun publish(state: PublicCardReaderState) {
        mainHandler.post { onStateChanged(state) }
    }
}

sealed interface PublicCardReaderState {
    data object Idle : PublicCardReaderState
    data object Connecting : PublicCardReaderState
    data class AwaitingCard(val message: String) : PublicCardReaderState
    data object CardCaptured : PublicCardReaderState
    data object Stopped : PublicCardReaderState
    data class Error(val message: String) : PublicCardReaderState
}