/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector.network;

import android.os.SystemClock;
import dev.lumen.common.LogRedirector;
import dev.lumen.common.Utf8Charset;
import dev.lumen.inspector.console.CLog;
import dev.lumen.inspector.jsonrpc.JsonRpcPeer;
import dev.lumen.inspector.protocol.module.Console;
import dev.lumen.inspector.protocol.module.Network;
import dev.lumen.inspector.protocol.module.Page;
import org.json.JSONException;
import org.json.JSONObject;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Implementation of {@link NetworkEventReporter} which allows callers to inform the Lumen
 * system of network traffic.  Callers can safely eagerly access this class and store a
 * reference if they wish.  When WebKit Inspector clients are connected, the internal
 * implementation will be automatically wired up to them.
 */
public class NetworkEventReporterImpl implements NetworkEventReporter {
  private final AtomicInteger mNextRequestId = new AtomicInteger(0);
  private static final AtomicLong sWebSocketFrameSeq = new AtomicLong();
  @Nullable
  private ResourceTypeHelper mResourceTypeHelper;

  private static NetworkEventReporter sInstance;

  private NetworkEventReporterImpl() {
  }

  /**
   * Static accessor allowing callers to easily hook into the WebKit Inspector system without
   * creating dependencies on the main Lumen initialization code path.
   */
  public static synchronized NetworkEventReporter get() {
    if (sInstance == null) {
      sInstance = new NetworkEventReporterImpl();
    }
    return sInstance;
  }

  @Override
  public boolean isEnabled() {
    // Lumen records always once the agent is up. Peers only affect live CDP push.
    if (dev.lumen.LumenAgent.isStarted()) {
      return true;
    }
    NetworkPeerManager peerManager = getPeerManagerIfEnabled();
    return peerManager != null;
  }

  /** Peer manager if DevTools Network domain has subscribers; may be null while still recording. */
  @Nullable
  private NetworkPeerManager getPeerManagerForPush() {
    return getPeerManagerIfEnabled();
  }

  @Nullable
  private NetworkPeerManager getPeerManagerIfEnabled() {
    NetworkPeerManager peerManager = NetworkPeerManager.getInstanceOrNull();
    if (peerManager != null && peerManager.hasRegisteredPeers()) {
      return peerManager;
    }
    return null;
  }

  @Override
  public void requestWillBeSent(InspectorRequest request) {
    NetworkPeerManager peerManager = getPeerManagerIfEnabled();
    if (peerManager != null) {
      Network.Request requestJSON = new Network.Request();
      requestJSON.url = request.url();
      requestJSON.method = request.method();
      requestJSON.headers = formatHeadersAsJSON(request);
      requestJSON.postData = readBodyAsString(peerManager, request);

      // Hack to use the initiator of SCRIPT to generate a fake call stack that includes
      // the request's "friendly" name.
      String requestFriendlyName = request.friendlyName();
      Integer requestPriority = request.friendlyNameExtra();
      Network.Initiator initiatorJSON = new Network.Initiator();
      initiatorJSON.type = Network.InitiatorType.SCRIPT;
      initiatorJSON.stackTrace = new ArrayList<Console.CallFrame>();
      initiatorJSON.stackTrace.add(new Console.CallFrame(requestFriendlyName,
          requestFriendlyName,
          requestPriority != null ? requestPriority : 0 /* lineNumber */,
          0 /* columnNumber */));

      Network.RequestWillBeSentParams params = new Network.RequestWillBeSentParams();
      params.requestId = request.id();
      params.frameId = "1";
      params.loaderId = "1";
      params.documentURL = request.url();
      params.request = requestJSON;
      params.timestamp = stethoNow() / 1000.0;
      params.initiator = initiatorJSON;
      params.redirectResponse = null;

      // Type is now required as of at least WebKit Inspector rev @188492.  If you don't send
      // it, Chrome will refuse to draw the row in the Network tab until the response is
      // received (providing the type).  This delay is very noticable on slow networks.
      // WebSocket upgrades must be typed here: Chrome only shows the Messages tab
      // for ResourceType.WebSocket, and loadingFinished later would drop frames.
      params.type = isWebSocketRequest(request)
          ? Page.ResourceType.WEBSOCKET
          : Page.ResourceType.OTHER;

      peerManager.sendNotificationToPeers("Network.requestWillBeSent", params);
    }
  }

  @Nullable
  private static String readBodyAsString(
      NetworkPeerManager peerManager,
      InspectorRequest request) {
    try {
      byte[] body = request.body();
      if (body != null) {
        return new String(body, Utf8Charset.INSTANCE);
      }
    } catch (IOException | OutOfMemoryError e) {
      CLog.writeToConsole(
          peerManager,
          Console.MessageLevel.WARNING,
          Console.MessageSource.NETWORK,
          "Could not reproduce POST body: " + e);
    }
    return null;
  }

