package com.kocti.crystaldefense.pet.battle

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

/** Связь двух телефонов напрямую, без интернета и сервера. Все обратные вызовы приходят в главном потоке. */
class NearbyBattle(context: Context, private val listener: Listener) {
    interface Listener {
        fun onOpponentFound(endpointId: String, name: String)
        fun onOpponentLost(endpointId: String)
        fun onChallenged(endpointId: String, name: String)
        fun onConnected(endpointId: String)
        fun onConnectionFailed()
        fun onDisconnected()
        fun onMessage(bytes: ByteArray)
        fun onSearchFailed(statusCode: Int)
    }

    private val client = Nearby.getConnectionsClient(context.applicationContext)
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val serviceId = context.packageName
    private var outgoingChallenge: String? = null

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let(listener::onMessage)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val connections = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Вызвавший уже согласен, решение остаётся за тем, кого вызвали.
            if (endpointId == outgoingChallenge) accept(endpointId) else listener.onChallenged(endpointId, info.endpointName)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            outgoingChallenge = null
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                // При прямом соединении один на один поиск дальше не нужен.
                client.stopAdvertising()
                client.stopDiscovery()
                listener.onConnected(endpointId)
            } else {
                listener.onConnectionFailed()
            }
        }

        override fun onDisconnected(endpointId: String) = listener.onDisconnected()
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            listener.onOpponentFound(endpointId, info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) = listener.onOpponentLost(endpointId)
    }

    /** Телефон одновременно ищет соперников и виден им сам. [name] видят другие игроки, личных данных в нём нет. */
    fun start(name: String) {
        client.startAdvertising(name, serviceId, connections, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener(::reportFailure)
        client.startDiscovery(serviceId, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener(::reportFailure)
    }

    fun challenge(endpointId: String, name: String) {
        outgoingChallenge = endpointId
        client.requestConnection(name, endpointId, connections).addOnFailureListener {
            outgoingChallenge = null
            listener.onConnectionFailed()
        }
    }

    fun accept(endpointId: String) {
        client.acceptConnection(endpointId, payloads)
    }

    fun decline(endpointId: String) {
        client.rejectConnection(endpointId)
    }

    fun send(endpointId: String, bytes: ByteArray) {
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
    }

    private fun reportFailure(error: Exception) {
        val code = (error as? ApiException)?.statusCode ?: ConnectionsStatusCodes.ERROR
        val alreadyRunning = code == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING ||
            code == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING
        if (!alreadyRunning) listener.onSearchFailed(code)
    }
}
