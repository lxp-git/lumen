/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.websocket;

/**
 * Alternative to JSR-356's Session class but with a less insane J2EE-style API.
 */
public interface SimpleSession {
  void sendText(String payload);

  /**
   * Send a text frame now, bypassing the writer's coalescing window.
   * JSON-RPC responses use this so Chrome's request/response stays snappy.
   */
  default void sendTextImmediate(String payload) {
    sendText(payload);
  }

  void sendBinary(byte[] payload);

  /**
   * Request that the session be closed.
   *
   * @param closeReason Close reason, as per RFC6455
   * @param reasonPhrase Possibly arbitrary close reason phrase.
   */
  void close(int closeReason, String reasonPhrase);

  boolean isOpen();
}
