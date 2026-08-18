/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector.protocol.module;

import android.os.Build;

import dev.lumen.inspector.jsonrpc.JsonRpcPeer;
import dev.lumen.inspector.jsonrpc.JsonRpcResult;
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain;
import dev.lumen.inspector.protocol.ChromeDevtoolsMethod;
import dev.lumen.json.annotation.JsonProperty;

import org.json.JSONObject;

/**
 * Stub implementation of the Chrome DevTools {@code Browser} domain.
 *
 * <p>Modern Chrome DevTools frontends call {@code Browser.getVersion} during
 * startup to decide which features are available; the response is also shown
 * in the about-page header. We answer with a synthetic Lumen identity and
 * the on-device Android build info.</p>
 */
public class Browser implements ChromeDevtoolsDomain {

  public Browser() {
  }

  @ChromeDevtoolsMethod
  public JsonRpcResult getVersion(JsonRpcPeer peer, JSONObject params) {
    GetVersionResponse result = new GetVersionResponse();
    result.protocolVersion = "1.3";
    result.product = "Lumen";
    result.revision = "0.1.2";
    result.userAgent = "Lumen (Android " + Build.VERSION.RELEASE +
        "; API " + Build.VERSION.SDK_INT +
        "; " + Build.MANUFACTURER + " " + Build.MODEL + ")";
    result.jsVersion = "";
    return result;
  }

  @ChromeDevtoolsMethod
  public void close(JsonRpcPeer peer, JSONObject params) {
    // We don't close the host process.
  }

  @ChromeDevtoolsMethod
  public JsonRpcResult getWindowForTarget(JsonRpcPeer peer, JSONObject params) {
    WindowForTargetResponse result = new WindowForTargetResponse();
    result.windowId = 1;
    result.bounds = new WindowBounds();
    return result;
  }

  // ── Result payloads ────────────────────────────────────────────────────

  private static class GetVersionResponse implements JsonRpcResult {
    @JsonProperty(required = true) public String protocolVersion;
    @JsonProperty(required = true) public String product;
    @JsonProperty(required = true) public String revision;
    @JsonProperty(required = true) public String userAgent;
    @JsonProperty(required = true) public String jsVersion;
  }

  private static class WindowForTargetResponse implements JsonRpcResult {
    @JsonProperty(required = true) public int windowId;
    @JsonProperty(required = true) public WindowBounds bounds;
  }

  private static class WindowBounds {
    @JsonProperty public int left = 0;
    @JsonProperty public int top = 0;
    @JsonProperty public int width = 0;
    @JsonProperty public int height = 0;
    @JsonProperty public String windowState = "normal";
  }
}
