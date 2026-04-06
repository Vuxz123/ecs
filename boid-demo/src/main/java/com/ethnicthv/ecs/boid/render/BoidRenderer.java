package com.ethnicthv.ecs.boid.render;

import com.ethnicthv.ecs.boid.sim.BoidRuntime;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_POINTS;
import static org.lwjgl.opengl.GL11C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.glBlendFunc;
import static org.lwjgl.opengl.GL11C.glClear;
import static org.lwjgl.opengl.GL11C.glClearColor;
import static org.lwjgl.opengl.GL11C.glDrawArrays;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glBufferData;
import static org.lwjgl.opengl.GL15C.glBufferSubData;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL15C.glGenBuffers;
import static org.lwjgl.opengl.GL20C.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20C.glAttachShader;
import static org.lwjgl.opengl.GL20C.glCompileShader;
import static org.lwjgl.opengl.GL20C.glCreateProgram;
import static org.lwjgl.opengl.GL20C.glCreateShader;
import static org.lwjgl.opengl.GL20C.glDeleteProgram;
import static org.lwjgl.opengl.GL20C.glDeleteShader;
import static org.lwjgl.opengl.GL20C.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL20C.glGetProgrami;
import static org.lwjgl.opengl.GL20C.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL20C.glGetShaderi;
import static org.lwjgl.opengl.GL20C.glGetUniformLocation;
import static org.lwjgl.opengl.GL20C.glLinkProgram;
import static org.lwjgl.opengl.GL20C.glShaderSource;
import static org.lwjgl.opengl.GL20C.glUniform1f;
import static org.lwjgl.opengl.GL20C.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL20C.glVertexAttribPointer;
import static org.lwjgl.opengl.GL20C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;

public final class BoidRenderer {
    private static final String VERTEX_SHADER = """
        #version 330 core

        layout (location = 0) in vec3 aPosition;

        uniform mat4 uViewProjection;
        uniform float uPointScale;

        out float vDepth;

        void main() {
            vec4 viewClip = uViewProjection * vec4(aPosition, 1.0);
            gl_Position = viewClip;
            float depth = clamp(-viewClip.z * 0.01, 0.0, 1.0);
            vDepth = depth;
            float pointSize = uPointScale / max(0.35, -viewClip.z * 0.08);
            gl_PointSize = clamp(pointSize, 1.5, 7.0);
        }
        """;

    private static final String FRAGMENT_SHADER = """
        #version 330 core

        in float vDepth;

        out vec4 fragColor;

        void main() {
            vec2 pointCoord = gl_PointCoord * 2.0 - 1.0;
            float radiusSq = dot(pointCoord, pointCoord);
            if (radiusSq > 1.0) {
                discard;
            }

            vec3 nearColor = vec3(0.95, 0.84, 0.42);
            vec3 farColor = vec3(0.20, 0.72, 0.95);
            vec3 color = mix(nearColor, farColor, vDepth);
            float alpha = 1.0 - smoothstep(0.65, 1.0, radiusSq);
            fragColor = vec4(color, alpha);
        }
        """;

    private int programId;
    private int vaoId;
    private int positionVboId;
    private int viewProjectionLocation;
    private int pointScaleLocation;
    private int bufferedBoidCapacity;
    private float[] positions = new float[0];
    private FloatBuffer positionBuffer;
    private long lastRenderNanos;
    private long totalRenderNanos;
    private long renderedFrameCount;
    private int renderStride = 1;
    private int lastVisibleBoidCount;

