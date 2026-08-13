/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector;

import android.content.Context;
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain;
import dev.lumen.server.SocketLike;
import dev.lumen.server.SocketLikeHandler;
import dev.lumen.server.http.ExactPathMatcher;
import dev.lumen.server.http.HandlerRegistry;
import dev.lumen.server.http.LightHttpServer;
import dev.lumen.server.http.RegexpPathMatcher;
import dev.lumen.websocket.SimpleEndpoint;
import dev.lumen.websocket.WebSocketHandler;

import java.io.IOException;
import java.util.regex.Pattern;

public class DevtoolsSocketHandler implements SocketLikeHandler {
  private final Context mContext;
  private final LightHttpServer mServer;

  public DevtoolsSocketHandler(Context context, Iterable<ChromeDevtoolsDomain> modules) {
    this(context, new ChromeDevtoolsServer(modules));
  }

  public DevtoolsSocketHandler(Context context, SimpleEndpoint endpoint) {
    mContext = context;
    mServer = createServer(endpoint);
  }

  private LightHttpServer createServer(SimpleEndpoint endpoint) {
    HandlerRegistry registry = new HandlerRegistry();
    ChromeDiscoveryHandler discoveryHandler =
        new ChromeDiscoveryHandler(
            mContext,
            ChromeDevtoolsServer.PATH);
    discoveryHandler.register(registry);
    // Chrome inspect reconnect (especially when /json/version says Chrome/…)
    // HttpUpgrades /devtools/browser. First-open uses /devtools/page/1.
    WebSocketHandler webSocketHandler = new WebSocketHandler(endpoint);
    registry.register(new ExactPathMatcher(ChromeDevtoolsServer.PATH), webSocketHandler);
    registry.register(new ExactPathMatcher("/devtools/browser"), webSocketHandler);
    registry.register(
        new RegexpPathMatcher(Pattern.compile("/devtools/page/.*")),
        webSocketHandler);
    registry.register(
        new RegexpPathMatcher(Pattern.compile("/devtools/browser.*")),
        webSocketHandler);

    return new LightHttpServer(registry);
  }

  @Override
  public void onAccepted(SocketLike socket) throws IOException {
    mServer.serve(socket);
  }
}
