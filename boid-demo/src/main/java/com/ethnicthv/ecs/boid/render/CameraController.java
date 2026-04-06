package com.ethnicthv.ecs.boid.render;

public final class CameraController {
    private static final float MIN_DISTANCE = 40.0f;
    private static final float MAX_DISTANCE = 1200.0f;
    private static final float MIN_PITCH = -1.35f;
    private static final float MAX_PITCH = 1.35f;

    private float yaw = 0.8f;
    private float pitch = 0.42f;
    private float distance = 280.0f;
    private float orbitSpeed = 0.18f;
    private boolean autoOrbit = true;
    private boolean rotating;
    private double lastCursorX;
    private double lastCursorY;

    public void update(double deltaSeconds) {
        if (autoOrbit && !rotating) {
            yaw += (float) (orbitSpeed * deltaSeconds);
        }
    }

    public void mouseButtonCallback(int button, int action) {
        if (button != 1) {
            return;
        }
        rotating = action != 0;
    }

    public void cursorPosCallback(double cursorX, double cursorY, boolean capturedByUi) {
        if (rotating && !capturedByUi) {
            double deltaX = cursorX - lastCursorX;
            double deltaY = cursorY - lastCursorY;
            yaw += (float) (deltaX * 0.0085f);
            pitch = clamp(pitch - (float) (deltaY * 0.0085f), MIN_PITCH, MAX_PITCH);
            autoOrbit = false;
        }

        lastCursorX = cursorX;
        lastCursorY = cursorY;
    }

    public void scrollCallback(double yOffset, boolean capturedByUi) {
        if (capturedByUi) {
            return;
        }

        float zoomScale = (float) Math.exp(-yOffset * 0.1f);
        distance = clamp(distance * zoomScale, MIN_DISTANCE, MAX_DISTANCE);
        autoOrbit = false;
    }

    public float[] buildViewProjectionMatrix(int framebufferWidth, int framebufferHeight, float worldHalfExtent) {
        float aspect = framebufferHeight == 0 ? 1.0f : (float) framebufferWidth / framebufferHeight;
        float minDistance = Math.max(MIN_DISTANCE, worldHalfExtent * 0.45f);
        float maxDistance = Math.max(MAX_DISTANCE, worldHalfExtent * 8.0f);
        distance = clamp(distance, minDistance, maxDistance);

        float eyeX = (float) (Math.cos(yaw) * Math.cos(pitch)) * distance;
        float eyeY = (float) Math.sin(pitch) * distance;
        float eyeZ = (float) (Math.sin(yaw) * Math.cos(pitch)) * distance;

        float[] projection = perspective((float) Math.toRadians(50.0), aspect, 0.1f, worldHalfExtent * 10.0f);
        float[] view = lookAt(eyeX, eyeY, eyeZ, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        return multiply(projection, view);
    }

    public boolean autoOrbit() {
        return autoOrbit;
    }

    public void setAutoOrbit(boolean autoOrbit) {
        this.autoOrbit = autoOrbit;
    }

    public float distance() {
        return distance;
    }

    public void setDistance(float distance) {
        this.distance = clamp(distance, MIN_DISTANCE, MAX_DISTANCE);
    }

    private static float[] perspective(float fieldOfViewRadians, float aspect, float near, float far) {
        float tanHalfFov = (float) Math.tan(fieldOfViewRadians * 0.5f);
        float[] matrix = new float[16];
        matrix[0] = 1.0f / (aspect * tanHalfFov);
        matrix[5] = 1.0f / tanHalfFov;
        matrix[10] = -(far + near) / (far - near);
        matrix[11] = -1.0f;
        matrix[14] = -(2.0f * far * near) / (far - near);
        return matrix;
    }

    private static float[] lookAt(
        float eyeX,
        float eyeY,
        float eyeZ,
        float centerX,
        float centerY,
        float centerZ,
        float upX,
        float upY,
        float upZ
    ) {
        float forwardX = centerX - eyeX;
        float forwardY = centerY - eyeY;
        float forwardZ = centerZ - eyeZ;
        float forwardScale = invLength(forwardX, forwardY, forwardZ);
        forwardX *= forwardScale;
        forwardY *= forwardScale;
        forwardZ *= forwardScale;

        float sideX = forwardY * upZ - forwardZ * upY;
        float sideY = forwardZ * upX - forwardX * upZ;
        float sideZ = forwardX * upY - forwardY * upX;
        float sideScale = invLength(sideX, sideY, sideZ);
        sideX *= sideScale;
        sideY *= sideScale;
        sideZ *= sideScale;

        float trueUpX = sideY * forwardZ - sideZ * forwardY;
        float trueUpY = sideZ * forwardX - sideX * forwardZ;
        float trueUpZ = sideX * forwardY - sideY * forwardX;

        float[] matrix = identity();
        matrix[0] = sideX;
        matrix[4] = sideY;
        matrix[8] = sideZ;
        matrix[1] = trueUpX;
        matrix[5] = trueUpY;
        matrix[9] = trueUpZ;
        matrix[2] = -forwardX;
        matrix[6] = -forwardY;
        matrix[10] = -forwardZ;
        matrix[12] = -(sideX * eyeX + sideY * eyeY + sideZ * eyeZ);
        matrix[13] = -(trueUpX * eyeX + trueUpY * eyeY + trueUpZ * eyeZ);
        matrix[14] = forwardX * eyeX + forwardY * eyeY + forwardZ * eyeZ;
        return matrix;
    }

    private static float[] multiply(float[] left, float[] right) {
        float[] result = new float[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                result[column * 4 + row] =
                    left[row] * right[column * 4] +
                    left[4 + row] * right[column * 4 + 1] +
                    left[8 + row] * right[column * 4 + 2] +
                    left[12 + row] * right[column * 4 + 3];
            }
        }
        return result;
    }

    private static float invLength(float x, float y, float z) {
        float lengthSq = x * x + y * y + z * z;
        if (lengthSq <= 0.00001f) {
            return 1.0f;
        }
        return 1.0f / (float) Math.sqrt(lengthSq);
    }

    private static float[] identity() {
        return new float[] {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        };
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
