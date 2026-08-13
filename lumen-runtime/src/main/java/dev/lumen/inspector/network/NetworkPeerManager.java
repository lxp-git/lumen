/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector.network;

import javax.annotation.Nullable;

import android.content.Context;
import dev.lumen.common.Util;
import dev.lumen.inspector.helper.ChromePeerManager;
import dev.lumen.inspector.helper.PeersRegisteredListener;

public class NetworkPeerManager extends ChromePeerManager {
  private static NetworkPeerManager sInstance;

  private final ResponseBodyFileManager mResponseBodyFileManager;
  private AsyncPrettyPrinterInitializer mPrettyPrinterInitializer;
  private AsyncPrettyPrinterRegistry mAsyncPrettyPrinterRegistry;

  @Nullable
  public static synchronized NetworkPeerManager getInstanceOrNull() {
    return sInstance;
  }

  public static synchronized NetworkPeerManager getOrCreateInstance(Context context) {
    if (sInstance == null) {
      sInstance = new NetworkPeerManager(
          new ResponseBodyFileManager(
              context.getApplicationContext()));
    }
    return sInstance;
  }

  public NetworkPeerManager(
      ResponseBodyFileManager responseBodyFileManager) {
    mResponseBodyFileManager = responseBodyFileManager;
    setListener(mTempFileCleanup);
  }

  public ResponseBodyFileManager getResponseBodyFileManager() {
    return mResponseBodyFileManager;
  }

  @Nullable
  public AsyncPrettyPrinterRegistry getAsyncPrettyPrinterRegistry() {
    return mAsyncPrettyPrinterRegistry;
  }

  public void setPrettyPrinterInitializer(AsyncPrettyPrinterInitializer initializer) {
    Util.throwIfNotNull(mPrettyPrinterInitializer);
    mPrettyPrinterInitializer = Util.throwIfNull(initializer);
  }

  private final PeersRegisteredListener mTempFileCleanup = new PeersRegisteredListener() {
    @Override
    protected void onFirstPeerRegistered() {
      AsyncPrettyPrinterExecutorHolder.ensureInitialized();
      if (mAsyncPrettyPrinterRegistry == null && mPrettyPrinterInitializer != null) {
        mAsyncPrettyPrinterRegistry = new AsyncPrettyPrinterRegistry();
        mPrettyPrinterInitializer.populatePrettyPrinters(mAsyncPrettyPrinterRegistry);
      }
      // Lumen keeps bodies in EventStore across connect/disconnect — do not wipe.
    }

    @Override
    protected void onLastPeerUnregistered() {
      // Keep archive; only shut down pretty-printer pool.
      AsyncPrettyPrinterExecutorHolder.shutdown();
    }
  };
}
