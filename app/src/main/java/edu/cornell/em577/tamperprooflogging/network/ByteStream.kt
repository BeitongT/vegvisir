package edu.cornell.em577.tamperprooflogging.network

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import edu.cornell.em577.tamperprooflogging.util.SingletonHolder
import java.util.concurrent.LinkedBlockingQueue


/**
 * Thread-safe reliable bidirectional byte stream between two network endpoints used to send and
 * receive data over the network, where each local send call is paired with a corresponding
 * remote receive call.
 */
class ByteStream private constructor(env: Pair<Context, String>) {

    companion object :
        SingletonHolder<ByteStream, Pair<Context, String>>(::ByteStream) {

        /**
         * Returns the service id. This represents the action this connection is for. When discovering,
         * we'll verify that the advertiser has the same service id before we consider connecting to them.
         */
        private const val serviceId = "vegvisir"

        /**
         * Returns the strategy we use to connect to other devices. Only devices using the same strategy
         * and service id will appear when discovering. Strategies determine how many incoming and outgoing
         * connections are possible at the same time, as well as how much bandwidth is available for use.
         */
        private val strategy = Strategy.P2P_STAR

        /** Represents a device we can talk to. */
        private data class Endpoint constructor(val id: String)

        /** Connection states. */
        private enum class State {
            IDLE,
            SEARCHING,
            CONNECTED
        }
    }

    val context = env.first
    val userId = env.second

    /** Our handler to Nearby Connections. Shared object between UI thread and background threads */
    private val mConnectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)

    /** The device we are currently connected to. */
    private val mEstablishedConnection = LinkedBlockingQueue<Endpoint>(1)

    /**
     * Identifier of the device we are currently connection to. Null if we are not connected to
     * any device.
     */
    @Volatile
    private var mEndpointId: Endpoint? = null

    /** Current state of the connection. */
    private var mState = State.IDLE

    /** Flag to indicate whether we are currently connecting */
    private var mIsConnecting = false

    /** Buffer of received byte arrays. */
    private val mRecvBuffer = LinkedBlockingQueue<ByteArray>()

    /** Callbacks for connections to other devices. */
    private val mConnectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            if (mEstablishedConnection.isEmpty()) {
                synchronized(mConnectionsClient) {
                    mConnectionsClient.acceptConnection(endpointId, mPayloadCallback)
                }
            } else {
                synchronized(mConnectionsClient) {
                    mConnectionsClient.rejectConnection(endpointId)
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (mEstablishedConnection.isEmpty()) {
                if (result.status.isSuccess) {
                    mEndpointId = Endpoint(endpointId)
                    mEstablishedConnection.put(mEndpointId)
                    setState(State.CONNECTED)
                } else if (mState == State.SEARCHING) {
                    setState(State.IDLE)
                    setState(State.SEARCHING)
                    startAdvertising()
                    startDiscovering()
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            setState(State.IDLE)
            setState(State.SEARCHING)
        }
    }

    /** Callbacks for payloads (bytes of data) sent from another device to us.  */
    private val mPayloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                if (endpointId == mEndpointId?.id) {
                    mRecvBuffer.put(payload.asBytes())
                } else {
                    synchronized(mConnectionsClient) {
                        mConnectionsClient.disconnectFromEndpoint(endpointId)
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }


    /** Sets the device to discovery mode. It will now listen for devices in advertising mode. */
    private fun startDiscovering() {
        mConnectionsClient
            .startDiscovery(
                serviceId,
                object : EndpointDiscoveryCallback() {
                    override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                        if (serviceId == info.serviceId) {
                            val endpoint = Endpoint(endpointId)
                            synchronized(mConnectionsClient) {
                                mConnectionsClient.stopDiscovery()
                                mConnectionsClient.requestConnection(userId, endpoint.id, mConnectionLifecycleCallback)
                            }
                        }
                    }

                    override fun onEndpointLost(endpointId: String) {}
                },
                DiscoveryOptions.Builder().setStrategy(strategy).build()
            )
    }

    /** Sets the device to advertising mode. It will broadcast to other devices in discovery mode. */
    private fun startAdvertising() {
        mConnectionsClient
            .startAdvertising(
                userId,
                serviceId,
                mConnectionLifecycleCallback,
                AdvertisingOptions.Builder().setStrategy(strategy).build()
            )
    }

    /** Resets and clears all state in Nearby Connections.  */
    private fun stopAllEndpoints() {
        mEndpointId = null
        mRecvBuffer.clear()
        synchronized(mConnectionsClient) {
            mConnectionsClient.stopAllEndpoints()
        }
        mEstablishedConnection.clear()
    }

    /**
     * Sets the state of the underlying connection of the byte stream, and trigger any
     * necessary changes to Google Nearby modes on state changes.
     */
    private fun setState(state: State) {
        if (mState !== state) {
            mState = state

            // Update Nearby Connections to the new state.
            when (state) {
                State.SEARCHING -> {
                    stopAllEndpoints()
                    startDiscovering()
                    startAdvertising()
                }
                State.CONNECTED -> {
                    synchronized(mConnectionsClient) {
                        mConnectionsClient.stopDiscovery()
                        mConnectionsClient.stopAdvertising()
                    }
                }
                State.IDLE -> stopAllEndpoints()
            }
        }
    }

    /** Enter advertising and listening mode simultaneously. */
    fun create() {
        setState(State.SEARCHING)
    }

    /** Block until a connection has been established and returns the endpointId. */
    fun establishConnection(): String {
        return mEstablishedConnection.take().id
    }

    /** Send the provided byte array over the link. Paired with a corresponding remote recv call. */
    fun send(endpointId: String, byteArray: ByteArray) {
        synchronized(mConnectionsClient) {
            mConnectionsClient.sendPayload(endpointId, Payload.fromBytes(byteArray))
        }
    }

    /** Blocking call that returns the byte array sent by the corresponding remote send call. */
    fun recv(): ByteArray {
        return mRecvBuffer.take()
    }

    /** Terminates all current outgoing connections and enters the IDLE state. */
    fun close() {
        setState(State.IDLE)
    }
}