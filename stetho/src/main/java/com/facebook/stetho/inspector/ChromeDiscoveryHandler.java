/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.stetho.inspector;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import com.facebook.stetho.common.ProcessUtil;
import com.facebook.stetho.server.http.ExactPathMatcher;
import com.facebook.stetho.server.http.HandlerRegistry;
import com.facebook.stetho.server.http.HttpHandler;
import com.facebook.stetho.server.http.HttpStatus;
import com.facebook.stetho.server.SocketLike;
import com.facebook.stetho.server.http.LightHttpBody;
import com.facebook.stetho.server.http.LightHttpRequest;
import com.facebook.stetho.server.http.LightHttpResponse;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.annotation.Nullable;

/**
 * Provides sufficient responses to convince Chrome's {@code chrome://inspect/devices}
 * that we are a debuggable Android target.
 *
 * <p>Modeled after Android WebView's {@code DevToolsManagerDelegate}: we
 * advertise a single page per process, expose its WebSocket at
 * {@code /devtools/page/<id>}, and <b>deliberately do not return a
 * {@code devtoolsFrontendUrl}</b>. Omitting that field causes Chrome's inspect
 * link to load its bundled {@code chrome-devtools://devtools/bundled/inspector.html}
 * frontend (no extra CSP, no inline-script loader). The legacy Stetho behavior
 * of pointing the frontend at {@code chrome-devtools-frontend.appspot.com}
 * triggers Chrome 138+'s remote-frontend hardening and breaks the panel.</p>
 *
 * <p>Discovery is automatic by socket name suffix
 * ({@code _devtools_remote}); see {@link LocalSocketHttpServer}.</p>
 */
public class ChromeDiscoveryHandler implements HttpHandler {
  private static final String PAGE_ID = "1";

  private static final String PATH_PAGE_LIST = "/json";
  private static final String PATH_PAGE_LIST1 = "/json/list";
  private static final String PATH_VERSION = "/json/version";
  private static final String PATH_ACTIVATE = "/json/activate/" + PAGE_ID;

  /**
   * WebKit "version" string Chrome inspects on the {@code /json/version} response.
   * Chrome only treats this as a free-form identifier — we keep a recent revision
   * suffix so it looks current next to a Chromium-tot WebView.
   */
  private static final String WEBKIT_REV = "@cfede9db1d154de0468cb0538479f34c0755a0f4";
  private static final String WEBKIT_VERSION = "537.36 (" + WEBKIT_REV + ")";

  /**
   * Browser identity string. Chrome 138+ matches this against an allow-list when
   * deciding which protocol features to enable in the bundled frontend; using
   * the {@code Chrome/<major>.<patch>} shape (same as real WebView) lights up the
   * full Network panel even though the underlying server is Stetho.
   */
  private static final String BROWSER_NAME = "Chrome/120.0.6099.144";

  private static final String USER_AGENT_PREFIX = "Mozilla/5.0 (Linux; Android";

  /**
   * V8 version Chrome's frontend reads to gate Debugger / Runtime features.
   * The frontend never executes scripts against this V8 — it just decides which
   * panels and protocol options to expose based on the major version.
   */
  private static final String V8_VERSION = "12.0.0.0";

  /**
   * Structured version of the Chrome DevTools protocol we declare. Chrome
   * doesn't strictly enforce this — it dispatches whatever methods the
   * frontend wants — but bumping it past stetho's historical "1.3" makes
   * modern Network features render rather than gate-fall to a legacy mode.
   */
  private static final String PROTOCOL_VERSION = "1.3";

  private final Context mContext;
  private final String mInspectorPath;

  @Nullable private LightHttpBody mVersionResponse;
  @Nullable private LightHttpBody mPageListResponse;

  public ChromeDiscoveryHandler(Context context, String inspectorPath) {
    mContext = context;
    mInspectorPath = inspectorPath;
  }

  public void register(HandlerRegistry registry) {
    registry.register(new ExactPathMatcher(PATH_PAGE_LIST), this);
    registry.register(new ExactPathMatcher(PATH_PAGE_LIST1), this);
    registry.register(new ExactPathMatcher(PATH_VERSION), this);
    registry.register(new ExactPathMatcher(PATH_ACTIVATE), this);
  }

