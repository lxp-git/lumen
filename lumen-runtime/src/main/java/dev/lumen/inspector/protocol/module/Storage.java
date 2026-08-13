/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector.protocol.module;

import dev.lumen.inspector.jsonrpc.JsonRpcPeer;
import dev.lumen.inspector.jsonrpc.JsonRpcResult;
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain;
import dev.lumen.inspector.protocol.ChromeDevtoolsMethod;
import dev.lumen.json.annotation.JsonProperty;

import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

/**
 * Stub implementation of the Chrome DevTools {@code Storage} domain.
 *
 * <p>Distinct from {@link DOMStorage} (which models {@code localStorage} /
 * {@code sessionStorage}); the modern {@code Storage} domain covers cookies,
 * cache storage, IndexedDB, etc. across "storage keys" (a {@code https://}
 * origin or similar). The DevTools frontend probes {@code getStorageKey}
 * dozens of times during the Application panel's init pipeline and stalls
 * when those calls error out — even when the developer never opens that
 * panel.</p>
 *
 * <p>We hand back a stable synthetic storage key and acknowledge the rest of
 * the surface area; a real Lumen-style storage browser would belong here
 * but isn't required for Network panel functionality, which is what this
 * stub is keeping unblocked.</p>
 */
public class Storage implements ChromeDevtoolsDomain {

  private static final String DEFAULT_STORAGE_KEY = "lumen-default";

  public Storage() {
  }

  @ChromeDevtoolsMethod
  public JsonRpcResult getStorageKey(JsonRpcPeer peer, JSONObject params) {
    GetStorageKeyResponse result = new GetStorageKeyResponse();
    result.storageKey = DEFAULT_STORAGE_KEY;
    return result;
  }

  @ChromeDevtoolsMethod
  public JsonRpcResult getCookies(JsonRpcPeer peer, JSONObject params) {
    GetCookiesResponse result = new GetCookiesResponse();
    result.cookies = Collections.emptyList();
    return result;
  }

  @ChromeDevtoolsMethod
  public void setCookies(JsonRpcPeer peer, JSONObject params) {
    // No persistent cookie store to mutate.
  }

  @ChromeDevtoolsMethod
  public void clearCookies(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public void clearDataForOrigin(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public void clearDataForStorageKey(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public JsonRpcResult getUsageAndQuota(JsonRpcPeer peer, JSONObject params) {
    UsageAndQuotaResponse result = new UsageAndQuotaResponse();
    result.usage = 0;
    result.quota = 0;
    result.overrideActive = false;
    result.usageBreakdown = Collections.emptyList();
    return result;
  }

  @ChromeDevtoolsMethod
  public void trackCacheStorageForOrigin(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public void trackIndexedDBForOrigin(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public void untrackCacheStorageForOrigin(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public void untrackIndexedDBForOrigin(JsonRpcPeer peer, JSONObject params) {
  }

  // ── Result payloads ────────────────────────────────────────────────────

  private static class GetStorageKeyResponse implements JsonRpcResult {
    @JsonProperty(required = true) public String storageKey;
  }

  private static class GetCookiesResponse implements JsonRpcResult {
    @JsonProperty(required = true) public List<JSONObject> cookies;
  }

  private static class UsageAndQuotaResponse implements JsonRpcResult {
    @JsonProperty(required = true) public long usage;
    @JsonProperty(required = true) public long quota;
    @JsonProperty(required = true) public boolean overrideActive;
    @JsonProperty(required = true) public List<JSONObject> usageBreakdown;
  }
}
