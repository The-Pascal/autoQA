package com.brahamchari.transport

import java.io.IOException
import java.net.Socket
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.io.*

interface Transport {
    val input: Source
    val output: Sink
    suspend fun close()
}

/**
 * A concrete implementation of the Transport interface that uses a
 * java.net.Socket for communication.
 *
 * @param socket The underlying Java socket used for communication.
 * @param manageSocketLifecycle If true (default), this transport will close the provided
 *                              socket when its own close() method is called. If false,
 *                              the caller is responsible for closing the socket separately.
 */
class SocketTransport(
    private val socket: Socket,
    private val manageSocketLifecycle: Boolean = true
) : Transport {

    // CORRECTED: Wrap the initialization logic in a lambda block `{ ... }`
    override val input by lazy {
        socket.getInputStream().asSource().buffered()
    }

    // CORRECTED: Wrap the initialization logic in a lambda block `{ ... }`
    override val output: Sink by lazy {
        socket.getOutputStream().asSink().buffered()
    }

    /**
     * Closes the transport and, optionally, the underlying socket.
     * This should be called to release resources when communication is finished.
     * It attempts to close the input source, output sink, and the socket itself,
     * swallowing potential IOExceptions during closure.
     */
    @Volatile private var isClosed = false // Add flag to prevent multiple close attempts

    override suspend fun close() {
        // Prevent running close multiple times concurrently or sequentially
        if (isClosed) {
            println("SocketTransport: Already closed or closing.")
            return
        }
        // Set flag immediately (though not perfectly atomic without more complex locking)
        // Good enough for most cases to avoid redundant work.
        isClosed = true

        println("SocketTransport: Closing transport for socket ${socket.localSocketAddress} <-> ${socket.remoteSocketAddress}")
        // Use Dispatchers.IO for blocking I/O operations like closing streams/sockets
        withContext(Dispatchers.IO) {
            // 1. Close the Okio Sink (flushes buffer and closes underlying stream)
            try {
                // Check if lazy property was initialized before closing
                    output.close()
                    println("SocketTransport: Output sink closed.")
            } catch (e: IOException) {
                System.err.println("SocketTransport: Error closing output sink: ${e.message}")
            } catch (e: Exception) { // Catch other potential exceptions during close
                System.err.println("SocketTransport: Unexpected error closing output sink: ${e.message}")
            }

            // 2. Close the Okio Source (closes underlying stream)
            try {
                // Check if lazy property was initialized before closing
                    input.close()
                    println("SocketTransport: Input source closed.")
            } catch (e: IOException) {
                System.err.println("SocketTransport: Error closing input source: ${e.message}")
            } catch (e: Exception) {
                System.err.println("SocketTransport: Unexpected error closing input source: ${e.message}")
            }

            // 3. Close the socket itself if managed by this transport
            if (manageSocketLifecycle) {
                try {
                    if (!socket.isClosed) {
                        socket.close()
                        println("SocketTransport: Underlying socket closed.")
                    }
                } catch (e: IOException) {
                    System.err.println("SocketTransport: Error closing socket: ${e.message}")
                } catch (e: Exception) {
                    System.err.println("SocketTransport: Unexpected error closing socket: ${e.message}")
                }
            } else {
                println("SocketTransport: Underlying socket lifecycle managed externally.")
            }
        }
        println("SocketTransport: Close operation finished.")
        // Note: isClosed remains true even if errors occurred during close
    }
}