  @Override
  public boolean handleRequest(SocketLike socket, LightHttpRequest request, LightHttpResponse response) {
    String path = request.uri.getPath();
    try {
      if (PATH_VERSION.equals(path)) {
        handleVersion(response);
      } else if (PATH_PAGE_LIST.equals(path) || PATH_PAGE_LIST1.equals(path)) {
        handlePageList(response);
      } else if (PATH_ACTIVATE.equals(path)) {
        handleActivate(response);
      } else {
        response.code = HttpStatus.HTTP_NOT_IMPLEMENTED;
        response.reasonPhrase = "Not implemented";
        response.body = LightHttpBody.create("No support for " + path + "\n", "text/plain");
      }
    } catch (JSONException e) {
      response.code = HttpStatus.HTTP_INTERNAL_SERVER_ERROR;
      response.reasonPhrase = "Internal server error";
      response.body = LightHttpBody.create(e.toString() + "\n", "text/plain");
    }
    return true;
  }

  private void handleVersion(LightHttpResponse response)
      throws JSONException {
    if (mVersionResponse == null) {
      JSONObject reply = new JSONObject();
      reply.put("Browser", BROWSER_NAME);
      reply.put("Protocol-Version", PROTOCOL_VERSION);
      reply.put("User-Agent", buildUserAgent());
      reply.put("V8-Version", V8_VERSION);
      reply.put("WebKit-Version", WEBKIT_VERSION);
      reply.put("Android-Package", mContext.getPackageName());
      mVersionResponse = LightHttpBody.create(reply.toString(), "application/json");
    }
    setSuccessfulResponse(response, mVersionResponse);
  }

  /**
   * Build a {@code /json/list} response that mirrors Android WebView's shape:
   *
   * <ul>
   *   <li>{@code type: "page"} — Chrome's frontend gates a different feature set
   *       for {@code app} vs {@code page}; {@code page} unlocks Network/Console/Sources.</li>
   *   <li>{@code webSocketDebuggerUrl: "ws://localhost/devtools/page/<id>"} — a
   *       full URL with a host. Chrome rewrites the host to whatever it forwarded
   *       the abstract socket to, so {@code localhost} is just a placeholder.</li>
   *   <li>No {@code devtoolsFrontendUrl} — see class kdoc; this is the single
   *       most important difference vs. legacy Stetho and is what lets Chrome
   *       138+ render its bundled frontend instead of a CSP-blocked appspot
   *       fallback.</li>
   * </ul>
   */
  private void handlePageList(LightHttpResponse response)
      throws JSONException {
    if (mPageListResponse == null) {
      JSONArray reply = new JSONArray();
      JSONObject page = new JSONObject();
      page.put("id", PAGE_ID);
      page.put("type", "page");
      page.put("title", makeTitle());
      page.put("description", mContext.getPackageName());
      page.put("url", "stetho://" + mContext.getPackageName());
      page.put("webSocketDebuggerUrl", "ws://localhost" + mInspectorPath);
      reply.put(page);
      mPageListResponse = LightHttpBody.create(reply.toString(), "application/json");
    }
    setSuccessfulResponse(response, mPageListResponse);
  }

  private static String buildUserAgent() {
    return USER_AGENT_PREFIX + " " + Build.VERSION.RELEASE +
        "; " + Build.MANUFACTURER + " " + Build.MODEL +
        ") AppleWebKit/537.36 (KHTML, like Gecko) " + BROWSER_NAME + " Mobile Safari/537.36";
  }

  private String makeTitle() {
    StringBuilder b = new StringBuilder();
    b.append(getAppLabel());

    b.append(" (powered by Stetho)");

    String processName = ProcessUtil.getProcessName();
    int colonIndex = processName.indexOf(':');
    if (colonIndex >= 0) {
      String nonDefaultProcessName = processName.substring(colonIndex);
      b.append(nonDefaultProcessName);
    }

    return b.toString();
  }

  private void handleActivate(LightHttpResponse response) {
    // Arbitrary response seem acceptable :)
    setSuccessfulResponse(
        response,
        LightHttpBody.create("Target activation ignored\n", "text/plain"));
  }

  private static void setSuccessfulResponse(
      LightHttpResponse response,
      LightHttpBody body) {
    response.code = HttpStatus.HTTP_OK;
    response.reasonPhrase = "OK";
    response.body = body;
  }

  private String getAppLabelAndVersion() {
    StringBuilder b = new StringBuilder();
    PackageManager pm = mContext.getPackageManager();
    b.append(getAppLabel());
    b.append('/');
    try {
      PackageInfo info = pm.getPackageInfo(mContext.getPackageName(), 0 /* flags */);
      b.append(info.versionName);
    } catch (PackageManager.NameNotFoundException e) {
      throw new RuntimeException(e);
    }
    return b.toString();
  }

  private CharSequence getAppLabel() {
    PackageManager pm = mContext.getPackageManager();
    return pm.getApplicationLabel(mContext.getApplicationInfo());
  }
}