  @Override
  public void responseHeadersReceived(InspectorResponse response) {
    NetworkPeerManager peerManager = getPeerManagerIfEnabled();
    if (peerManager != null) {
      Network.Response responseJSON = new Network.Response();
      responseJSON.url = response.url();
      responseJSON.status = response.statusCode();
      responseJSON.statusText = response.reasonPhrase();
      responseJSON.headers = formatHeadersAsJSON(response);
      String contentType = getContentType(response);
      responseJSON.mimeType = contentType != null ?
          getResourceTypeHelper().stripContentExtras(contentType) :
          "application/octet-stream";
      responseJSON.connectionReused = response.connectionReused();
      responseJSON.connectionId = response.connectionId();
      responseJSON.fromDiskCache = response.fromDiskCache();
      Network.ResponseReceivedParams receivedParams = new Network.ResponseReceivedParams();
      receivedParams.requestId = response.requestId();
      receivedParams.frameId = "1";
      receivedParams.loaderId = "1";
      receivedParams.timestamp = stethoNow() / 1000.0;
      receivedParams.response = responseJSON;
      AsyncPrettyPrinter asyncPrettyPrinter =
          initAsyncPrettyPrinterForResponse(response, peerManager);
      receivedParams.type = response.statusCode() == 101
          ? Page.ResourceType.WEBSOCKET
          : determineResourceType(asyncPrettyPrinter, contentType, getResourceTypeHelper());
      peerManager.sendNotificationToPeers("Network.responseReceived", receivedParams);
    }
  }

  @Nullable
  private static AsyncPrettyPrinter initAsyncPrettyPrinterForResponse(
      InspectorResponse response,
      NetworkPeerManager peerManager) {
    AsyncPrettyPrinterRegistry registry = peerManager.getAsyncPrettyPrinterRegistry();
    AsyncPrettyPrinter asyncPrettyPrinter = createPrettyPrinterForResponse(response, registry);
    if (asyncPrettyPrinter != null) {
      peerManager.getResponseBodyFileManager().associateAsyncPrettyPrinterWithId(
          response.requestId(),
          asyncPrettyPrinter);
    }
     return asyncPrettyPrinter;
  }

  private static Page.ResourceType determineResourceType(
      AsyncPrettyPrinter asyncPrettyPrinter,
      String contentType,
      ResourceTypeHelper resourceTypeHelper) {
    if (asyncPrettyPrinter != null) {
      return asyncPrettyPrinter.getPrettifiedType().getResourceType();
    } else {
      return contentType != null ?
          resourceTypeHelper.determineResourceType(contentType) :
          Page.ResourceType.OTHER;
    }
  }

  //@VisibleForTesting
  @Nullable
  static AsyncPrettyPrinter createPrettyPrinterForResponse(
      InspectorResponse response,
      @Nullable AsyncPrettyPrinterRegistry registry) {
    if (registry != null) {
      for (int i = 0, count = response.headerCount(); i < count; i++) {
        AsyncPrettyPrinterFactory factory = registry.lookup(response.headerName(i));
        if (factory != null) {
          AsyncPrettyPrinter asyncPrettyPrinter = factory.getInstance(
              response.headerName(i),
              response.headerValue(i));
          return asyncPrettyPrinter;
        }
      }
    }
    return null;
  }

  @Override
  public InputStream interpretResponseStream(
      String requestId,
      @Nullable String contentType,
      @Nullable String contentEncoding,
      @Nullable InputStream availableInputStream,
      ResponseHandler responseHandler) {
    NetworkPeerManager peerManager = getPeerManagerIfEnabled();
    if (peerManager != null) {
      if (availableInputStream == null) {
        responseHandler.onEOF();
        return null;
      }
      Page.ResourceType resourceType =
          contentType != null ?
              getResourceTypeHelper().determineResourceType(contentType) :
              null;

      // There's this weird logic at play that only knows how to base64 decode certain kinds of
      // resources.
      boolean base64Encode = false;
      if (resourceType != null && resourceType == Page.ResourceType.IMAGE) {
        base64Encode = true;
      }

      try {
        OutputStream fileOutputStream =
            peerManager.getResponseBodyFileManager().openResponseBodyFile(
                requestId,
                base64Encode);
        return DecompressionHelper.teeInputWithDecompression(
            peerManager,
            requestId,
            availableInputStream,
            fileOutputStream,
            contentEncoding,
            responseHandler);
      } catch (IOException e) {
        CLog.writeToConsole(
            peerManager,
            Console.MessageLevel.ERROR,
            Console.MessageSource.NETWORK,
            "Error writing response body data for request #" + requestId);
      }
    }
    return availableInputStream;
  }

