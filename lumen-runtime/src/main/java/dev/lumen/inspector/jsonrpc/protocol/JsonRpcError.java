/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector.jsonrpc.protocol;

import javax.annotation.Nullable;

import android.annotation.SuppressLint;

import dev.lumen.json.annotation.JsonProperty;
import dev.lumen.json.annotation.JsonValue;
import org.json.JSONObject;

@SuppressLint({ "UsingDefaultJsonDeserializer", "EmptyJsonPropertyUse" })
public class JsonRpcError {
  @JsonProperty(required = true)
  public ErrorCode code;

  @JsonProperty(required = true)
  public String message;

  @JsonProperty
  public JSONObject data;

  public JsonRpcError() {
  }

  public JsonRpcError(ErrorCode code, String message, @Nullable JSONObject data) {
    this.code = code;
    this.message = message;
    this.data = data;
  }

  public enum ErrorCode {
    PARSER_ERROR(-32700),
    INVALID_REQUEST(-32600),
    METHOD_NOT_FOUND(-32601),
    INVALID_PARAMS(-32602),
    INTERNAL_ERROR(-32603);

    private final int mProtocolValue;

    private ErrorCode(int protocolValue) {
      mProtocolValue = protocolValue;
    }

    @JsonValue
    public int getProtocolValue() {
      return mProtocolValue;
    }
  }
}
