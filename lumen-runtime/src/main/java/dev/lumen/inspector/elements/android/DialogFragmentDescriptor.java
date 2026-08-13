/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.inspector.elements.android;

import android.app.Dialog;
import android.graphics.Rect;
import android.view.View;

import dev.lumen.common.Accumulator;
import dev.lumen.common.LogUtil;
import dev.lumen.common.Util;
import dev.lumen.common.android.DialogFragmentAccessor;
import dev.lumen.common.android.FragmentCompat;
import dev.lumen.inspector.elements.AbstractChainedDescriptor;
import dev.lumen.inspector.elements.AttributeAccumulator;
import dev.lumen.inspector.elements.ChainedDescriptor;
import dev.lumen.inspector.elements.ComputedStyleAccumulator;
import dev.lumen.inspector.elements.Descriptor;
import dev.lumen.inspector.elements.DescriptorMap;
import dev.lumen.inspector.elements.NodeType;
import dev.lumen.inspector.elements.StyleAccumulator;
import dev.lumen.inspector.elements.StyleRuleNameAccumulator;

import javax.annotation.Nullable;

final class DialogFragmentDescriptor
    extends Descriptor<Object>
    implements ChainedDescriptor<Object>, HighlightableDescriptor<Object> {
  private final DialogFragmentAccessor mAccessor;
  private Descriptor<? super Object> mSuper;

  public static DescriptorMap register(DescriptorMap map) {
    maybeRegister(map, FragmentCompat.getSupportLibInstance());
    maybeRegister(map, FragmentCompat.getFrameworkInstance());
    return map;
  }

  private static void maybeRegister(DescriptorMap map, @Nullable FragmentCompat compat) {
    if (compat != null) {
      Class<?> dialogFragmentClass = compat.getDialogFragmentClass();
      LogUtil.d("Adding support for %s", dialogFragmentClass);
      map.registerDescriptor(dialogFragmentClass, new DialogFragmentDescriptor(compat));
    }
  }

  private DialogFragmentDescriptor(FragmentCompat compat) {
    mAccessor = compat.forDialogFragment();
  }

  @Override
  public void setSuper(Descriptor<? super Object> superDescriptor) {
    Util.throwIfNull(superDescriptor);

    if (superDescriptor != mSuper) {
      if (mSuper != null) {
        throw new IllegalStateException();
      }
      mSuper = superDescriptor;
    }
  }

  @Override
  public void hook(Object element) {
    mSuper.hook(element);
  }

  @Override
  public void unhook(Object element) {
    mSuper.unhook(element);
  }

  @Override
  public NodeType getNodeType(Object element) {
    return mSuper.getNodeType(element);
  }

  @Override
  public String getNodeName(Object element) {
    return mSuper.getNodeName(element);
  }

  @Override
  public String getLocalName(Object element) {
    return mSuper.getLocalName(element);
  }

  @Nullable
  @Override
  public String getNodeValue(Object element) {
    return mSuper.getNodeValue(element);
  }

  @Override
  public void getChildren(Object element, Accumulator<Object> children) {
    /**
     * We do NOT want the children from our super-{@link Descriptor}, which is probably
     * {@link FragmentDescriptor}. We only want to emit the {@link Dialog}, not the {@link View}.
     * Therefore, we don't call mSuper.getChildren(), and this is the reason why we don't derive
     * from {@link AbstractChainedDescriptor} (it doesn't allow a non-chained implementation of
     * {@link Descriptor#getChildren(Object, Accumulator)}).
     */
    children.store(mAccessor.getDialog(element));
  }

  @Override
  public void getAttributes(Object element, AttributeAccumulator attributes) {
    mSuper.getAttributes(element, attributes);
  }

  @Override
  public void setAttributesAsText(Object element, String text) {
    mSuper.setAttributesAsText(element, text);
  }

  @Nullable
  @Override
  public View getViewAndBoundsForHighlighting(Object element, Rect bounds) {
    final Descriptor.Host host = getHost();
    Dialog dialog = null;
    HighlightableDescriptor descriptor = null;

    if (host instanceof AndroidDescriptorHost) {
      dialog = mAccessor.getDialog(element);
      descriptor = ((AndroidDescriptorHost) host).getHighlightableDescriptor(dialog);
    }

    return descriptor == null
        ? null
        : descriptor.getViewAndBoundsForHighlighting(dialog, bounds);
  }

  @Nullable
  @Override
  public Object getElementToHighlightAtPosition(Object element, int x, int y, Rect bounds) {
    final Descriptor.Host host = getHost();
    Dialog dialog = null;
    HighlightableDescriptor descriptor = null;

    if (host instanceof AndroidDescriptorHost) {
      dialog = mAccessor.getDialog(element);
      descriptor = ((AndroidDescriptorHost) host).getHighlightableDescriptor(dialog);
    }

    return descriptor == null
        ? null
        : descriptor.getElementToHighlightAtPosition(dialog, x, y, bounds);
  }

  @Override
  public void getStyleRuleNames(Object element, StyleRuleNameAccumulator accumulator) {
  }

  @Override
  public void getStyles(Object element, String ruleName, StyleAccumulator accumulator) {
  }

  @Override
  public void setStyle(Object element, String ruleName, String name, String value) {
  }

  @Override
  public void getComputedStyles(Object element, ComputedStyleAccumulator styles) {
  }
}
