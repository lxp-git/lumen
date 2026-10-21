/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector.network;

import java.io.IOException;

import javax.annotation.Nullable;

/**
 * Live push of network activity to attached DevTools peers. Recording into
 * {@link dev.lumen.store.EventStore} happens separately and regardless of peers; this interface
 * only mirrors it onto the wire while Chrome is watching. For HTTP the call flow is:
 *
 * <pre>
 * requestWillBeSent +---> responseHeadersReceived ---> dataReceived ---> responseReadFinished
 *                   |                                       |
 *                   |                                       `-------> responseReadFailed
 *                   `-------------------------------------------------> httpExchangeFailed
 * </pre>
 *
 * <p>For WebSockets (and Socket.IO polling promoted to a synthetic socket):</p>
 *
 * <pre>
 * webSocketCreated +---> webSocketWillSendHandshakeRequest ----> webSocketHandshakeResponseReceived
 *                  |                                                              |
 *                  |                     ,----------------------+-----------------+--------,
 *                  |                     v                      v                          |
 *                  +---------> [ webSocketFrameSent | webSocketFrameReceived ] ---,        |
 *                  |                     ^                      ^                 |        |
 *                  |                     `----------------------+-----------------+        |
 *                  |                                                              |        |
 *                  `---------> webSocketClosed <----------------------------------'        |
 *                                     ^                                                    |
 *                                     `----------------------------------------------------'
 * </pre>
 */
public interface NetworkEventReporter {
  /**
   * True once the agent is recording. Callers use it to skip building event payloads; every
   * method is still safe to call when it is false.
   */
  boolean isEnabled();

  /**
   * Indicates that a request is about to be sent, but has not yet been delivered over the wire.
   *
   * @param request Request descriptor.
   */
  void requestWillBeSent(InspectorRequest request);

  /**
   * Indicates that a response message was just received from the network, but the body
   * has not yet been read.
   *
   * @param response Response descriptor.
   */
  void responseHeadersReceived(InspectorResponse response);

  /**
   * Indicates that communication with the server failed before a response body was available.
   *
   * @param requestId Unique identifier for the request as per {@link InspectorRequest#id()}
   * @param errorText Text to report for the error; using {@link IOException#toString()} is
   *     recommended.
   */
  void httpExchangeFailed(String requestId, String errorText);

  /**
   * Indicates that reading the response body failed part way.
   *
   * @param requestId Unique identifier for the request as per {@link InspectorRequest#id()}
   * @param errorText Text to report for the error; using {@link IOException#toString()} is
   *     recommended.
   */
  void responseReadFailed(String requestId, String errorText);

  /**
   * Indicates that the response body was fully read (or closed by the app) and the request is
   * now complete.
   *
   * @param requestId Unique identifier for the request as per {@link InspectorRequest#id()}
   */
  void responseReadFinished(String requestId);

  /**
   * Indicates how many body bytes were received, for the Network panel size column.
   *
   * @param dataLength Decoded body length
   * @param encodedDataLength Length as received on the wire
   */
  void dataReceived(String requestId, int dataLength, int encodedDataLength);

  /**
   * Provides unique request id for {@link InspectorRequest#id()}.
   */
  String nextRequestId();

  /**
   * Invoked when a socket is created and implicitly being connected (but not necessarily connected
   * yet).  If a websocket is being used, proceed to {@link #webSocketWillSendHandshakeRequest}.
   * Otherwise you may proceed next to {@code webSocketFrame*} methods.
   */
  void webSocketCreated(String requestId, String url);

  /**
   * Socket has been closed for unknown reasons.  Consider first invoking
   * {@link #webSocketFrameError} even for standard sockets to provide context.
   */
  void webSocketClosed(String requestId);

  /**
   * Invoked specifically for websockets to communicate the WebSocket upgrade messages.  Not
   * necessary to call for standard sockets.
   */
  void webSocketWillSendHandshakeRequest(InspectorWebSocketRequest request);

  /**
   * Delivers the reply from the peer in response to the WebSocket upgrade request.
   */
  void webSocketHandshakeResponseReceived(InspectorWebSocketResponse response);

