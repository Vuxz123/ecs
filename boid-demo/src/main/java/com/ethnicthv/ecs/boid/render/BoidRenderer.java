package com.ethnicthv.ecs.boid.render;

import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.glClear;
import static org.lwjgl.opengl.GL11C.glClearColor;

public final class BoidRenderer {
    public void bootstrap() {
        // Rendering setup lands in Phase 4. Phase 0 only clears the window.
    }

    public void renderFrame(float red, float green, float blue) {
        glClearColor(red, green, blue, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    public void shutdown() {
        // Reserved for future OpenGL resource teardown.
    }
}
