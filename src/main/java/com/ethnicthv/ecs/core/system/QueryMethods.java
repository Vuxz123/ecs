package com.ethnicthv.ecs.core.system;

import com.ethnicthv.ecs.core.api.archetype.IQuery;

import java.lang.reflect.Method;

/**
 * Cached reflective Method handles for IQuery methods used by dynamic proxies.
 * Fail-fast on interface signature changes and avoid per-call lookups.
 */
public final class QueryMethods {
    public static final Method FOR_EACH_ENTITY;
    public static final Method FOR_EACH_PARALLEL;

    static {
        try {
            FOR_EACH_ENTITY = IQuery.class.getMethod("forEachEntity", IQuery.EntityConsumer.class);
            FOR_EACH_PARALLEL = IQuery.class.getMethod("forEachParallel", IQuery.EntityConsumer.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Fatal: IQuery interface mismatch", e);
        }
    }

    private QueryMethods() {}
}

