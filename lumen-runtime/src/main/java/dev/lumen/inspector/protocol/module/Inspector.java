/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector.protocol.module;

import dev.lumen.inspector.jsonrpc.JsonRpcPeer;
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain;
import dev.lumen.inspector.protocol.ChromeDevtoolsMethod;

import org.json.JSONObject;

public class Inspector implements ChromeDevtoolsDomain {
  public Inspector() {
  }

  @ChromeDevtoolsMethod
  public void enable(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public void disable(JsonRpcPeer peer, JSONObject params) {
  }
}
