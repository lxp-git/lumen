/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector.protocol.module;

import dev.lumen.common.ProcessUtil;
import dev.lumen.inspector.jsonrpc.JsonRpcPeer;
import dev.lumen.inspector.jsonrpc.JsonRpcResult;
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain;
import dev.lumen.inspector.protocol.ChromeDevtoolsMethod;
import dev.lumen.json.annotation.JsonProperty;

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
 * <p>This domain doesn't represent a real "target tree" — Lumen exposes one
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
  // Must match /json id and /devtools/page/<id> so inspect reconnect
  // (Target.attachToTarget) hits the same page Chrome first opened.
  private static final String TARGET_ID = "1";
  private static final String TARGET_TYPE = "page";
  private static final String SESSION_ID = "lumen-1";

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
    if (peer.isBrowserUpgrade()) {
      emitAttachedToTarget(peer);
    }
  }

  @ChromeDevtoolsMethod
  public void setAutoAttach(JsonRpcPeer peer, JSONObject params) {
    // Chrome reconnects a Browser=Chrome/* window via /devtools/browser and
    // waits for attachedToTarget (setAutoAttach flatten + waitForDebuggerOnStart).
    // Page inspect (/devtools/page/1) already is the page; do not emit there.
    boolean enable = params == null || params.optBoolean("autoAttach", true);
    if (enable && peer.isBrowserUpgrade()) {
      emitAttachedToTarget(peer);
    }
  }

  private void emitAttachedToTarget(JsonRpcPeer peer) {
    peer.setSessionId(SESSION_ID);
    AttachedToTargetEvent event = new AttachedToTargetEvent();
    event.sessionId = SESSION_ID;
    event.targetInfo = describeSelf();
    // Chrome 151 sends waitForDebuggerOnStart=true; if we echo that, the
    // frontend stalls until Runtime.runIfWaitingForDebugger.
    event.waitingForDebugger = false;
    peer.invokeMethod("Target.attachedToTarget", event, null /* callback */);
  }

  @ChromeDevtoolsMethod
  public void setRemoteLocations(JsonRpcPeer peer, JSONObject params) {
    // We don't proxy to remote browser contexts.
  }

  @ChromeDevtoolsMethod
  public JsonRpcResult attachToTarget(JsonRpcPeer peer, JSONObject params) {
    AttachToTargetResponse result = new AttachToTargetResponse();
    result.sessionId = SESSION_ID;
    AttachedToTargetEvent event = new AttachedToTargetEvent();
    event.sessionId = SESSION_ID;
    event.targetInfo = describeSelf();
    event.waitingForDebugger = false;
    peer.invokeMethod("Target.attachedToTarget", event, null /* callback */);
    return result;
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
    info.url = "lumen://" + ProcessUtil.getProcessName();
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

  private static class AttachToTargetResponse implements JsonRpcResult {
    @JsonProperty(required = true) public String sessionId;
  }

  private static class AttachedToTargetEvent implements JsonRpcResult {
    @JsonProperty(required = true) public String sessionId;
    @JsonProperty(required = true) public TargetInfo targetInfo;
    @JsonProperty(required = true) public boolean waitingForDebugger;
  }
}
