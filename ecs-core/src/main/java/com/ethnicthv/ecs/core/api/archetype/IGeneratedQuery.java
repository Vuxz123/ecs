package com.ethnicthv.ecs.core.api.archetype;

public interface IGeneratedQuery {

    default IQueryBuilder withShared(Object managedValue) {
        throw new UnsupportedOperationException("withShared(Object) is not implemented by this query instance");
    }

    default IQueryBuilder withShared(Class<?> unmanagedSharedType, long value) {
        throw new UnsupportedOperationException("withShared(Class<?>, long) is not implemented by this query instance");
    }


    default void runQuery() {
        throw new UnsupportedOperationException("runQuery is not implemented by this query instance");
    }
}
