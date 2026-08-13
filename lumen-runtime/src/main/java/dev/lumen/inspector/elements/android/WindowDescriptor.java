/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector.elements.android;

import android.graphics.Rect;
import android.view.View;
import android.view.Window;

import dev.lumen.common.Accumulator;
import dev.lumen.inspector.elements.AbstractChainedDescriptor;
import dev.lumen.inspector.elements.Descriptor;

import javax.annotation.Nullable;

final class WindowDescriptor extends AbstractChainedDescriptor<Window>
    implements HighlightableDescriptor<Window> {
  @Override
  protected void onGetChildren(Window element, Accumulator<Object> children) {
    View decorView = element.peekDecorView();
    if (decorView != null) {
      children.store(decorView);
    }
  }

  @Override
  @Nullable
  public View getViewAndBoundsForHighlighting(Window element, Rect bounds) {
    return element.peekDecorView();
  }

  @Nullable
  @Override
  public Object getElementToHighlightAtPosition(Window element, int x, int y, Rect bounds) {
    final Descriptor.Host host = getHost();
    View view = null;
    HighlightableDescriptor descriptor = null;

    if (host instanceof AndroidDescriptorHost) {
      view = element.peekDecorView();
      descriptor = ((AndroidDescriptorHost) host).getHighlightableDescriptor(view);
    }

    return descriptor == null
        ? null
        : descriptor.getElementToHighlightAtPosition(view, x, y, bounds);
  }
}
