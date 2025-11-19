package com.ethnicthv.ecs.core.system.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a system field or parameter to receive the current entity id during a query run.
 * Constraints: at most one per @Query method and it must be of type int (or Integer for parameters).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER})
public @interface Id {
}
