package com.ethnicthv.ecs.core.system.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Marks an interface as an unmanaged instance component.
 * The interface must declare JavaBean-style getters/setters for primitive fields.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface UnmanagedComponent {}

