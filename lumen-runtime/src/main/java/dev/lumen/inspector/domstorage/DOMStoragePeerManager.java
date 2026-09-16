/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector.domstorage;

import android.content.Context;
import android.content.SharedPreferences;
import dev.lumen.common.LogUtil;
import dev.lumen.inspector.console.CLog;
import dev.lumen.inspector.helper.ChromePeerManager;
import dev.lumen.inspector.helper.PeerRegistrationListener;
import dev.lumen.inspector.helper.PeersRegisteredListener;
import dev.lumen.inspector.kv.KvCatalog;
import dev.lumen.inspector.kv.KvEntry;
import dev.lumen.inspector.kv.KvIds;
import dev.lumen.inspector.protocol.module.Console;
import dev.lumen.inspector.protocol.module.DOMStorage;
import dev.lumen.inspector.protocol.module.Storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DOMStoragePeerManager extends ChromePeerManager {
  private static final long KV_POLL_MS = 1000L;

  private final Context mContext;
  private volatile boolean mKvPoll;
  private volatile int mKvGeneration;
  private Thread mKvPoller;

  public DOMStoragePeerManager(Context context) {
    mContext = context;
    setListener(mPeerListener);
  }

  public void signalItemRemoved(DOMStorage.StorageId storageId, String key) {
    DOMStorage.DomStorageItemRemovedParams params =
        new DOMStorage.DomStorageItemRemovedParams();
    params.storageId = storageId;
    params.key = key;
    sendNotificationToPeers("DOMStorage.domStorageItemRemoved", params);
  }

  public void signalItemAdded(DOMStorage.StorageId storageId, String key, String value) {
    DOMStorage.DomStorageItemAddedParams params =
        new DOMStorage.DomStorageItemAddedParams();
    params.storageId = storageId;
    params.key = key;
    params.newValue = value;
    sendNotificationToPeers("DOMStorage.domStorageItemAdded", params);
  }

  public void signalItemUpdated(
      DOMStorage.StorageId storageId,
      String key,
      String oldValue,
      String newValue) {
    DOMStorage.DomStorageItemUpdatedParams params =
        new DOMStorage.DomStorageItemUpdatedParams();
    params.storageId = storageId;
    params.key = key;
    params.oldValue = oldValue;
    params.newValue = newValue;
    sendNotificationToPeers("DOMStorage.domStorageItemUpdated", params);
  }

  private final PeerRegistrationListener mPeerListener = new PeersRegisteredListener() {
    private final List<DevToolsSharedPreferencesListener> mPrefsListeners =
        new ArrayList<DevToolsSharedPreferencesListener>();

    @Override
    protected synchronized void onFirstPeerRegistered() {
      // TODO: We list the tags in Page.getResourceTree as well and those are the real fixed
      // tags that will be observed by the peer.  We can fix this by making the page frames
      // dynamically update in response to DOMStorage events.  This would also allow us to
      // add new SharedPreferences tags as we observe them being created by way of
      // android.os.FileObserver.
      List<String> tags = SharedPreferencesHelper.getSharedPreferenceTags(mContext);
      for (String tag : tags) {
        SharedPreferences prefs = mContext.getSharedPreferences(tag, Context.MODE_PRIVATE);
        DevToolsSharedPreferencesListener listener =
            new DevToolsSharedPreferencesListener(prefs, tag);
        prefs.registerOnSharedPreferenceChangeListener(listener);
        mPrefsListeners.add(listener);
      }
      startKvPoller();
    }

    @Override
    protected synchronized void onLastPeerUnregistered() {
      stopKvPoller();
      for (DevToolsSharedPreferencesListener prefsListener : mPrefsListeners) {
        prefsListener.unregister();
      }
      mPrefsListeners.clear();
    }
  };

  private void startKvPoller() {
    mKvPoll = true;
    final int generation = ++mKvGeneration;
    Thread t = new Thread(() -> pollDataStoreAndMmkv(generation), "lumen-kv-poll");
    mKvPoller = t;
    t.setDaemon(true);
    t.start();
  }

  private void stopKvPoller() {
    mKvPoll = false;
    mKvGeneration++;
    Thread t = mKvPoller;
    mKvPoller = null;
    if (t != null) {
      t.interrupt();
    }
  }

  private void pollDataStoreAndMmkv(int generation) {
    Map<String, String> prev = snapshotDataStoreAndMmkv();
    while (mKvPoll && generation == mKvGeneration) {
      try {
        Thread.sleep(KV_POLL_MS);
      } catch (InterruptedException e) {
        return;
      }
      if (!mKvPoll || generation != mKvGeneration) {
        return;
      }
      Map<String, String> next = snapshotDataStoreAndMmkv();
      emitKvDiff(prev, next);
      prev = next;
    }
  }

  private Map<String, String> snapshotDataStoreAndMmkv() {
    KvCatalog catalog = new KvCatalog(mContext);
    Map<String, String> out = new LinkedHashMap<String, String>();
    for (KvIds.StoreRef store : catalog.listStores()) {
      if (store.getKind() == KvIds.Kind.SHARED_PREFERENCES) {
        continue;
      }
      for (KvEntry entry : catalog.entries(store)) {
        out.put(
            KvIds.combinedKey(store.getKind(), store.getName(), entry.getKey()),
            entry.getDisplayValue());
      }
    }
    return out;
  }

  private void emitKvDiff(Map<String, String> prev, Map<String, String> next) {
    DOMStorage.StorageId storageId = new DOMStorage.StorageId();
    storageId.storageKey = Storage.DEFAULT_STORAGE_KEY;
    storageId.isLocalStorage = true;
    for (Map.Entry<String, String> e : prev.entrySet()) {
      if (!next.containsKey(e.getKey())) {
        signalItemRemoved(storageId, e.getKey());
      }
    }
    for (Map.Entry<String, String> e : next.entrySet()) {
      String oldValue = prev.get(e.getKey());
      if (oldValue == null) {
        signalItemAdded(storageId, e.getKey(), e.getValue());
      } else if (!oldValue.equals(e.getValue())) {
        signalItemUpdated(storageId, e.getKey(), oldValue, e.getValue());
      }
    }
  }

  private class DevToolsSharedPreferencesListener
      implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final SharedPreferences mPrefs;
    private final String mTag;
    private final DOMStorage.StorageId mStorageId;

    /**
     * Maintains a copy of the prefs data structure so that we can invoke
     * {@code DOMStorage.domStorageItemUpdated}.  This method requires that we know the old
     * value to perform updates.  Using {@code domStorageItemRemoved}/{@code Added} causes a UI
     * glitch where the item is moved to the end of the list, unfortunately.
     */
    private final Map<String, Object> mCopy;

    public DevToolsSharedPreferencesListener(SharedPreferences prefs, String tag) {
      mPrefs = prefs;
      mTag = tag;
      mStorageId = new DOMStorage.StorageId();
      // storageKey = lumen-default so events match getDOMStorageItems.
      mStorageId.storageKey = Storage.DEFAULT_STORAGE_KEY;
      mStorageId.isLocalStorage = true;
      mCopy = prefsCopy(prefs.getAll());
    }

    public void unregister() {
      mPrefs.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
      if (key == null) {
        LogUtil.i("Detected SharedPreferences clear of %s", mTag);
        ArrayList<String> gone = new ArrayList<String>(mCopy.keySet());
        for (String k : gone) {
          signalItemRemoved(
              mStorageId,
              KvIds.combinedKey(KvIds.Kind.SHARED_PREFERENCES, mTag, k));
        }
        mCopy.clear();
        return;
      }
      Map<String, ?> entries = sharedPreferences.getAll();
      boolean existedBefore = mCopy.containsKey(key);
      boolean existsNow = entries.containsKey(key);
      Object newValue = existsNow ? entries.get(key) : null;
      String displayKey = KvIds.combinedKey(KvIds.Kind.SHARED_PREFERENCES, mTag, key);
      if (existedBefore && existsNow) {
        signalItemUpdated(
            mStorageId,
            displayKey,
            SharedPreferencesHelper.valueToString(mCopy.get(key)),
            SharedPreferencesHelper.valueToString(newValue));
        mCopy.put(key, newValue);
      } else if (existedBefore) {
        signalItemRemoved(mStorageId, displayKey);
        mCopy.remove(key);
      } else if (existsNow) {
        signalItemAdded(
            mStorageId,
            displayKey,
            SharedPreferencesHelper.valueToString(newValue));
        mCopy.put(key, newValue);
      } else {
        // This can happen due to the async nature of the onSharedPreferenceChanged callback.  A
        // rapid put/remove as two separate commits on a background thread would cause this.
        LogUtil.i("Detected rapid put/remove of %s", key);
      }
    }
  }

  private static Map<String, Object> prefsCopy(Map<String, ?> src) {
    HashMap<String, Object> dst = new HashMap<String, Object>(src.size());
    for (Map.Entry<String, ?> entry : src.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      if (value instanceof Set) {
        dst.put(key, shallowCopy((Set<String>)value));
      } else {
        dst.put(key, value);
      }
    }
    return dst;
  }

  private static <T> Set<T> shallowCopy(Set<T> src) {
    HashSet<T> dst = new HashSet<T>();
    for (T item : src) {
      dst.add(item);
    }
    return dst;
  }
}
