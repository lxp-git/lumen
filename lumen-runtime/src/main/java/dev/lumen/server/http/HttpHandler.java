/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.server.http;

import dev.lumen.server.SocketLike;

import java.io.IOException;

public interface HttpHandler {
  boolean handleRequest(
      SocketLike socket,
      LightHttpRequest request,
      LightHttpResponse response)
      throws IOException;
}
