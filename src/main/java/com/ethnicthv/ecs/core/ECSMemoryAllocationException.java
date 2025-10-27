package com.ethnicthv.ecs.core;

/**
 * Domain-specific unchecked exception for ECS memory allocation failures.
 * Wraps low-level OutOfMemoryError or provides more context such as
 * component index, requested bytes, and capacity to aid diagnostics.
 */
public class ECSMemoryAllocationException extends RuntimeException {
    public ECSMemoryAllocationException(String message) {
        super(message);
    }
    public ECSMemoryAllocationException(String message, Throwable cause) {
        super(message, cause);
    }
}