  /**
   * Send a "websocket" frame from our app to the remote peer.  Standard sockets can simply emulate
   * this by capturing each socket send as a frame of either
   * {@link InspectorWebSocketFrame#OPCODE_BINARY} or
   * {@link InspectorWebSocketFrame#OPCODE_TEXT}.  Note that binary
   * payloads are not visualized in Chrome but can be sent by simply assuming the data is UTF-8
   * encoded (yes, really:
   * https://chromium.googlesource.com/chromium/blink/+/master/Source/core/inspector/InspectorResourceAgent.cpp#850).
   */
  void webSocketFrameSent(InspectorWebSocketFrame frame);

  /**
   * The receive side of {@link #webSocketFrameSent}.
   */
  void webSocketFrameReceived(InspectorWebSocketFrame frame);

  /**
   * Indicates a web socket (or standard socket) error has occurred though this doesn't
   * explicitly close the socket (see {@link #webSocketClosed}) but it does let the UI
   * know that it should denote the closure as forceful or as a failure in some way.
   */
  void webSocketFrameError(String requestId, String errorMessage);

  /**
   * Represents the request that will be sent over HTTP.  Note that for many implementations
   * of HTTP the request constructed may differ from the request actually sent over the wire.
   * For instance, additional headers like {@code Host}, {@code User-Agent}, {@code Content-Type},
   * etc may not be part of this request but should be injected if necessary.  Some stacks offer
   * inspection of the raw request about to be sent to the server which is preferable.
   */
  interface InspectorRequest extends InspectorRequestCommon {
    /**
     * Provide an extra integer to decorate the {@link #friendlyName()}.  This shows up next to
     * it in the WebKit Inspector UI and can be used to indicate things like request priority.
     */
    @Nullable
    Integer friendlyNameExtra();

    String url();

    /**
     * HTTP method ("GET", "POST", "DELETE", etc).
     */
    String method();

    /**
     * Provide the body if part of an entity-enclosing request (like "POST" or "PUT").  May
     * return null otherwise.
     */
    @Nullable
    byte[] body() throws IOException;
  }

  interface InspectorResponse extends InspectorResponseCommon {
    String url();

    /**
     * True if the response was furnished on a re-used socket; false otherwise or if unknown.
     */
    boolean connectionReused();

    /**
     * Unique connection identifier representing the socket that was used to furnish the response.
     */
    int connectionId();

    /**
     * True if the response was furnished by disk cache; false otherwise or if unknown.
     */
    boolean fromDiskCache();
  }

  interface InspectorWebSocketRequest extends InspectorRequestCommon {
  }

  interface InspectorWebSocketResponse extends InspectorResponseCommon {
    /**
     * Optional and redundant set of headers from {@link InspectorWebSocketRequest} that are for
     * some mysterious reason are included here in the response by Chrome.
     */
    @Nullable
    InspectorHeaders requestHeaders();
  }

  interface InspectorWebSocketFrame {
    String requestId();

    int OPCODE_CONTINUATION = 0;
    int OPCODE_TEXT = 1;
    int OPCODE_BINARY = 2;
    int OPCODE_CONNECTION_CLOSE = 8;
    int OPCODE_PING = 9;
    int OPCODE_PONG = 10;

    int opcode();
    boolean mask();
    String payloadData();
  }

  interface InspectorRequestCommon extends InspectorHeaders {
    /**
     * Unique identifier for this request.  This identifier must be used in all other network
     * events corresponding to this request.  Identifiers may be re-used for HTTP requests  or
     * WebSockets that have exhuasted the state machine to its final closed/finished state.
     */
    String id();

    /**
     * Arbitrary debug-friendly name of the request.
     */
    String friendlyName();
  }

  interface InspectorResponseCommon extends InspectorHeaders {
    /** @see InspectorRequest#id() */
    String requestId();

    int statusCode();

    String reasonPhrase();
  }

  interface InspectorHeaders {
    int headerCount();
    String headerName(int index);
    String headerValue(int index);

    @Nullable
    String firstHeaderValue(String name);
  }
}
