/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.server;

import dev.lumen.common.ProcessUtil;

public class AddressNameHelper {
  private static final String PREFIX = "lumen_";

  public static String createCustomAddress(String suffix) {
    final int userId = ProcessUtil.getUserId();
    return
        PREFIX +
        ProcessUtil.getProcessName() +
        (userId == 0 ? "" : ("_" + userId)) +
        suffix;
  }
}
