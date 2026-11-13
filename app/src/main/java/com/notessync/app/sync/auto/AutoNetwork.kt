package com.notessync.app.sync.auto

import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket

internal data class PeerEndpoint(val id: String, val host: String, val port: Int = AUTO_PORT)
internal const val AUTO_PORT = 47384
internal interface AutoNetwork {
    val address: String
    fun listen(): ServerSocket
    fun socket(): Socket
    fun discover(ownId: String, allowed: Set<String>, found: (PeerEndpoint) -> Unit, lost: (String) -> Unit): Closeable
}
