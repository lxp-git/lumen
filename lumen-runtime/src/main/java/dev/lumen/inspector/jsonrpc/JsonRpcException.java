/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector.jsonrpc;

import dev.lumen.inspector.jsonrpc.protocol.JsonRpcError;
import dev.lumen.common.Util;

public class JsonRpcException extends Exception {
  private final JsonRpcError mErrorMessage;

  public JsonRpcException(JsonRpcError errorMessage) {
    super(errorMessage.code + ": " + errorMessage.message);
    mErrorMessage = Util.throwIfNull(errorMessage);
  }

  public JsonRpcError getErrorMessage() {
    return mErrorMessage;
  }
}
