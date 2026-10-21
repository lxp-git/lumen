/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector.protocol.module;

import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.util.SparseArray;

import dev.lumen.common.LogUtil;
import dev.lumen.common.Util;
import dev.lumen.inspector.database.SqliteDatabaseDriver;
import dev.lumen.inspector.helper.ChromePeerManager;
import dev.lumen.inspector.helper.PeersRegisteredListener;
import dev.lumen.inspector.jsonrpc.JsonRpcException;
import dev.lumen.inspector.jsonrpc.JsonRpcPeer;
import dev.lumen.inspector.jsonrpc.JsonRpcResult;
import dev.lumen.inspector.jsonrpc.protocol.JsonRpcError;
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain;
import dev.lumen.inspector.protocol.ChromeDevtoolsMethod;
import dev.lumen.json.ObjectMapper;
import dev.lumen.json.annotation.JsonProperty;

import org.json.JSONObject;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

public class Database implements ChromeDevtoolsDomain {
  /**
   * The protocol doesn't offer an efficient means of pagination or anything like that so
   * we'll just cap the result list to some arbitrarily large number that I think folks will
   * actually need in practice.
   * <p>
   * Note that when this limit is exceeded, a dummy row will be introduced that indicates
   * truncation occurred.
   */
  private static final int MAX_EXECUTE_RESULTS = 250;

  /**
   * Maximum length of a BLOB field before we stop trying to interpret it and just
   * return {@link #UNKNOWN_BLOB_LABEL}
   */
  private static final int MAX_BLOB_LENGTH = 512;

  /**
   * Label to use when a BLOB column cannot be converted to a string.
   */
  private static final String UNKNOWN_BLOB_LABEL = "{blob}";

  private final SqliteDatabaseDriver mDriver;
  private final ChromePeerManager mChromePeerManager;
  private final DatabasePeerRegistrationListener mPeerListener;
  private final ObjectMapper mObjectMapper;

  public Database(SqliteDatabaseDriver driver, String packageName) {
    mDriver = driver;
    mChromePeerManager = new ChromePeerManager();
    mPeerListener = new DatabasePeerRegistrationListener(driver, packageName);
    mChromePeerManager.setListener(mPeerListener);
    mObjectMapper = new ObjectMapper();
  }

  @ChromeDevtoolsMethod
  public void enable(JsonRpcPeer peer, JSONObject params) {
    mChromePeerManager.addPeer(peer);
  }

  @ChromeDevtoolsMethod
  public void disable(JsonRpcPeer peer, JSONObject params) {
    mChromePeerManager.removePeer(peer);
  }

  @ChromeDevtoolsMethod
  public JsonRpcResult getDatabaseTableNames(JsonRpcPeer peer, JSONObject params)
      throws JsonRpcException {
    GetDatabaseTableNamesRequest request = mObjectMapper.convertValue(params,
        GetDatabaseTableNamesRequest.class);

    File databaseFile = mPeerListener.getDatabaseFile(request.databaseId);

    try {
      GetDatabaseTableNamesResponse response = new GetDatabaseTableNamesResponse();
      response.tableNames = mDriver.getTableNames(databaseFile);
      return response;
    } catch (SQLiteException e) {
      throw new JsonRpcException(
          new JsonRpcError(
              JsonRpcError.ErrorCode.INVALID_REQUEST,
              e.toString(),
              null /* data */));
    }
  }

  @ChromeDevtoolsMethod
  public JsonRpcResult executeSQL(JsonRpcPeer peer, JSONObject params) {
    ExecuteSQLRequest request = mObjectMapper.convertValue(params,
        ExecuteSQLRequest.class);

    File databaseFile = mPeerListener.getDatabaseFile(request.databaseId);

    try {
      return mDriver.executeSQL(
          databaseFile,
          request.query,
          new SqliteDatabaseDriver.ExecuteResultHandler<ExecuteSQLResponse>() {
        @Override
        public ExecuteSQLResponse handleRawQuery() throws SQLiteException {
          ExecuteSQLResponse response = new ExecuteSQLResponse();
          // This is done because the inspector UI likes to delete rows if you give them no
          // name/value list
          response.columnNames = Collections.singletonList("success");
          response.values = Collections.singletonList("true");
          return response;
        }

        @Override
        public ExecuteSQLResponse handleSelect(Cursor result) throws SQLiteException {
          ExecuteSQLResponse response = new ExecuteSQLResponse();
          response.columnNames = Arrays.asList(result.getColumnNames());
          response.values = flattenRows(result, MAX_EXECUTE_RESULTS);
          return response;
        }

        @Override
        public ExecuteSQLResponse handleInsert(long insertedId) throws SQLiteException {
          ExecuteSQLResponse response = new ExecuteSQLResponse();
          response.columnNames = Collections.singletonList("ID of last inserted row");
          response.values = Collections.singletonList(String.valueOf(insertedId));
          return response;
        }

        @Override
        public ExecuteSQLResponse handleUpdateDelete(int count) throws SQLiteException {
          ExecuteSQLResponse response = new ExecuteSQLResponse();
          response.columnNames = Collections.singletonList("Modified rows");
          response.values = Collections.singletonList(String.valueOf(count));
          return response;
        }
      });
    } catch (RuntimeException e) {
      LogUtil.e(e, "Exception executing: %s", request.query);

      Error error = new Error();
      error.code = 0;
      error.message = e.getMessage();
      ExecuteSQLResponse response = new ExecuteSQLResponse();
      response.sqlError = error;
      return response;
    }
  }

