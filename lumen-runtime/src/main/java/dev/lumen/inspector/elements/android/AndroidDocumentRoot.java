/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector.elements.android;

import android.app.Application;

import dev.lumen.common.Accumulator;
import dev.lumen.common.Util;
import dev.lumen.inspector.elements.AbstractChainedDescriptor;
import dev.lumen.inspector.elements.NodeType;

// For the root, we use 1 object for both element and descriptor.

final class AndroidDocumentRoot extends AbstractChainedDescriptor<AndroidDocumentRoot> {
  private final Application mApplication;

  public AndroidDocumentRoot(Application application) {
    mApplication = Util.throwIfNull(application);
  }

  @Override
  protected NodeType onGetNodeType(AndroidDocumentRoot element) {
    return NodeType.DOCUMENT_NODE;
  }

  @Override
  protected String onGetNodeName(AndroidDocumentRoot element) {
    return "root";
  }

  @Override
  protected void onGetChildren(AndroidDocumentRoot element, Accumulator<Object> children) {
    children.store(mApplication);
  }
}
