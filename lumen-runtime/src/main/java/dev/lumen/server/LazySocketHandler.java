/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.server;

import android.net.LocalSocket;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.IOException;

/**
 * Optimization designed to allow us to lazily construct/configure the true Lumen server
 * only after the first caller connects.  This gives us much more wiggle room to have performance
 * impact in the set up path that only applies when Lumen is _used_, not simply enabled.
 */
public class LazySocketHandler implements SocketHandler {
  private final SocketHandlerFactory mSocketHandlerFactory;

  @Nullable
  private SocketHandler mSocketHandler;

  public LazySocketHandler(SocketHandlerFactory socketHandlerFactory) {
    mSocketHandlerFactory = socketHandlerFactory;
  }

  @Override
  public void onAccepted(LocalSocket socket) throws IOException {
    getSocketHandler().onAccepted(socket);
  }

  /** Build the real handler now so Chrome's 1s ADB HttpUpgrade is not the first hit. */
  public void warm() {
    getSocketHandler();
  }

  @Nonnull
  private synchronized SocketHandler getSocketHandler() {
    if (mSocketHandler == null) {
      mSocketHandler = mSocketHandlerFactory.create();
    }
    return mSocketHandler;
  }
}