  /**
   * Flatten all columns and all rows of a cursor to a single array.  The array cannot be
   * interpreted meaningfully without the number of columns.
   *
   * @param cursor
   * @param limit Maximum number of rows to process.
   * @return List of Java primitives matching the value type of each column, converted to
   *      strings.
   */
  private static ArrayList<String> flattenRows(Cursor cursor, int limit) {
    Util.throwIfNot(limit >= 0);
    ArrayList<String> flatList = new ArrayList<>();
    final int numColumns = cursor.getColumnCount();
    for (int row = 0; row < limit && cursor.moveToNext(); row++) {
      for (int column = 0; column < numColumns; column++) {
        switch (cursor.getType(column)) {
          case Cursor.FIELD_TYPE_NULL:
            flatList.add(null);
            break;
          case Cursor.FIELD_TYPE_INTEGER:
            flatList.add(String.valueOf(cursor.getLong(column)));
            break;
          case Cursor.FIELD_TYPE_FLOAT:
            flatList.add(String.valueOf(cursor.getDouble(column)));
            break;
          case Cursor.FIELD_TYPE_BLOB:
            flatList.add(blobToString(cursor.getBlob(column)));
            break;
          case Cursor.FIELD_TYPE_STRING:
          default:
            flatList.add(cursor.getString(column));
            break;
        }
      }
    }
    if (!cursor.isAfterLast()) {
      for (int column = 0; column < numColumns; column++) {
        flatList.add("{truncated}");
      }
    }
    return flatList;
  }

  private static String blobToString(byte[] blob) {
    if (blob.length <= MAX_BLOB_LENGTH) {
      if (fastIsAscii(blob)) {
        try {
          return new String(blob, "US-ASCII");
        } catch (UnsupportedEncodingException e) {
          // Fall through...
        }
      }
    }
    return UNKNOWN_BLOB_LABEL;
  }

  private static boolean fastIsAscii(byte[] blob) {
    for (byte b : blob) {
      if ((b & ~0x7f) != 0) {
        return false;
      }
    }
    return true;
  }

  @ThreadSafe
  private static class DatabasePeerRegistrationListener extends PeersRegisteredListener {
    private final SqliteDatabaseDriver mDriver;
    private final String mPackageName;

    @GuardedBy("this")
    private final SparseArray<File> mDatabaseFiles = new SparseArray<>();

    /** Keyed by path: every scan builds new File objects, so identity cannot dedupe them. */
    @GuardedBy("this")
    private final Map<String, Integer> mIdsByPath = new HashMap<>();

    @GuardedBy("this")
    private int mNextId = 1;

    private DatabasePeerRegistrationListener(SqliteDatabaseDriver driver, String packageName) {
      mDriver = driver;
      mPackageName = packageName;
    }

    public synchronized File getDatabaseFile(String databaseId) {
      return mDatabaseFiles.get(Integer.parseInt(databaseId));
    }

    /** Ids are stable per path; databases created since the last scan get new ones. */
    private void refreshDatabaseFiles() {
      for (File file : mDriver.getDatabaseFiles()) {
        String path = file.getAbsolutePath();
        if (!mIdsByPath.containsKey(path)) {
          int id = mNextId++;
          mIdsByPath.put(path, id);
          mDatabaseFiles.put(id, file);
        }
      }
    }

    @Override
    protected synchronized void onFirstPeerRegistered() {
      // onPeerAdded follows for this peer and scans.
    }

    @Override
    protected synchronized void onLastPeerUnregistered() {
      mIdsByPath.clear();
      mDatabaseFiles.clear();
    }

    @Override
    protected synchronized void onPeerAdded(JsonRpcPeer peer) {
      refreshDatabaseFiles();
      for (int i = 0, N = mDatabaseFiles.size(); i < N; i++) {
        Database.DatabaseObject databaseParams = new Database.DatabaseObject();
        databaseParams.id = String.valueOf(mDatabaseFiles.keyAt(i));
        databaseParams.name = mDatabaseFiles.valueAt(i).getName();
        databaseParams.domain = mPackageName;
        databaseParams.version = "N/A";
        Database.AddDatabaseEvent eventParams = new Database.AddDatabaseEvent();
        eventParams.database = databaseParams;
        peer.invokeMethod("Database.addDatabase", eventParams, null /* callback */);
      }
    }

    @Override
    protected synchronized void onPeerRemoved(JsonRpcPeer peer) {
      // Nothing to do on each peer removal...
    }
  }

  private static class GetDatabaseTableNamesRequest {
    @JsonProperty(required = true)
    public String databaseId;
  }

  private static class GetDatabaseTableNamesResponse implements JsonRpcResult {
    @JsonProperty(required = true)
    public List<String> tableNames;
  }

  public static class ExecuteSQLRequest {
    @JsonProperty(required = true)
    public String databaseId;

    @JsonProperty(required = true)
    public String query;
  }

  public static class ExecuteSQLResponse implements JsonRpcResult {
    @JsonProperty
    public List<String> columnNames;

    @JsonProperty
    public List<String> values;

    @JsonProperty
    public Error sqlError;
  }

  public static class AddDatabaseEvent {
    @JsonProperty(required = true)
    public DatabaseObject database;
  }

  public static class DatabaseObject {
    @JsonProperty(required = true)
    public String id;

    @JsonProperty(required = true)
    public String domain;

    @JsonProperty(required = true)
    public String name;

    @JsonProperty(required = true)
    public String version;
  }

  public static class Error {
    @JsonProperty(required = true)
    public String message;

    @JsonProperty(required = true)
    public int code;
  }
}
