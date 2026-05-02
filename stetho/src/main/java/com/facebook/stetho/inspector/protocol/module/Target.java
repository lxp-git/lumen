/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.stetho.inspector.protocol.module;

import com.facebook.stetho.common.ProcessUtil;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcPeer;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcResult;
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsDomain;
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsMethod;
import com.facebook.stetho.json.annotation.JsonProperty;

import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

/**
 * Stub implementation of the Chrome DevTools {@code Target} domain.
 *
 * <p>Modern Chrome DevTools frontends call {@code Target.setDiscoverTargets}
 * and {@code Target.setAutoAttach} during their startup handshake; if the
 * server returns {@code METHOD_NOT_FOUND} the frontend stalls and never
 * proceeds to render panels (including Network).</p>
 *
 * <p>This domain doesn't represent a real "target tree" — Stetho exposes one
 * inspectable Android process. We acknowledge the discovery handshake by
 * emitting a single {@code Target.targetCreated} event describing ourselves,
 * which is enough for the frontend to commit to its main session.</p>
 */
public class Target implements ChromeDevtoolsDomain {

  /**
   * Stable id for the synthetic target this domain represents. The exact
   * value doesn't matter to the frontend as long as it stays consistent for
   * the duration of the session.
   */
  private static final String TARGET_ID = "stetho-main";
  private static final String TARGET_TYPE = "page";

  public Target() {
  }

  @ChromeDevtoolsMethod
  public JsonRpcResult getTargetInfo(JsonRpcPeer peer, JSONObject params) {
    GetTargetInfoResponse result = new GetTargetInfoResponse();
    result.targetInfo = describeSelf();
    return result;
  }

  @ChromeDevtoolsMethod
  public JsonRpcResult getTargets(JsonRpcPeer peer, JSONObject params) {
    GetTargetsResponse result = new GetTargetsResponse();
    result.targetInfos = Collections.singletonList(describeSelf());
    return result;
  }

  /**
   * The frontend invokes this immediately after opening a session and waits
   * for {@code Target.targetCreated} events before rendering tabs/panels.
   * We emit one event describing the current process so the frontend knows
   * which target it's debugging.
   */
  @ChromeDevtoolsMethod
  public void setDiscoverTargets(JsonRpcPeer peer, JSONObject params) {
    TargetCreatedEvent event = new TargetCreatedEvent();
    event.targetInfo = describeSelf();
    peer.invokeMethod("Target.targetCreated", event, null /* callback */);
  }

  @ChromeDevtoolsMethod
  public void setAutoAttach(JsonRpcPeer peer, JSONObject params) {
    // No iframes / OOPIF to auto-attach to in an Android process.
  }

  @ChromeDevtoolsMethod
  public void setRemoteLocations(JsonRpcPeer peer, JSONObject params) {
    // We don't proxy to remote browser contexts.
  }

  @ChromeDevtoolsMethod
  public void attachToTarget(JsonRpcPeer peer, JSONObject params) {
    // Stub: the frontend already has a session attached via the websocket.
  }

  @ChromeDevtoolsMethod
  public void detachFromTarget(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public void closeTarget(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public void exposeDevToolsProtocol(JsonRpcPeer peer, JSONObject params) {
  }

  private static TargetInfo describeSelf() {
    TargetInfo info = new TargetInfo();
    info.targetId = TARGET_ID;
    info.type = TARGET_TYPE;
    info.title = ProcessUtil.getProcessName();
    info.url = "stetho://" + ProcessUtil.getProcessName();
    info.attached = true;
    info.canAccessOpener = false;
    info.browserContextId = TARGET_ID;
    return info;
  }

  // ── Result and event payloads ──────────────────────────────────────────

  private static class TargetInfo {
    @JsonProperty(required = true) public String targetId;
    @JsonProperty(required = true) public String type;
    @JsonProperty(required = true) public String title;
    @JsonProperty(required = true) public String url;
    @JsonProperty(required = true) public boolean attached;
    @JsonProperty public boolean canAccessOpener;
    @JsonProperty public String browserContextId;
  }

  private static class GetTargetInfoResponse implements JsonRpcResult {
    @JsonProperty(required = true) public TargetInfo targetInfo;
  }

  private static class GetTargetsResponse implements JsonRpcResult {
    @JsonProperty(required = true) public List<TargetInfo> targetInfos;
  }

  private static class TargetCreatedEvent implements JsonRpcResult {
    @JsonProperty(required = true) public TargetInfo targetInfo;
  }
}
