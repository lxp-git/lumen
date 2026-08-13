/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector.screencast;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import dev.lumen.common.LogUtil;
import dev.lumen.inspector.elements.android.ActivityTracker;
import dev.lumen.inspector.jsonrpc.JsonRpcPeer;
import dev.lumen.inspector.protocol.module.Page;

import java.io.ByteArrayOutputStream;

/**
 * DevTools device preview. Must never crash the host: Compose / ImageView
 * hardware bitmaps throw if drawn onto a software {@link Canvas}.
 */
public final class ScreencastDispatcher {
  private static final long FRAME_DELAY = 200L;

  private final Handler mMainHandler = new Handler(Looper.getMainLooper());
  private final BitmapFetchRunnable mBitmapFetchRunnable = new BitmapFetchRunnable();
  private final ActivityTracker mActivityTracker = ActivityTracker.get();
  private final EventDispatchRunnable mEventDispatchRunnable = new EventDispatchRunnable();
  private final RectF mTempSrc = new RectF();
  private final RectF mTempDst = new RectF();

  private boolean mIsRunning;
  private Handler mBackgroundHandler;
  private JsonRpcPeer mPeer;
  private HandlerThread mHandlerThread;
  private Bitmap mBitmap;
  private Canvas mCanvas;
  private Page.StartScreencastRequest mRequest;
  private ByteArrayOutputStream mStream;
  private Page.ScreencastFrameEvent mEvent = new Page.ScreencastFrameEvent();
  private Page.ScreencastFrameEventMetadata mMetadata = new Page.ScreencastFrameEventMetadata();

  public void startScreencast(JsonRpcPeer peer, Page.StartScreencastRequest request) {
    LogUtil.d("Starting screencast");
    mRequest = request;
    mHandlerThread = new HandlerThread("Screencast Thread");
    mHandlerThread.start();
    mPeer = peer;
    mIsRunning = true;
    mStream = new ByteArrayOutputStream();
    mBackgroundHandler = new Handler(mHandlerThread.getLooper());
    mMainHandler.postDelayed(mBitmapFetchRunnable, FRAME_DELAY);
  }

  public void stopScreencast() {
    LogUtil.d("Stopping screencast");
    if (mBackgroundHandler != null) {
      mBackgroundHandler.post(new CancellationRunnable());
    }
  }

  private boolean ensureBitmap(int viewWidth, int viewHeight) {
    if (viewWidth <= 0 || viewHeight <= 0 || mRequest == null) {
      return false;
    }
    if (mRequest.maxWidth <= 0 || mRequest.maxHeight <= 0) {
      return false;
    }
    if (mBitmap != null) {
      return true;
    }
    float scale = Math.min(
        (float) mRequest.maxWidth / (float) viewWidth,
        (float) mRequest.maxHeight / (float) viewHeight);
    int destWidth = Math.max(1, (int) (viewWidth * scale));
    int destHeight = Math.max(1, (int) (viewHeight * scale));
    mBitmap = Bitmap.createBitmap(destWidth, destHeight, Bitmap.Config.ARGB_8888);
    mCanvas = new Canvas(mBitmap);
    Matrix matrix = new Matrix();
    mTempSrc.set(0, 0, viewWidth, viewHeight);
    mTempDst.set(0, 0, destWidth, destHeight);
    matrix.setRectToRect(mTempSrc, mTempDst, Matrix.ScaleToFit.CENTER);
    mCanvas.setMatrix(matrix);
    return true;
  }

  private class BitmapFetchRunnable implements Runnable {
    @Override
    public void run() {
      captureFrame();
    }

    private void captureFrame() {
      if (!mIsRunning) {
        return;
      }
      Activity activity = mActivityTracker.tryGetTopActivity();
      if (activity == null || activity.isFinishing()) {
        mMainHandler.postDelayed(this, FRAME_DELAY);
        return;
      }
      Window window = activity.getWindow();
      if (window == null) {
        mMainHandler.postDelayed(this, FRAME_DELAY);
        return;
      }
      View rootView = window.getDecorView();
      int viewWidth = rootView.getWidth();
      int viewHeight = rootView.getHeight();
      try {
        if (!ensureBitmap(viewWidth, viewHeight)) {
          mMainHandler.postDelayed(this, FRAME_DELAY);
          return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
          PixelCopy.request(
              window,
              mBitmap,
              new PixelCopy.OnPixelCopyFinishedListener() {
                @Override
                public void onPixelCopyFinished(int result) {
                  if (!mIsRunning) {
                    return;
                  }
                  if (result == PixelCopy.SUCCESS && mBackgroundHandler != null) {
                    mBackgroundHandler.post(mEventDispatchRunnable.withEndAction(BitmapFetchRunnable.this));
                  } else {
                    mMainHandler.postDelayed(BitmapFetchRunnable.this, FRAME_DELAY);
                  }
                }
              },
              mMainHandler);
          return;
        }
        // API 24–25: software draw. Compose hardware bitmaps throw — never crash the app.
        rootView.draw(mCanvas);
        if (mBackgroundHandler != null) {
          mBackgroundHandler.post(mEventDispatchRunnable.withEndAction(this));
        }
      } catch (Throwable t) {
        LogUtil.w(t, "Screencast frame skipped");
        mMainHandler.postDelayed(this, FRAME_DELAY);
      }
    }
  }

  private class EventDispatchRunnable implements Runnable {
    private Runnable mEndAction;

    private EventDispatchRunnable withEndAction(Runnable endAction) {
      mEndAction = endAction;
      return this;
    }

    @Override
    public void run() {
      if (!mIsRunning || mBitmap == null || mRequest == null || mPeer == null) {
        return;
      }
      try {
        int width = mBitmap.getWidth();
        int height = mBitmap.getHeight();
        mStream.reset();
        Base64OutputStream base64Stream = new Base64OutputStream(mStream, Base64.DEFAULT);
        String rawFormat = mRequest.format != null ? mRequest.format : "jpeg";
        Bitmap.CompressFormat format;
        try {
          format = Bitmap.CompressFormat.valueOf(rawFormat.toUpperCase());
        } catch (IllegalArgumentException e) {
          format = Bitmap.CompressFormat.JPEG;
        }
        int quality = mRequest.quality;
        if (quality < 0 || quality > 100) {
          quality = 60;
        }
        mBitmap.compress(format, quality, base64Stream);
        mEvent.data = mStream.toString();
        mMetadata.pageScaleFactor = 1;
        mMetadata.deviceWidth = width;
        mMetadata.deviceHeight = height;
        mEvent.metadata = mMetadata;
        mPeer.invokeMethod("Page.screencastFrame", mEvent, null);
      } catch (Throwable t) {
        LogUtil.w(t, "Screencast encode skipped");
      }
      mMainHandler.postDelayed(mEndAction, FRAME_DELAY);
    }
  }

  private class CancellationRunnable implements Runnable {
    @Override
    public void run() {
      mIsRunning = false;
      mMainHandler.removeCallbacks(mBitmapFetchRunnable);
      if (mBackgroundHandler != null) {
        mBackgroundHandler.removeCallbacks(mEventDispatchRunnable);
      }
      if (mHandlerThread != null) {
        mHandlerThread.quitSafely();
      }
      mHandlerThread = null;
      mBackgroundHandler = null;
      mBitmap = null;
      mCanvas = null;
      mStream = null;
    }
  }
}
