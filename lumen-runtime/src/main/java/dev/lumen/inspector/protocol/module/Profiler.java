/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector.protocol.module;

import java.util.Collections;
import java.util.List;

import dev.lumen.inspector.jsonrpc.JsonRpcPeer;
import dev.lumen.inspector.jsonrpc.JsonRpcResult;
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain;
import dev.lumen.inspector.protocol.ChromeDevtoolsMethod;

import dev.lumen.json.annotation.JsonProperty;
import org.json.JSONObject;

public class Profiler implements ChromeDevtoolsDomain {
  public Profiler() {
  }

  @ChromeDevtoolsMethod
  public void enable(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public void disable(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public void setSamplingInterval(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public JsonRpcResult getProfileHeaders(JsonRpcPeer peer, JSONObject params) {
    ProfileHeaderResponse response = new ProfileHeaderResponse();
    response.headers = Collections.emptyList();
    return response;
  }

  private static class ProfileHeaderResponse implements JsonRpcResult {
    @JsonProperty(required = true)
    public List<ProfileHeader> headers;
  }

  private static class ProfileHeader {
    @JsonProperty(required = true)
    String typeId;

    @JsonProperty(required = true)
    String title;

    @JsonProperty(required = true)
    int uid;
  }
}
