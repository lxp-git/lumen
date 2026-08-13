/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import android.util.Log;

import dev.lumen.common.LogRedirector;
import dev.lumen.common.Util;
import dev.lumen.inspector.jsonrpc.JsonRpcException;
import dev.lumen.inspector.jsonrpc.JsonRpcPeer;
import dev.lumen.inspector.jsonrpc.PendingRequest;
import dev.lumen.inspector.jsonrpc.protocol.JsonRpcError;
import dev.lumen.inspector.jsonrpc.protocol.JsonRpcRequest;
import dev.lumen.inspector.jsonrpc.protocol.JsonRpcResponse;
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain;
import dev.lumen.json.ObjectMapper;
import dev.lumen.websocket.CloseCodes;
import dev.lumen.websocket.SimpleEndpoint;
import dev.lumen.websocket.SimpleSession;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Implements a limited version of the Chrome Debugger WebSocket protocol (using JSON-RPC 2.0).
 * The most up-to-date documentation can be found in the Blink source code:
 * <a href="https://code.google.com/p/chromium/codesearch#chromium/src/third_party/WebKit/Source/devtools/protocol.json&q=protocol.json&sq=package:chromium&type=cs">protocol.json</a>
 */
public class ChromeDevtoolsServer implements SimpleEndpoint {
  private static final String TAG = "ChromeDevtoolsServer";

  /**
   * WebSocket endpoint path advertised in {@code /json/list[.webSocketDebuggerUrl]}.
   *
   * <p>Mirrors Android WebView's {@code DevToolsManagerDelegate} which exposes
   * each page at {@code /devtools/page/<targetId>}. Chrome's built-in
   * frontend (loaded via {@code chrome-devtools://devtools/bundled/inspector.html})
   * expects this exact shape; the legacy {@code /inspector} path Lumen used to
   * advertise routes through Chrome's "fallback frontend" loader instead, which
   * triggers stricter CSP and breaks modern Chrome's inspect link.</p>
   *
   * <p>Lumen only exposes a single conceptual page per process, so the
   * {@code targetId} segment is a constant.</p>
   */
  public static final String PATH = "/devtools/page/1";

  private final ObjectMapper mObjectMapper;
  private final MethodDispatcher mMethodDispatcher;
  private final Map<SimpleSession, JsonRpcPeer> mPeers =
      Collections.synchronizedMap(
          new HashMap<SimpleSession, JsonRpcPeer>());

  public ChromeDevtoolsServer(Iterable<ChromeDevtoolsDomain> domainModules) {
    mObjectMapper = new ObjectMapper();
    mMethodDispatcher = new MethodDispatcher(mObjectMapper, domainModules);
  }

  @Override
  public void onOpen(SimpleSession session) {
    LogRedirector.d(TAG, "onOpen");
    JsonRpcPeer peer = new JsonRpcPeer(mObjectMapper, session);
    peer.setUpgradePath(dev.lumen.websocket.UpgradeContext.getPath());
    mPeers.put(session, peer);
  }

  @Override
  public void onClose(SimpleSession session, int code, String reasonPhrase) {
    LogRedirector.d(TAG, "onClose: reason=" + code + " " + reasonPhrase);

    JsonRpcPeer peer = mPeers.remove(session);
    if (peer != null) {
      peer.invokeDisconnectReceivers();
    }
  }

  @Override
  public void onMessage(SimpleSession session, byte[] message, int messageLen) {
    LogRedirector.d(TAG, "Ignoring binary message of length " + messageLen);
  }

  @Override
  public void onMessage(SimpleSession session, String message) {
    if (LogRedirector.isLoggable(TAG, Log.VERBOSE)) {
      LogRedirector.v(TAG, "onMessage: message=" + message);
    }
    try {
      JsonRpcPeer peer = mPeers.get(session);
      Util.throwIfNull(peer);

      handleRemoteMessage(peer, message);
    } catch (IOException e) {
      if (LogRedirector.isLoggable(TAG, Log.VERBOSE)) {
        LogRedirector.v(TAG, "Unexpected I/O exception processing message: " + e);
      }
      closeSafely(session, CloseCodes.UNEXPECTED_CONDITION, e.getClass().getSimpleName());
    } catch (MessageHandlingException e) {
      LogRedirector.i(TAG, "Message could not be processed by implementation: " + e);
      closeSafely(session, CloseCodes.UNEXPECTED_CONDITION, e.getClass().getSimpleName());
    } catch (JSONException e) {
      LogRedirector.v(TAG, "Unexpected JSON exception processing message", e);
      closeSafely(session, CloseCodes.UNEXPECTED_CONDITION, e.getClass().getSimpleName());
    }
  }

