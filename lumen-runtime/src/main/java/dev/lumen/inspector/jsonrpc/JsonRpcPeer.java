/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector.jsonrpc;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import java.nio.channels.NotYetConnectedException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import android.database.Observable;
import dev.lumen.common.LogRedirector;
import dev.lumen.inspector.jsonrpc.protocol.JsonRpcRequest;
import dev.lumen.common.Util;
import dev.lumen.json.ObjectMapper;
import dev.lumen.websocket.SimpleSession;

import org.json.JSONObject;

@ThreadSafe
public class JsonRpcPeer {
  private static final String TAG = "LumenCDP";

  private final SimpleSession mPeer;
  private final ObjectMapper mObjectMapper;

  @GuardedBy("this")
  private long mNextRequestId;

  @GuardedBy("this")
  private final Map<Long, PendingRequest> mPendingRequests = new HashMap<>();

  /**
   * Flattened CDP session this peer last addressed. Chrome 151 stamps
   * {@code sessionId} on page-domain messages after {@code Target.attachToTarget}.
   */
  @GuardedBy("this")
  @Nullable
  private String mSessionId;

  @GuardedBy("this")
  @Nullable
  private String mUpgradePath;

  /** requestIds that already received {@code Network.webSocketCreated} on this peer. */
  @GuardedBy("this")
  private final Set<String> mWsCreatedIds = new HashSet<>();

  /**
   * Synthetic transcript XHR rows already advertised on this peer. A second
   * {@code requestWillBeSent} would draw another Network row for the same socket.
   */
  @GuardedBy("this")
  private final Set<String> mWsTranscriptIds = new HashSet<>();

  private final DisconnectObservable mDisconnectObservable = new DisconnectObservable();

  public JsonRpcPeer(ObjectMapper objectMapper, SimpleSession peer) {
    mObjectMapper = objectMapper;
    mPeer = Util.throwIfNull(peer);
  }

  public SimpleSession getWebSocket() {
    return mPeer;
  }

  public synchronized void setSessionId(@Nullable String sessionId) {
    if (sessionId == null || sessionId.isEmpty()) {
      return;
    }
    if (!sessionId.equals(mSessionId)) {
      LogRedirector.i(TAG, "sessionId " + mSessionId + " -> " + sessionId);
      mSessionId = sessionId;
    }
  }

  @Nullable
  public synchronized String getSessionId() {
    return mSessionId;
  }

  public synchronized void setUpgradePath(@Nullable String path) {
    mUpgradePath = path;
  }

  @Nullable
  public synchronized String getUpgradePath() {
    return mUpgradePath;
  }

  public synchronized boolean isBrowserUpgrade() {
    return mUpgradePath != null && mUpgradePath.contains("/devtools/browser");
  }

  /**
   * @return true if this is the first {@code webSocketCreated} for {@code requestId}
   *     on this peer. Chrome 151 {@code webSocketCreated} always allocates a new
   *     NetworkRequest and replaces {@code #requestsById} — a second emit wipes
   *     the Messages tab.
   */
  public synchronized boolean markWsCreated(String requestId) {
    return mWsCreatedIds.add(requestId);
  }

  /**
   * @return true if this is the first WebSocket transcript XHR for {@code requestId}
   *     on this peer.
   */
  public synchronized boolean markWsTranscript(String requestId) {
    return mWsTranscriptIds.add(requestId);
  }

  public void invokeMethod(String method, Object paramsObject,
      @Nullable PendingRequestCallback callback)
      throws NotYetConnectedException {
    Util.throwIfNull(method);

    Long requestId = (callback != null) ? preparePendingRequest(callback) : null;

    // magic, can basically convert anything for some amount of runtime overhead...
    JSONObject params = mObjectMapper.convertValue(paramsObject, JSONObject.class);

    JsonRpcRequest message = new JsonRpcRequest(requestId, method, params);
    JSONObject jsonObject = mObjectMapper.convertValue(message, JSONObject.class);
    String sessionId = getSessionId();
    if (sessionId != null && !method.startsWith("Target.")) {
      try {
        jsonObject.put("sessionId", sessionId);
      } catch (org.json.JSONException e) {
        throw new RuntimeException(e);
      }
    }
    String requestString = jsonObject.toString();
    if (method.startsWith("Network.webSocket")) {
      String preview = requestString.length() > 220
          ? requestString.substring(0, 220) + "…"
          : requestString;
      LogRedirector.i(TAG, "out " + preview);
    }
    mPeer.sendText(requestString);
  }

  public void registerDisconnectReceiver(DisconnectReceiver callback) {
    mDisconnectObservable.registerObserver(callback);
  }

  public void unregisterDisconnectReceiver(DisconnectReceiver callback) {
    mDisconnectObservable.unregisterObserver(callback);
  }

  public void invokeDisconnectReceivers() {
    mDisconnectObservable.onDisconnect();
  }

  private synchronized long preparePendingRequest(PendingRequestCallback callback) {
    long requestId = mNextRequestId++;
    mPendingRequests.put(requestId, new PendingRequest(requestId, callback));
    return requestId;
  }

  public synchronized PendingRequest getAndRemovePendingRequest(long requestId) {
    return mPendingRequests.remove(requestId);
  }

  private static class DisconnectObservable extends Observable<DisconnectReceiver> {
    public void onDisconnect() {
      for (int i = 0, N = mObservers.size(); i < N; ++i) {
        final DisconnectReceiver observer = mObservers.get(i);
        observer.onDisconnect();
      }
    }
  }
}