  @Override
  public void httpExchangeFailed(String requestId, String errorText) {
    loadingFailed(requestId, errorText);
  }

  @Override
  public void responseReadFinished(String requestId) {
    loadingFinished(requestId);
  }

  private void loadingFinished(String requestId) {
    NetworkPeerManager peerManager = getPeerManagerIfEnabled();
    if (peerManager != null) {
      Network.LoadingFinishedParams finishedParams = new Network.LoadingFinishedParams();
      finishedParams.requestId = requestId;
      finishedParams.timestamp = stethoNow() / 1000.0;
      if (dev.lumen.LumenAgent.isStarted()) {
        try {
          dev.lumen.store.NetworkRecord rec =
              dev.lumen.LumenAgent.requireStore().getNetwork().get(requestId);
          if (rec != null) {
            finishedParams.encodedDataLength = rec.getEncodedDataLength();
          }
        } catch (RuntimeException ignored) {
          // Agent torn down mid-event.
        }
      }
      peerManager.sendNotificationToPeers("Network.loadingFinished", finishedParams);
    }
  }

  @Override
  public void responseReadFailed(String requestId, String errorText) {
    loadingFailed(requestId, errorText);
  }

  private void loadingFailed(String requestId, String errorText) {
    NetworkPeerManager peerManager = getPeerManagerIfEnabled();
    if (peerManager != null) {
      Network.LoadingFailedParams failedParams = new Network.LoadingFailedParams();
      failedParams.requestId = requestId;
      failedParams.timestamp = stethoNow() / 1000.0;
      failedParams.errorText = errorText;
      failedParams.type = Page.ResourceType.OTHER;
      peerManager.sendNotificationToPeers("Network.loadingFailed", failedParams);
    }
  }

  @Override
  public void dataSent(
      String requestId,
      int dataLength,
      int encodedDataLength) {
    // The inspector protocol only gives us the dataReceived event, but we can happily combine
    // upstream and downstream data into this to visualize the real size of the request, not
    // strictly the size of the "content" as reported in the UI.
    dataReceived(requestId, dataLength, encodedDataLength);
  }

  @Override
  public void dataReceived(
      String requestId,
      int dataLength,
      int encodedDataLength) {
    NetworkPeerManager peerManager = getPeerManagerIfEnabled();
    if (peerManager != null) {
      Network.DataReceivedParams dataReceivedParams = new Network.DataReceivedParams();
      dataReceivedParams.requestId = requestId;
      dataReceivedParams.timestamp = stethoNow() / 1000.0;
      dataReceivedParams.dataLength = dataLength;
      dataReceivedParams.encodedDataLength = encodedDataLength;
      peerManager.sendNotificationToPeers("Network.dataReceived", dataReceivedParams);
    }
  }

  private static boolean isWebSocketRequest(InspectorRequest request) {
    String upgrade = request.firstHeaderValue("Upgrade");
    if (upgrade != null && upgrade.equalsIgnoreCase("websocket")) {
      return true;
    }
    String url = request.url();
    return url != null && (url.startsWith("ws://") || url.startsWith("wss://"));
  }

  @Override
  public String nextRequestId() {
    return String.valueOf(mNextRequestId.getAndIncrement());
  }

  @Nullable
  private String getContentType(InspectorHeaders headers) {
    // This may need to change in the future depending on how cumbersome header simulation
    // is for the various hooks we expose.
    return headers.firstHeaderValue("Content-Type");
  }

  @Override
  public void webSocketCreated(String requestId, String url) {
    NetworkPeerManager peerManager = getPeerManagerIfEnabled();
    if (peerManager != null) {
      Network.WebSocketCreatedParams params = new Network.WebSocketCreatedParams();
      params.requestId = requestId;
      params.url = url;
      // Chrome 151 webSocketCreated always replaces #requestsById with an
      // empty NetworkRequest. Emitting it twice on the same peer wipes Messages.
      for (JsonRpcPeer peer : peerManager.copyReceivingPeers()) {
        if (!peer.markWsCreated(requestId)) {
          continue;
        }
        try {
          peer.invokeMethod("Network.webSocketCreated", params, null);
        } catch (java.nio.channels.NotYetConnectedException e) {
          LogRedirector.e("NetworkEventReporter", "wsCreated deliver failed", e);
        }
      }
    }
  }

  @Override
  public void webSocketClosed(String requestId) {
    NetworkPeerManager peerManager = getPeerManagerIfEnabled();
    if (peerManager != null) {
      Network.WebSocketClosedParams params = new Network.WebSocketClosedParams();
      params.requestId = requestId;
      params.timestamp = stethoNow() / 1000.0;
      peerManager.sendNotificationToPeers("Network.webSocketClosed", params);
    }
  }

