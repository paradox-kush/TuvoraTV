package com.nuvio.tv.core.iptv

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Step 0.3b test helper — a raw-socket HTTP/1.1 server on loopback whose every connection is ONE
 * scripted exchange (`Connection: close`), so a test can prove what a real OkHttp client did on the
 * wire: when it connected, which request it sent, whether it closed the socket early (a cancelled
 * loser, a probe that stopped at 1 KB) and how many body bytes the server managed to push first.
 *
 * Distinct host names (`main.test`, `b1.test`, …) all resolve to 127.0.0.1 through [TEST_DNS], so the
 * failover race sees different hosts (it never races two servers on one host).
 */
class FakeHttpServer(private val handler: (Exchange) -> Unit) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val exchanges: MutableList<Exchange> = Collections.synchronizedList(mutableListOf())
    private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
    private val release = CountDownLatch(1)
    private val epochNs = System.nanoTime()

    init {
        thread(isDaemon = true, name = "fake-http-$port") {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                sockets += s
                thread(isDaemon = true, name = "fake-http-$port-conn") { serve(s) }
            }
        }
    }

    /** `http://<host>:<port>` — use a `.test` host so [TEST_DNS] maps it to this server. */
    fun url(host: String) = "http://$host:$port"

    private fun serve(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 120_000
            val input = s.getInputStream()
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val requestLine = lines.first()
            val headers = lines.drop(1).filter { ':' in it }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val ex = Exchange(requestLine, headers, input, s.getOutputStream(), (System.nanoTime() - epochNs) / 1_000_000, release)
            exchanges += ex
            runCatching { handler(ex) }.onFailure { if (it is IOException) ex.clientClosed = true }
        }
    }

    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = runCatching { input.read() }.getOrDefault(-1)
            if (b == -1) return null
            sb.append(b.toChar())
        }
        return sb.toString().trimEnd()
    }

    /** Requests whose target matches [predicate] (e.g. by query action). */
    fun count(predicate: (Exchange) -> Boolean): Int = synchronized(exchanges) { exchanges.count(predicate) }

    override fun close() {
        release.countDown()
        runCatching { server.close() }
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
    }

    class Exchange(
        val requestLine: String,
        val headers: Map<String, String>,
        private val input: InputStream,
        private val out: OutputStream,
        /** ms since the server started when this request arrived. */
        val arrivedAtMs: Long,
        private val release: CountDownLatch,
    ) {
        val target: String get() = requestLine.split(' ').getOrElse(1) { "" }
        val action: String? get() = "http://x$target".toHttpUrl().queryParameter("action")
        @Volatile var clientClosed = false
        val bodyBytesSent = AtomicLong(0)

        fun respond(code: Int, body: String, contentType: String = "application/json") {
            val bytes = body.toByteArray()
            out.write("HTTP/1.1 $code X\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes)
            out.flush()
            bodyBytesSent.addAndGet(bytes.size.toLong())
        }

        /** Accepts the request and never answers; returns when the CLIENT closes the socket (or the server shuts down). */
        fun hang() {
            try {
                while (input.read() != -1) Unit
                clientClosed = true
            } catch (_: IOException) {
                clientClosed = true
            }
        }

        /** 200 headers at once, then [body] trickled in [pieces] parts [pauseMs] apart (a slow-but-alive host). */
        fun trickle(body: String, pieces: Int, pauseMs: Long, contentType: String = "application/json") {
            val bytes = body.toByteArray()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            val step = (bytes.size + pieces - 1) / pieces
            var at = 0
            while (at < bytes.size) {
                val n = minOf(step, bytes.size - at)
                out.write(bytes, at, n); out.flush()
                at += n
                bodyBytesSent.set(at.toLong())
                if (at < bytes.size) Thread.sleep(pauseMs)
            }
        }

        /**
         * 200 headers at once, then [total] body bytes (the [prefix] first, then filler lines) written in
         * [chunk]-byte pieces every [pauseMs]; stops when the client closes the socket.
         */
        fun stream(prefix: String, total: Long, chunk: Int = 1024, pauseMs: Long = 0, contentType: String = "audio/x-mpegurl") {
            out.write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: $total\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            var sent = 0L
            val first = prefix.toByteArray()
            try {
                out.write(first); sent += first.size
                val line = "#EXTINF:-1,Filler\nhttp://x/1.ts\n".toByteArray()
                while (sent < total) {
                    var piece = ByteArray(0)
                    while (piece.size < chunk) piece += line
                    val n = minOf(piece.size.toLong(), total - sent).toInt()
                    out.write(piece, 0, n); out.flush()
                    sent += n
                    bodyBytesSent.set(sent)
                    if (pauseMs > 0) Thread.sleep(pauseMs)
                }
            } catch (_: IOException) {
                clientClosed = true
            } finally {
                bodyBytesSent.set(sent)
            }
        }
    }

    companion object {
        /** `*.test` → 127.0.0.1; everything else the system resolver. */
        val TEST_DNS = Dns { host ->
            if (host.endsWith(".test")) listOf(InetAddress.getByName("127.0.0.1")) else Dns.SYSTEM.lookup(host)
        }
    }
}
