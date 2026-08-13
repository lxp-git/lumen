/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector.elements;

import dev.lumen.common.ThreadBound;

/**
 * Factory mechanism to dynamically construct the document provider.  This allows for lazy
 * initialization and memory cleanup when DevTools instances disconnect.
 */
public interface DocumentProviderFactory extends ThreadBound {
  DocumentProvider create();
}
