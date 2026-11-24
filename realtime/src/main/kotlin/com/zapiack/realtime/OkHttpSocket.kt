package com.zapiack.realtime

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** The default line: an OkHttp WebSocket. Pass your own [OkHttpClient] to share its connection pool, proxy and certificate settings. */
class OkHttpSocketFactory(
    private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(0, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build(),
) : RealtimeSocketFactory {
    override fun open(url: String, listener: RealtimeSocketListener): RealtimeSocket {
        val ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                    listener.onClosed(code)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(code)
                // A failure (no network, refused, reset) carries no close code; 1006 is what that is called.
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onClosed(1006)
            },
        )
        return object : RealtimeSocket {
            override fun send(text: String) {
                ws.send(text)
            }
            override fun close() {
                ws.close(1000, null)
            }
        }
    }
}
