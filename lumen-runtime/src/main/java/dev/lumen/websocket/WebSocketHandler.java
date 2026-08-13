/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.websocket;

import android.util.Base64;
import dev.lumen.common.Utf8Charset;
import dev.lumen.server.http.HttpHandler;
import dev.lumen.server.http.HttpStatus;
import dev.lumen.server.SocketLike;
import dev.lumen.server.http.LightHttpBody;
import dev.lumen.server.http.LightHttpMessage;
import dev.lumen.server.http.LightHttpRequest;
import dev.lumen.server.http.LightHttpResponse;
import dev.lumen.server.http.LightHttpServer;

import javax.annotation.Nullable;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Crazy kludge to support upgrading to the WebSocket protocol while still using the
 * {@link HttpHandler} harness.
 * <p>
 * The way this works is that we pump the request directly into our WebSocket implementation and
 * force write the response out to the connection without returning.  Then, we extract the
 * remaining buffered input stream bytes from the socket and stitch them together with the
 * raw sockets input stream and pass everything onto the WebSocket engine which blocks
 * until WebSocket orderly shutdown.
 */
public class WebSocketHandler implements HttpHandler {
  private static final String HEADER_UPGRADE = "Upgrade";
  private static final String HEADER_CONNECTION = "Connection";
  private static final String HEADER_SEC_WEBSOCKET_KEY = "Sec-WebSocket-Key";
  private static final String HEADER_SEC_WEBSOCKET_ACCEPT = "Sec-WebSocket-Accept";
  private static final String HEADER_SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";
  private static final String HEADER_SEC_WEBSOCKET_VERSION = "Sec-WebSocket-Version";

  private static final String HEADER_UPGRADE_WEBSOCKET = "websocket";
  private static final String HEADER_CONNECTION_UPGRADE = "Upgrade";
  private static final String HEADER_SEC_WEBSOCKET_VERSION_13 = "13";

  // Are you kidding me?  The WebSocket spec requires that we append this weird hardcoded String
  // to the key we receive from the client, SHA-1 that, and base64 encode it back to the client.
  // I'm guessing this is to prevent replay attacks of some kind but given that there's no actual
  // security context here, I can only imagine that this is just security through obscurity in
  // some fashion.
  private static final String SERVER_KEY_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

  private final SimpleEndpoint mEndpoint;

  public WebSocketHandler(SimpleEndpoint endpoint) {
    mEndpoint = endpoint;
  }

  @Override
  public boolean handleRequest(
      SocketLike socket,
      LightHttpRequest request,
      LightHttpResponse response) throws IOException {
    if (!isSupportableUpgradeRequest(request)) {
      response.code = HttpStatus.HTTP_NOT_IMPLEMENTED;
      response.reasonPhrase = "Not Implemented";
      response.body = LightHttpBody.create(
          "Not a supported WebSocket upgrade request\n",
          "text/plain");
      return true;
    }

    // This will not return on successful WebSocket upgrade, but rather block until the session is
    // shut down or a socket error occurs.
    String path = request.uri != null ? request.uri.getPath() : "";
    UpgradeContext.setPath(path);
    try {
      doUpgrade(socket, request, response);
    } finally {
      UpgradeContext.setPath(null);
    }
    return false;
  }

  private static boolean isSupportableUpgradeRequest(LightHttpRequest request) {
    // Chrome sends things like "Connection: keep-alive, Upgrade" (comma-separated,
    // any case). RFC 6455 only requires that the upgrade token be present — matching
    // the header with equals() rejects every modern Chrome inspect handshake.
    return HEADER_UPGRADE_WEBSOCKET.equalsIgnoreCase(getFirstHeaderValue(request, HEADER_UPGRADE)) &&
        headerContainsToken(getFirstHeaderValue(request, HEADER_CONNECTION), HEADER_CONNECTION_UPGRADE) &&
        HEADER_SEC_WEBSOCKET_VERSION_13.equals(
            getFirstHeaderValue(request, HEADER_SEC_WEBSOCKET_VERSION));
  }

  /** True if a comma-separated header list contains {@code token} (case-insensitive). */
  private static boolean headerContainsToken(@Nullable String headerValue, String token) {
    if (headerValue == null) {
      return false;
    }
    for (String part : headerValue.split(",")) {
      if (token.equalsIgnoreCase(part.trim())) {
        return true;
      }
    }
    return false;
  }

  private void doUpgrade(
      SocketLike socketLike,
      LightHttpRequest request,
      LightHttpResponse response)
      throws IOException {
    response.code = HttpStatus.HTTP_SWITCHING_PROTOCOLS;
    response.reasonPhrase = "Switching Protocols";
    response.addHeader(HEADER_UPGRADE, HEADER_UPGRADE_WEBSOCKET);
    response.addHeader(HEADER_CONNECTION, HEADER_CONNECTION_UPGRADE);
    response.body = null;

    String clientKey = getFirstHeaderValue(request, HEADER_SEC_WEBSOCKET_KEY);
    if (clientKey != null) {
      response.addHeader(HEADER_SEC_WEBSOCKET_ACCEPT, generateServerKey(clientKey));
    }

    InputStream in = socketLike.getInput();
    OutputStream out = socketLike.getOutput();
    LightHttpServer.writeResponseMessage(
        response,
        new LightHttpServer.HttpMessageWriter(new BufferedOutputStream(out)));

    WebSocketSession session = new WebSocketSession(in, out, mEndpoint);
    session.handle();
  }

  private static String generateServerKey(String clientKey) {
    try {
      String serverKey = clientKey + SERVER_KEY_GUID;
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      sha1.update(Utf8Charset.encodeUTF8(serverKey));
      return Base64.encodeToString(sha1.digest(), Base64.NO_WRAP);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  @Nullable
  private static String getFirstHeaderValue(LightHttpMessage message, String headerName) {
    return message.getFirstHeaderValue(headerName);
  }
}
