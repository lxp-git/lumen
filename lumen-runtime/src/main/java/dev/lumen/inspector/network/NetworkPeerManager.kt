package dev.lumen.inspector.network

import dev.lumen.inspector.helper.ChromePeerManager

/**
 * DevTools peers that called `Network.enable`. Process-wide so the OkHttp interceptor can
 * push live events without a reference to the CDP module instance.
 */
class NetworkPeerManager private constructor() : ChromePeerManager() {
  companion object {
    @Volatile private var instance: NetworkPeerManager? = null

    @JvmStatic
    fun getInstanceOrNull(): NetworkPeerManager? = instance

    @JvmStatic
    fun getOrCreateInstance(): NetworkPeerManager =
      instance ?: synchronized(this) { instance ?: NetworkPeerManager().also { instance = it } }
  }
}
