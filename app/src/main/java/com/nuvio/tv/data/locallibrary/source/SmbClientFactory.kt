package com.nuvio.tv.data.locallibrary.source

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

internal object SmbClientFactory {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val IO_TIMEOUT_SECONDS = 15L

    fun newClient(): SMBClient = SMBClient(defaultConfig())

    fun defaultConfig(): SmbConfig = SmbConfig.builder()
        .withSocketFactory(BoundedConnectSocketFactory(CONNECT_TIMEOUT_MS))
        .withSoTimeout(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .withReadTimeout(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .withWriteTimeout(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .withTransactTimeout(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .withDfsEnabled(false)
        .build()

    private class BoundedConnectSocketFactory(private val connectTimeoutMs: Int) : SocketFactory() {
        override fun createSocket(): Socket = Socket()

        override fun createSocket(host: String, port: Int): Socket =
            Socket().apply { connect(InetSocketAddress(host, port), connectTimeoutMs) }

        override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
            Socket(null as java.net.InetAddress?, 0).apply {
                bind(InetSocketAddress(localHost, localPort))
                connect(InetSocketAddress(host, port), connectTimeoutMs)
            }

        override fun createSocket(host: java.net.InetAddress, port: Int): Socket =
            Socket().apply { connect(InetSocketAddress(host, port), connectTimeoutMs) }

        override fun createSocket(
            address: java.net.InetAddress,
            port: Int,
            localAddress: java.net.InetAddress,
            localPort: Int
        ): Socket = Socket(null as java.net.InetAddress?, 0).apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port), connectTimeoutMs)
        }
    }
}
