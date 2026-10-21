package dev.lumen.server

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.LocalSocket
import dev.lumen.common.LogUtil
import java.io.IOException

/**
 * Accepts a local-socket peer only if it holds `android.permission.DUMP` (adb shell and adbd
 * do; other apps on the device do not), then hands the connection to [delegate].
 */
class SecureSocketHandler(
  private val context: Context,
  private val delegate: SocketLikeHandler,
) : SocketHandler {

  @Throws(IOException::class)
  override fun onAccepted(socket: LocalSocket) {
    val credentials = socket.peerCredentials
    val granted = context.checkPermission(Manifest.permission.DUMP, credentials.pid, credentials.uid)
    if (granted != PackageManager.PERMISSION_GRANTED) {
      LogUtil.e(
        "Unauthorized request: peer pid=%d uid=%d lacks %s",
        credentials.pid,
        credentials.uid,
        Manifest.permission.DUMP,
      )
      return
    }
    delegate.onAccepted(SocketLike(socket, LeakyBufferedInputStream(socket.inputStream, BUFFER_SIZE)))
  }

  private companion object {
    /** Lets the HTTP layer hand bytes it read past the request head to the WebSocket layer. */
    const val BUFFER_SIZE = 256
  }
}
