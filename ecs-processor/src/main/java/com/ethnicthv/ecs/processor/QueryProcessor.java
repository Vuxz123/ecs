package com.ethnicthv.ecs.processor;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.*;

@SupportedAnnotationTypes({
    "com.ethnicthv.ecs.core.system.annotation.Query"
})
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class QueryProcessor extends AbstractProcessor {
    private Elements elementUtils;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.elementUtils = processingEnv.getElementUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        TypeElement queryAnno = elementUtils.getTypeElement("com.ethnicthv.ecs.core.system.annotation.Query");
        if (queryAnno == null) return false;

        Map<TypeElement, List<ExecutableElement>> byClass = new LinkedHashMap<>();
        for (Element e : roundEnv.getElementsAnnotatedWith(queryAnno)) {
            if (e.getKind() != ElementKind.METHOD) {
                error("@Query can only be placed on methods: %s", e);
                continue;
            }
            ExecutableElement method = (ExecutableElement) e;
            TypeElement owner = (TypeElement) method.getEnclosingElement();
            byClass.computeIfAbsent(owner, k -> new ArrayList<>()).add(method);
        }

        for (Map.Entry<TypeElement, List<ExecutableElement>> entry : byClass.entrySet()) {
            TypeElement owner = entry.getKey();
            List<ExecutableElement> methods = entry.getValue();
            try {
                generateInjector(owner, methods);
                for (ExecutableElement m : methods) generateRunner(owner, m);
            } catch (IOException ex) {
                error("Failed to generate for %s: %s", owner.getQualifiedName(), ex.getMessage());
            }
        }

        return false;
    }

    private void generateInjector(TypeElement owner, List<ExecutableElement> methods) throws IOException {
        String pkg = elementUtils.getPackageOf(owner).getQualifiedName().toString();
        String simple = owner.getSimpleName().toString();
        String ownerQualified = owner.getQualifiedName().toString();
        String name = simple + "__QueryInjector";
        String fqn = pkg.isEmpty() ? name : pkg + "." + name;
        JavaFileObject file = processingEnv.getFiler().createSourceFile(fqn, owner);
        try (Writer w = file.openWriter()) {
            if (!pkg.isEmpty()) w.write("package " + pkg + ";\n\n");
            w.write("@SuppressWarnings(\"all\")\n");
            w.write("public final class " + name + " {\n");
            w.write("  private " + name + "(){}\n");
            w.write("  public static void inject(Object system, com.ethnicthv.ecs.core.archetype.ArchetypeWorld world) {\n");
            w.write("    " + ownerQualified + " self = (" + ownerQualified + ") system;\n");
            for (ExecutableElement m : methods) {
                AnnotationMirror q = getAnnotation(m, "com.ethnicthv.ecs.core.system.annotation.Query");
                String fieldInject = readString(q, "fieldInject");
                if (fieldInject == null || fieldInject.isEmpty()) {
                    error("@Query on %s missing fieldInject", m);
                    continue;
                }
                String runner = simple + "__" + m.getSimpleName() + "__QueryRunner";
                w.write("    try {\n");
                w.write("      java.lang.reflect.Field f = " + ownerQualified + ".class.getDeclaredField(\"" + fieldInject + "\");\n");
                w.write("      f.setAccessible(true);\n");
                w.write("      f.set(self, new " + runner + "(world, self));\n");
                w.write("    } catch (Throwable t) { throw new RuntimeException(t); }\n");
            }
            w.write("  }\n");
            w.write("}\n");
        }
    }

    private void generateRunner(TypeElement owner, ExecutableElement method) throws IOException {
        String pkg = elementUtils.getPackageOf(owner).getQualifiedName().toString();
        String simple = owner.getSimpleName().toString();
        String ownerQualified = owner.getQualifiedName().toString();
        String name = simple + "__" + method.getSimpleName() + "__QueryRunner";
        String fqn = pkg.isEmpty() ? name : pkg + "." + name;

        // Collect parameter component classes from @Component(type=...)
        List<String> paramComponentClasses = new ArrayList<>();
        for (VariableElement p : method.getParameters()) {
            AnnotationMirror compAnno = getAnnotation(p, "com.ethnicthv.ecs.core.system.annotation.Component");
            if (compAnno == null) {
                error("All parameters must be annotated with @com.ethnicthv.ecs.core.system.annotation.Component: %s", p);
                continue;
            }
            String typeClass = readTypeClass(compAnno, "type");
            if (typeClass == null) {
                error("@Component on %s must specify type()", p);
                continue;
            }
            paramComponentClasses.add(typeClass);
        }

        // Read query filters (with/without/any)
        AnnotationMirror q = getAnnotation(method, "com.ethnicthv.ecs.core.system.annotation.Query");
        List<String> withClasses = readTypeArray(q, "with");
        List<String> withoutClasses = readTypeArray(q, "without");
        List<String> anyClasses = readTypeArray(q, "any");

        JavaFileObject file = processingEnv.getFiler().createSourceFile(fqn, owner);
        try (Writer w = file.openWriter()) {
            if (!pkg.isEmpty()) w.write("package " + pkg + ";\n\n");
            w.write("@SuppressWarnings(\"all\")\n");
            w.write("public final class " + name + " implements com.ethnicthv.ecs.core.api.archetype.IQuery {\n");
            w.write("  private final com.ethnicthv.ecs.core.archetype.ArchetypeWorld world;\n");
            w.write("  private final " + ownerQualified + " system;\n");
            w.write("  private final java.lang.invoke.MethodHandle mh;\n");
            // Precompute param classes array
            w.write("  private static final Class<?>[] PARAM_CLASSES = new Class<?>[]{");
            for (int i = 0; i < paramComponentClasses.size(); i++) {
                w.write(paramComponentClasses.get(i) + ".class");
                if (i < paramComponentClasses.size()-1) w.write(", ");
            }
            w.write("};\n");
            w.write("  public " + name + "(com.ethnicthv.ecs.core.archetype.ArchetypeWorld w, " + ownerQualified + " s){ this.world=w; this.system=s; this.mh=createMH(); }\n");

            // MH builder
            w.write("  private java.lang.invoke.MethodHandle createMH(){\n");
            w.write("    try {\n");
            w.write("      var lookup = java.lang.invoke.MethodHandles.lookup();\n");
            w.write("      var prv = java.lang.invoke.MethodHandles.privateLookupIn(" + ownerQualified + ".class, lookup);\n");
            // Build MethodType: (ComponentHandle, ...)
            w.write("      var mt = java.lang.invoke.MethodType.methodType(void.class");
            for (int i = 0; i < paramComponentClasses.size(); i++) {
                w.write(", com.ethnicthv.ecs.core.components.ComponentHandle.class");
            }
            w.write(");\n");
            w.write("      return prv.findVirtual(" + ownerQualified + ".class, \"" + method.getSimpleName() + "\", mt);\n");
            w.write("    } catch (Throwable t) { throw new RuntimeException(t); }\n");
            w.write("  }\n");

            // runQuery implementation - sequential
            w.write("  @Override public void runQuery(){\n");
            w.write("    final var cm = world.getComponentManager();\n");
            // Build paramIds
            w.write("    final int paramCount = PARAM_CLASSES.length;\n");
            w.write("    final int[] paramIds = new int[paramCount];\n");
            w.write("    for (int i = 0; i < paramCount; i++) { Integer id = world.getComponentTypeId(PARAM_CLASSES[i]); if (id == null) throw new IllegalStateException(\"Component not registered: \" + PARAM_CLASSES[i]); paramIds[i] = id; }\n");
            // Build filter ids arrays
            w.write("    final int[] withIds = new int[]{");
            for (int i = 0; i < withClasses.size(); i++) {
                w.write("java.util.Objects.requireNonNull(world.getComponentTypeId(" + withClasses.get(i) + ".class)).intValue()");
                if (i < withClasses.size()-1) w.write(",");
            }
            w.write("};\n");
            w.write("    final int[] withoutIds = new int[]{");
            for (int i = 0; i < withoutClasses.size(); i++) {
                w.write("java.util.Objects.requireNonNull(world.getComponentTypeId(" + withoutClasses.get(i) + ".class)).intValue()");
                if (i < withoutClasses.size()-1) w.write(",");
            }
            w.write("};\n");
            w.write("    final int[] anyIds = new int[]{");
            for (int i = 0; i < anyClasses.size(); i++) {
                w.write("java.util.Objects.requireNonNull(world.getComponentTypeId(" + anyClasses.get(i) + ".class)).intValue()");
                if (i < anyClasses.size()-1) w.write(",");
            }
            w.write("};\n");

            // Iterate archetypes
            w.write("    for (com.ethnicthv.ecs.core.archetype.Archetype archetype : world.getAllArchetypes()) {\n");
            // Apply with/without/any filters by IDs
            w.write("      boolean ok = true;\n");
            w.write("      // WITH\n");
            w.write("      for (int id : withIds) { if (archetype.indexOfComponentType(id) < 0) { ok = false; break; } }\n");
            w.write("      if (!ok) continue;\n");
            w.write("      // WITHOUT\n");
            w.write("      for (int id : withoutIds) { if (archetype.indexOfComponentType(id) >= 0) { ok = false; break; } }\n");
            w.write("      if (!ok) continue;\n");
            w.write("      // ANY\n");
            w.write("      if (anyIds.length > 0) { boolean any=false; for (int id : anyIds) { if (archetype.indexOfComponentType(id) >= 0) { any=true; break; } } if (!any) continue; }\n");

            // Resolve component indices for parameters for this archetype
            w.write("      final int[] compIdx = new int[paramCount];\n");
            w.write("      for (int i = 0; i < paramCount; i++) { compIdx[i] = archetype.indexOfComponentType(paramIds[i]); if (compIdx[i] < 0) { ok=false; break; } }\n");
            w.write("      if (!ok) continue;\n");

            // Iterate chunks
            w.write("      for (com.ethnicthv.ecs.core.api.archetype.IArchetypeChunk ch : archetype.getChunks()) {\n");
            w.write("        final int cap = ch.getCapacity();\n");
            w.write("        for (int ei = 0; ei < cap; ei++) { int eid = ch.getEntityId(ei); if (eid == -1) continue;\n");
            // Acquire bound handles
            w.write("          com.ethnicthv.ecs.core.components.ComponentManager.BoundHandle[] bound = new com.ethnicthv.ecs.core.components.ComponentManager.BoundHandle[paramCount];\n");
            w.write("          try {\n");
            w.write("            for (int k = 0; k < paramCount; k++) { var seg = ch.getComponentData(compIdx[k], ei); bound[k] = cm.acquireBoundHandle(PARAM_CLASSES[k], seg); }\n");
            // Build args and invoke mh
            w.write("            Object[] args = new Object[paramCount + 1]; args[0] = system;\n");
            w.write("            for (int k = 0; k < paramCount; k++) args[k+1] = bound[k].handle();\n");
            w.write("            mh.invokeWithArguments(java.util.Arrays.asList(args));\n");
            w.write("          } catch (Throwable t) { throw new RuntimeException(t); }\n");
            w.write("          finally { for (int k = 0; k < paramCount; k++) { if (bound[k] != null) try { bound[k].close(); } catch (Exception ignore) {} } }\n");
            w.write("        }\n");
            w.write("      }\n");
            w.write("    }\n");
            w.write("  }\n");

            w.write("}\n");
        }
    }

    private AnnotationMirror getAnnotation(Element e, String fqn) {
        for (AnnotationMirror am : e.getAnnotationMirrors()) {
            if (((TypeElement) am.getAnnotationType().asElement()).getQualifiedName().contentEquals(fqn)) return am;
        }
        return null;
    }

    private String readString(AnnotationMirror am, String name) {
        if (am == null) return null;
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : am.getElementValues().entrySet()) {
            if (e.getKey().getSimpleName().contentEquals(name)) return String.valueOf(e.getValue().getValue());
        }
        return null;
    }

    private List<String> readTypeArray(AnnotationMirror am, String name) {
        List<String> out = new ArrayList<>();
        if (am == null) return out;
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : am.getElementValues().entrySet()) {
            if (!e.getKey().getSimpleName().contentEquals(name)) continue;
            Object v = e.getValue().getValue();
            if (v instanceof List<?> list) {
                for (Object o : list) {
                    Object ev = ((AnnotationValue) o).getValue();
                    out.add(ev.toString()); // returns class literal string like com.Foo.class
                    // We need the class name without ".class"
                    if (out.get(out.size()-1).endsWith(".class")) {
                        String s = out.get(out.size()-1);
                        out.set(out.size()-1, s.substring(0, s.length()-6));
                    }
                }
            }
        }
        return out;
    }

    private String readTypeClass(AnnotationMirror am, String name) {
        if (am == null) return null;
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : am.getElementValues().entrySet()) {
            if (!e.getKey().getSimpleName().contentEquals(name)) continue;
            String s = String.valueOf(e.getValue().getValue());
            if (s.endsWith(".class")) return s.substring(0, s.length()-6);
            return s;
        }
        return null;
    }

    private void error(String fmt, Object... args) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format(java.util.Locale.ROOT, fmt, args));
    }
}
