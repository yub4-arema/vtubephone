package com.vtubephone.app.bridge

import android.view.Choreographer
import androidx.webkit.JavaScriptReplyProxy
import com.vtubephone.app.sensing.SensingService
import com.vtubephone.core.gesture.TouchPhase
import org.json.JSONArray
import org.json.JSONObject

/** Origin/main-frame checks stay in MainActivity. This only transports raw samples; no reflexes. */
class AvatarBridge(private val sensing: SensingService) : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private var reply: JavaScriptReplyProxy? = null
    private var resumed = false
    private var scheduled = false
    private var nextFrameNs = 0L

    /** Page messages this layer does not own (bump, lifeReady …); return true when handled. */
    var inbound: ((JSONObject) -> Boolean)? = null

    fun onPageStarted() { reply = null; unschedule() }

    /** UI thread only. Dropped while the page is (re)loading. */
    fun post(message: JSONObject) { reply?.postMessage(message.toString()) }

    fun handle(data: JSONObject, proxy: JavaScriptReplyProxy): Boolean {
        when (data.getString("type")) {
            "ready" -> { reply = proxy; schedule() }
            "touch" -> {
                val phase = when (data.getString("phase")) {
                    "down" -> TouchPhase.Down; "move" -> TouchPhase.Move
                    "up" -> TouchPhase.Up; "cancel" -> TouchPhase.Cancel
                    else -> return true
                }
                val x = data.getDouble("x"); val y = data.getDouble("y"); val age = data.getDouble("ageMs")
                if (!x.isFinite() || !y.isFinite() || !age.isFinite()) return true
                sensing.onWebTouch(age, data.getInt("pointerId"), phase, x.toFloat(), y.toFloat(), data.getBoolean("inAvatar"))
            }
            else -> return inbound?.invoke(data) ?: false
        }
        return true
    }

    fun resume() { resumed = true; schedule() }
    fun pause() { resumed = false; unschedule() }
    fun destroy() { pause(); reply = null }

    private fun schedule() {
        if (resumed && reply != null && !scheduled) {
            scheduled = true
            nextFrameNs = 0L
            choreographer.postFrameCallback(this)
        }
    }
    private fun unschedule() {
        choreographer.removeFrameCallback(this)
        scheduled = false
    }
    override fun doFrame(frameTimeNanos: Long) {
        if (!scheduled) return
        if (frameTimeNanos >= nextFrameNs) {
            val up = sensing.up
            reply?.postMessage(JSONObject().put("type", "motion")
                .put("up", JSONArray(up.map { it.toDouble() })).toString())
            // At most 60 Hz, also on 90/120 Hz displays. Do not accumulate frames after a stall.
            nextFrameNs = maxOf(nextFrameNs + 16_666_667L, frameTimeNanos + 1L)
        }
        choreographer.postFrameCallback(this)
    }
}
