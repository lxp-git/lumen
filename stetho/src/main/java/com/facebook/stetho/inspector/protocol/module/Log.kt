/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.stetho.inspector.protocol.module

import android.content.Context
import com.facebook.stetho.inspector.console.LogcatForwarder
import com.facebook.stetho.inspector.jsonrpc.JsonRpcPeer
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsDomain
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsMethod
import org.json.JSONObject

/**
 * CDP `Log` domain. Modern DevTools frontends (Chrome 60+) render `Log.entryAdded` in the
 * Console panel and ignore the legacy `Console.messageAdded`; this module backs that panel
 * with the device's logcat stream (buffered from Stetho init by [LogcatForwarder], so the
 * backlog from before the frontend attached is replayed on `enable`).
 */
class Log(context: Context) : ChromeDevtoolsDomain {
  init {
    // Begin capturing (and buffering) immediately, not on first frontend attach.
    LogcatForwarder.ensureStarted(context)
  }

  @ChromeDevtoolsMethod
  fun enable(peer: JsonRpcPeer, params: JSONObject?) {
    LogcatForwarder.addPeer(peer)
  }

  @ChromeDevtoolsMethod
  fun disable(peer: JsonRpcPeer, params: JSONObject?) {
    LogcatForwarder.removePeer(peer)
  }

  @ChromeDevtoolsMethod
  fun clear(peer: JsonRpcPeer, params: JSONObject?) {
    LogcatForwarder.clearBuffer()
  }
}
