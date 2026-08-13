/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package dev.lumen.json.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public field for {@link dev.lumen.json.ObjectMapper}.
 *
 * <p>{@link Target} is FIELD-only so Kotlin property annotations land on the
 * Java field ({@code @JvmField}) rather than the synthetic getter. Without
 * that, {@code ObjectMapper} sees no annotation and emits empty CDP params —
 * which is why Chrome's Network panel stayed blank after a successful
 * inspect handshake.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JsonProperty {

  boolean required() default false;

}