  @Override
  public void webSocketWillSendHandshakeRequest(InspectorWebSocketRequest request) {
    NetworkPeerManager peerManager = getPeerManagerIfEnabled();
    if (peerManager != null) {
      Network.WebSocketWillSendHandshakeRequestParams params =
          new Network.WebSocketWillSendHandshakeRequestParams();
      params.requestId = request.id();
      params.timestamp = stethoNow() / 1000.0;
      params.wallTime = System.currentTimeMillis() / 1000.0;
      Network.WebSocketRequest requestJSON = new Network.WebSocketRequest();
      requestJSON.headers = formatHeadersAsJSON(request);
      params.request = requestJSON;
      peerManager.sendNotificationToPeers("Network.webSocketWillSendHandshakeRequest", params);
    }
  }

  @Override
  public void webSocketHandshakeResponseReceived(InspectorWebSocketResponse response) {
    NetworkPeerManager peerManager = getPeerManagerIfEnabled();
    if (peerManager != null) {
      Network.WebSocketHandshakeResponseReceivedParams params =
          new Network.WebSocketHandshakeResponseReceivedParams();
      params.requestId = response.requestId();
      params.timestamp = stethoNow() / 1000.0;
      Network.WebSocketResponse responseJSON = new Network.WebSocketResponse();
      responseJSON.headers = formatHeadersAsJSON(response);
      responseJSON.headersText = null;
      if (response.requestHeaders() != null) {
        responseJSON.requestHeaders = formatHeadersAsJSON(response.requestHeaders());
        responseJSON.requestHeadersText = null;
      }
      responseJSON.status = response.statusCode();
      responseJSON.statusText = response.reasonPhrase();
      params.response = responseJSON;
      peerManager.sendNotificationToPeers("Network.webSocketHandshakeResponseReceived", params);
    }
  }

  @Override
  public void webSocketFrameSent(InspectorWebSocketFrame frame) {
    NetworkPeerManager peerManager = getPeerManagerIfEnabled();
    if (peerManager != null) {
      peerManager.sendNotificationToPeers("Network.webSocketFrameSent", jsonFrameEvent(frame));
    }
  }

  @Override
  public void webSocketFrameReceived(InspectorWebSocketFrame frame) {
    NetworkPeerManager peerManager = getPeerManagerIfEnabled();
    if (peerManager != null) {
      peerManager.sendNotificationToPeers(
          "Network.webSocketFrameReceived", jsonFrameEvent(frame));
    }
  }

  /**
   * Build the CDP payload by hand. ObjectMapper has dropped {@code opcode}/{@code timestamp}
   * on Kotlin DTOs before; Chrome then treats every frame as continuation of one message,
   * so Messages shows a single row whose Data keeps being overwritten (chat → {@code 2}).
   */
  private static JSONObject jsonFrameEvent(InspectorWebSocketFrame frame) {
    try {
      JSONObject inner = new JSONObject();
      inner.put("opcode", frame.opcode());
      inner.put("mask", frame.mask());
      inner.put("payloadData", frame.payloadData() != null ? frame.payloadData() : "");
      JSONObject params = new JSONObject();
      params.put("requestId", frame.requestId());
      params.put("timestamp", nextWebSocketTimestamp());
      params.put("response", inner);
      return params;
    } catch (JSONException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void webSocketFrameError(String requestId, String errorMessage) {
    NetworkPeerManager peerManager = getPeerManagerIfEnabled();
    if (peerManager != null) {
      Network.WebSocketFrameErrorParams params = new Network.WebSocketFrameErrorParams();
      params.requestId = requestId;
      params.timestamp = stethoNow() / 1000.0;
      params.errorMessage = errorMessage;
      peerManager.sendNotificationToPeers("Network.webSocketFrameError", params);
    }
  }

  private static JSONObject formatHeadersAsJSON(InspectorHeaders headers) {
    JSONObject json = new JSONObject();
    for (int i = 0; i < headers.headerCount(); i++) {
      String name = headers.headerName(i);
      String value = headers.headerValue(i);
      try {
        if (json.has(name)) {
          // Multiple headers are separated with a new line.
          json.put(name, json.getString(name) + "\n" + value);
        } else {
          json.put(name, value);
        }
      } catch (JSONException e) {
        throw new RuntimeException(e);
      }
    }
    return json;
  }

  @Nonnull
  private ResourceTypeHelper getResourceTypeHelper() {
    if (mResourceTypeHelper == null) {
      mResourceTypeHelper = new ResourceTypeHelper();
    }
    return mResourceTypeHelper;
  }

  private static long stethoNow() {
    return SystemClock.elapsedRealtime();
  }

  /** Unique even when two frames share a millisecond, so Chrome doesn't collapse them. */
  private static double nextWebSocketTimestamp() {
    return (stethoNow() / 1000.0) + (sWebSocketFrameSeq.incrementAndGet() * 0.000001);
  }
}
