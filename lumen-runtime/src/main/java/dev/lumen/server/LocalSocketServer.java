/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.server;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;
import dev.lumen.common.LogUtil;
import dev.lumen.common.Util;

import javax.annotation.Nonnull;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.BindException;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicInteger;

public class LocalSocketServer {
  private static final String WORKER_THREAD_NAME_PREFIX = "StethoWorker";
  private static final int MAX_BIND_RETRIES = 2;
  private static final int TIME_BETWEEN_BIND_RETRIES_MS = 1000;

  private final String mFriendlyName;
  private final String mAddress;
  private final SocketHandler mSocketHandler;
  private final AtomicInteger mThreadId = new AtomicInteger();

  private Thread mListenerThread;
  private boolean mStopped;
  private volatile LocalServerSocket mServerSocket;

  /**
   * @param friendlyName identifier to help debug this server, used for naming threads and such.
   * @param address the local socket address to listen on.
   * @param socketHandler functional handler once a socket is accepted.
   */
  public LocalSocketServer(
      String friendlyName,
      String address,
      SocketHandler socketHandler) {
    mFriendlyName = Util.throwIfNull(friendlyName);
    mAddress = Util.throwIfNull(address);
    mSocketHandler = socketHandler;
  }

  public String getName() {
    return mFriendlyName;
  }

  /**
   * Binds to the address and listens for connections.
   * <p/>
   * If successful, this thread blocks forever or until {@link #stop} is called, whichever
   * happens first.
   *
   * @throws IOException Thrown on failure to bind the socket.
   */
  public void run() throws IOException {
    synchronized (this) {
      if (mStopped) {
        return;
      }
      mListenerThread = Thread.currentThread();
    }

    listenOnAddress(mAddress);
  }

  /**
   * Bind now and accept on a daemon thread. Chrome's ADB HttpUpgrade is 1s;
   * the abstract socket must exist before EventStore / CDP modules finish.
   */
  public void bindAndStartAccepting() throws IOException {
    bindAndStartAccepting(true);
  }

  public void bindAndStartAccepting(boolean retry) throws IOException {
    synchronized (this) {
      if (mStopped) {
        return;
      }
    }
    mServerSocket = retry ? bindToSocket(mAddress) : new LocalServerSocket(mAddress);
    Thread acceptor = new Thread(
        () -> acceptLoop(mAddress),
        "StethoListener-" + mFriendlyName);
    acceptor.setDaemon(true);
    synchronized (this) {
      mListenerThread = acceptor;
    }
    acceptor.start();
  }

  private void listenOnAddress(String address) throws IOException {
    mServerSocket = bindToSocket(address);
    acceptLoop(address);
  }

  private void acceptLoop(String address) {
    LogUtil.i("Listening on @" + address);

    while (!Thread.interrupted()) {
      try {
        // Use previously accepted socket the first time around, otherwise wait to
        // accept another.
        LocalSocket socket = mServerSocket.accept();

        // Start worker thread
        Thread t = new WorkerThread(socket, mSocketHandler);
        t.setName(
            WORKER_THREAD_NAME_PREFIX +
            "-" + mFriendlyName +
            "-" + mThreadId.incrementAndGet());
        t.setDaemon(true);
        t.start();
      } catch (SocketException se) {
        if (mStopped || Thread.interrupted()) {
          break;
        }
        LogUtil.w(se, "I/O error");
      } catch (InterruptedIOException ex) {
        break;
      } catch (IOException e) {
        LogUtil.w(e, "I/O error initialising connection thread");
        break;
      }
    }

    LogUtil.i("Server shutdown on @" + address);
  }

  /**
   * Stops the listener thread and unbinds the address.
   */
  public void stop() {
    Thread listener;
    synchronized (this) {
      mStopped = true;
      listener = mListenerThread;
    }
    try {
      if (mServerSocket != null) {
        mServerSocket.close();
      }
    } catch (IOException e) {
      // Don't care...
    }
    if (listener != null) {
      listener.interrupt();
    }
  }

  @Nonnull
  private static LocalServerSocket bindToSocket(String address) throws IOException {
    int retries = MAX_BIND_RETRIES;
    IOException firstException = null;
    do {
      try {
        if (LogUtil.isLoggable(Log.DEBUG)) {
          LogUtil.d("Trying to bind to @" + address);
        }
        return new LocalServerSocket(address);
      } catch (BindException be) {
        LogUtil.w(be, "Binding error, sleep " + TIME_BETWEEN_BIND_RETRIES_MS + " ms...");
        if (firstException == null) {
          firstException = be;
        }
        Util.sleepUninterruptibly(TIME_BETWEEN_BIND_RETRIES_MS);
      }
    } while (retries-- > 0);

    throw firstException;
  }

  private static class WorkerThread extends Thread {
    private final LocalSocket mSocket;
    private final SocketHandler mSocketHandler;

    public WorkerThread(LocalSocket socket, SocketHandler socketHandler) {
      mSocket = socket;
      mSocketHandler = socketHandler;
    }

    @Override
    public void run() {
      try {
        mSocketHandler.onAccepted(mSocket);
      } catch (IOException ex) {
        LogUtil.w("I/O error: %s", ex);
      } finally {
        try {
          mSocket.close();
        } catch (IOException ignore) {
        }
      }
    }
  }
}
