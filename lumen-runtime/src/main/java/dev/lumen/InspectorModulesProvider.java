/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen;

import dev.lumen.inspector.protocol.ChromeDevtoolsDomain;

public interface InspectorModulesProvider {
  Iterable<ChromeDevtoolsDomain> get();
}
