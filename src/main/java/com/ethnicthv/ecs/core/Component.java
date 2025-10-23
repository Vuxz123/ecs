package com.ethnicthv.ecs.core;

import java.lang.annotation.*;

/**
 * Base marker interface for all components
 */
public interface Component {

    /**
     * Annotation to mark a field as a component field with layout information
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Field {
        /**
         * Size in bytes (0 means auto-detect from type)
         */
        int size() default 0;

        /**
         * Explicit offset position in bytes (-1 means auto-layout)
         */
        int offset() default -1;

        /**
         * Alignment requirement in bytes (0 means natural alignment)
         */
        int alignment() default 0;
    }

    /**
     * Annotation to specify the overall component layout
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Layout {
        /**
         * Layout strategy: SEQUENTIAL (packed) or PADDING (aligned)
         */
        LayoutType value() default LayoutType.SEQUENTIAL;

        /**
         * Total size override (-1 means auto-calculate)
         */
        int size() default -1;
    }

    enum LayoutType {
        SEQUENTIAL,  // Pack fields sequentially
        PADDING,     // Add padding for alignment
        EXPLICIT     // Use explicit offsets
    }
}