  private void closeSafely(SimpleSession session, int code, String reasonPhrase) {
    session.close(code, reasonPhrase);
  }

  private void handleRemoteMessage(JsonRpcPeer peer, String message)
      throws IOException, MessageHandlingException, JSONException {
    // Parse as a generic JSONObject first since we don't know if this is a request or response.
    JSONObject messageNode = new JSONObject(message);
    if (messageNode.has("method")) {
      handleRemoteRequest(peer, messageNode);
    } else if (messageNode.has("result") || messageNode.has("error")) {
      // Chrome may reply with {id,error} only. Never tear down the socket —
      // that is what made Reconnect look dead after a successful 101.
      try {
        if (messageNode.has("id")) {
          handleRemoteResponse(peer, messageNode);
        }
      } catch (Exception ignored) {
        // Unmatched {id,error} — keep the socket.
      }
    } else {
      // Neither a method nor a result; ignore.
    }
  }

  private void handleRemoteRequest(JsonRpcPeer peer, JSONObject requestNode)
      throws MessageHandlingException {
    JsonRpcRequest request;
    request = mObjectMapper.convertValue(
        requestNode,
        JsonRpcRequest.class);
    // Do not copy incoming sessionId onto the peer. Page inspect (/devtools/page/1)
    // may send Target.attachToTarget; stamping that id on notifications would
    // hide Network events from the root session. Browser reconnect sets
    // sessionId only from Target.setAutoAttach.
    String method = request.method != null ? request.method : "?";
    if (method.startsWith("Network.")
        || method.startsWith("Target.")
        || method.startsWith("Page.")
        || method.startsWith("Runtime.")
        || method.startsWith("Log.")) {
      String preview = requestNode.toString();
      if (preview.length() > 180) {
        preview = preview.substring(0, 180) + "…";
      }
      LogRedirector.i("LumenCDP", "in " + preview);
    }

    JSONObject result = null;
    JSONObject error = null;
    try {
      result = mMethodDispatcher.dispatch(peer,
          request.method,
          request.params);
    } catch (JsonRpcException e) {
      logDispatchException(e);
      error = mObjectMapper.convertValue(e.getErrorMessage(), JSONObject.class);
    }
    if (request.id != null) {
      JsonRpcResponse response = new JsonRpcResponse();
      response.id = request.id;
      response.result = result;
      response.error = error;
      JSONObject jsonObject = mObjectMapper.convertValue(response, JSONObject.class);
      String responseString;
      try {
        responseString = jsonObject.toString();
      } catch (OutOfMemoryError e) {
        // JSONStringer can cause an OOM when the Json to handle is too big.
        response.result = null;
        response.error = mObjectMapper.convertValue(e.getMessage(), JSONObject.class);
        jsonObject = mObjectMapper.convertValue(response, JSONObject.class);
        responseString = jsonObject.toString();
      }
      peer.getWebSocket().sendText(responseString);
    }
  }

  private static void logDispatchException(JsonRpcException e) {
    JsonRpcError errorMessage = e.getErrorMessage();
    switch (errorMessage.code) {
      case METHOD_NOT_FOUND:
        LogRedirector.d(TAG, "Method not implemented: " + errorMessage.message);
        break;
      default:
        LogRedirector.w(TAG, "Error processing remote message", e);
    }
  }

  private void handleRemoteResponse(JsonRpcPeer peer, JSONObject responseNode)
      throws MismatchedResponseException {
    JsonRpcResponse response = mObjectMapper.convertValue(
        responseNode,
        JsonRpcResponse.class);
    PendingRequest pendingRequest = peer.getAndRemovePendingRequest(response.id);
    if (pendingRequest == null) {
      throw new MismatchedResponseException(response.id);
    }
    if (pendingRequest.callback != null) {
      pendingRequest.callback.onResponse(peer, response);
    }
  }

  @Override
  public void onError(SimpleSession session, Throwable ex) {
    LogRedirector.e(TAG, "onError: ex=" + ex.toString());
  }
}