    public void bootstrap() {
        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        vaoId = glGenVertexArrays();
        positionVboId = glGenBuffers();
        viewProjectionLocation = glGetUniformLocation(programId, "uViewProjection");
        pointScaleLocation = glGetUniformLocation(programId, "uPointScale");

        glBindVertexArray(vaoId);
        glBindBuffer(GL_ARRAY_BUFFER, positionVboId);
        glBufferData(GL_ARRAY_BUFFER, 0L, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        glBindVertexArray(0);

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }

    public void renderFrame(
        BoidRuntime simulation,
        int framebufferWidth,
        int framebufferHeight,
        float red,
        float green,
        float blue,
        float[] viewProjectionMatrix
    ) {
        long startedAt = System.nanoTime();
        int boidCount = simulation.boidCount();
        int visibleBoids = Math.max(1, (boidCount + renderStride - 1) / renderStride);
        ensureCapacity(visibleBoids);
        lastVisibleBoidCount = simulation.copyPositions(positions, renderStride);

        positionBuffer.clear();
        positionBuffer.put(positions, 0, lastVisibleBoidCount * 3);
        positionBuffer.flip();

        glViewport(0, 0, framebufferWidth, framebufferHeight);
        glClearColor(red, green, blue, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        glUseProgram(programId);
        glUniformMatrix4fv(viewProjectionLocation, false, viewProjectionMatrix);
        glUniform1f(pointScaleLocation, Math.min(framebufferWidth, framebufferHeight) * 0.85f);

        glBindVertexArray(vaoId);
        glBindBuffer(GL_ARRAY_BUFFER, positionVboId);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, positionBuffer);
        glDrawArrays(GL_POINTS, 0, lastVisibleBoidCount);
        glBindVertexArray(0);
        glUseProgram(0);

        lastRenderNanos = System.nanoTime() - startedAt;
        totalRenderNanos += lastRenderNanos;
        renderedFrameCount++;
    }

    public double averageRenderMillis() {
        return renderedFrameCount == 0L ? 0.0 : (totalRenderNanos / (double) renderedFrameCount) / 1_000_000.0;
    }

    public double lastRenderMillis() {
        return lastRenderNanos / 1_000_000.0;
    }

    public int renderStride() {
        return renderStride;
    }

    public void setRenderStride(int renderStride) {
        if (renderStride <= 0) {
            throw new IllegalArgumentException("renderStride must be > 0");
        }
        this.renderStride = renderStride;
    }

    public int lastVisibleBoidCount() {
        return lastVisibleBoidCount;
    }

    public void shutdown() {
        if (positionBuffer != null) {
            MemoryUtil.memFree(positionBuffer);
            positionBuffer = null;
        }
        if (positionVboId != 0) {
            glDeleteBuffers(positionVboId);
            positionVboId = 0;
        }
        if (vaoId != 0) {
            glDeleteVertexArrays(vaoId);
            vaoId = 0;
        }
        if (programId != 0) {
            glDeleteProgram(programId);
            programId = 0;
        }
    }

    private void ensureCapacity(int boidCount) {
        if (boidCount <= bufferedBoidCapacity) {
            return;
        }

        bufferedBoidCapacity = Math.max(boidCount, Math.max(256, bufferedBoidCapacity * 2));
        positions = new float[bufferedBoidCapacity * 3];

        if (positionBuffer != null) {
            MemoryUtil.memFree(positionBuffer);
        }
        positionBuffer = MemoryUtil.memAllocFloat(bufferedBoidCapacity * 3);

        glBindBuffer(GL_ARRAY_BUFFER, positionVboId);
        glBufferData(GL_ARRAY_BUFFER, (long) bufferedBoidCapacity * 3L * Float.BYTES, GL_DYNAMIC_DRAW);
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        int vertexShaderId = compileShader(GL_VERTEX_SHADER, vertexSource);
        int fragmentShaderId = compileShader(GL_FRAGMENT_SHADER, fragmentSource);
        int program = glCreateProgram();
        glAttachShader(program, vertexShaderId);
        glAttachShader(program, fragmentShaderId);
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) {
            String infoLog = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IllegalStateException("Failed to link boid shader program: " + infoLog);
        }

        glDeleteShader(vertexShaderId);
        glDeleteShader(fragmentShaderId);
        return program;
    }

    private static int compileShader(int shaderType, String source) {
        int shaderId = glCreateShader(shaderType);
        glShaderSource(shaderId, source);
        glCompileShader(shaderId);
        if (glGetShaderi(shaderId, GL_COMPILE_STATUS) == 0) {
            String infoLog = glGetShaderInfoLog(shaderId);
            glDeleteShader(shaderId);
            throw new IllegalStateException("Failed to compile boid shader: " + infoLog);
        }
        return shaderId;
    }

    private static float[] buildViewProjectionMatrix(
        int framebufferWidth,
        int framebufferHeight,
        float worldHalfExtent,
        double elapsedSeconds
    ) {
        float aspect = framebufferHeight == 0 ? 1.0f : (float) framebufferWidth / framebufferHeight;
        float radius = worldHalfExtent * 2.6f;
        float yaw = (float) (elapsedSeconds * 0.18);
        float pitch = 0.42f;

        float eyeX = (float) (Math.cos(yaw) * Math.cos(pitch)) * radius;
        float eyeY = (float) Math.sin(pitch) * radius * 0.92f;
        float eyeZ = (float) (Math.sin(yaw) * Math.cos(pitch)) * radius;

        float[] projection = perspective((float) Math.toRadians(50.0), aspect, 0.1f, worldHalfExtent * 8.0f);
        float[] view = lookAt(eyeX, eyeY, eyeZ, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        return multiply(projection, view);
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
}
