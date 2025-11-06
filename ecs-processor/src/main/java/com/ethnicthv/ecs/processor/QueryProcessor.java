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
        boolean isPrivate = method.getModifiers().contains(Modifier.PRIVATE);
        String methodName = method.getSimpleName().toString();

        // Detect @Id field on owner (at most one, must be int)
        String idFieldName = null;
        for (Element e : owner.getEnclosedElements()) {
            if (e.getKind() != ElementKind.FIELD) continue;
            boolean hasId = false;
            for (AnnotationMirror am : e.getAnnotationMirrors()) {
                Element ae = am.getAnnotationType().asElement();
                if (ae instanceof TypeElement te && te.getQualifiedName().contentEquals("com.ethnicthv.ecs.core.system.annotation.Id")) {
                    hasId = true; break;
                }
            }
            if (!hasId) continue;
            if (idFieldName != null) { error("Only one @Id field is allowed per @Query method owner %s", owner.getQualifiedName()); break; }
            // Type must be primitive int
            String ft = e.asType().toString();
            if (!"int".equals(ft)) { error("@Id field must be of type int: %s.%s", owner.getQualifiedName(), e.getSimpleName()); break; }
            idFieldName = e.getSimpleName().toString();
        }
        boolean hasIdField = (idFieldName != null);

        // Detect @Id parameter (at most one; type must be int or java.lang.Integer)
        int idParamIndex = -1;
        List<? extends VariableElement> methodParams = method.getParameters();
        for (int i = 0; i < methodParams.size(); i++) {
            VariableElement p = methodParams.get(i);
            AnnotationMirror idAnno = getAnnotation(p, "com.ethnicthv.ecs.core.system.annotation.Id");
            if (idAnno == null) continue;
            if (idParamIndex != -1) { error("Only one @Id parameter is allowed on method %s", method); break; }
            String dt = p.asType().toString();
            if (!"int".equals(dt) && !"java.lang.Integer".equals(dt)) { error("@Id parameter must be int or java.lang.Integer on method %s", method); break; }
            idParamIndex = i;
        }

        // Collect parameter component classes, skipping @Id parameter
        List<String> paramComponentClasses = new ArrayList<>();
        List<String> paramDeclaredTypes = new ArrayList<>();
        List<Integer> logicalToPhysical = new ArrayList<>(); // map logical comp param index -> physical method index
        List<String> expectedHandleClasses = new ArrayList<>();
        List<String> directBinderNames = new ArrayList<>();
        for (int phys = 0; phys < methodParams.size(); phys++) {
            if (phys == idParamIndex) continue; // skip @Id param from component processing
            VariableElement p = methodParams.get(phys);
            AnnotationMirror compAnno = getAnnotation(p, "com.ethnicthv.ecs.core.system.annotation.Component");
            String declaredType = p.asType().toString();
            String compTypeFqn;
            if (compAnno != null) {
                compTypeFqn = readTypeClass(compAnno, "type");
                if (compTypeFqn == null || compTypeFqn.isEmpty()) { error("@Component on %s must specify type()", p); continue; }
            } else {
                if ("com.ethnicthv.ecs.core.components.ComponentHandle".equals(declaredType)) { error("Parameter %s must be annotated with @Component(type=...) to specify component type", p); continue; }
                if (declaredType.endsWith("Handle")) {
                    String base = declaredType.substring(0, declaredType.length() - "Handle".length());
                    compTypeFqn = (elementUtils.getTypeElement(base) != null) ? base : declaredType;
                } else compTypeFqn = declaredType;
            }
            paramComponentClasses.add(compTypeFqn);
            paramDeclaredTypes.add(declaredType);
            logicalToPhysical.add(phys);
            int lastDot = compTypeFqn.lastIndexOf('.');
            String compPkg = (lastDot >= 0) ? compTypeFqn.substring(0, lastDot) : "";
            String compSimple = (lastDot >= 0) ? compTypeFqn.substring(lastDot+1) : compTypeFqn;
            String handleFqn = (compPkg.isEmpty() ? compSimple + "Handle" : compPkg + "." + compSimple + "Handle");
            expectedHandleClasses.add(handleFqn);
            String binderName = null;
            if (!"com.ethnicthv.ecs.core.components.ComponentHandle".equals(declaredType)) {
                TypeElement declEl = elementUtils.getTypeElement(declaredType);
                if (declEl != null) binderName = findPublicBinderMethodName(declEl);
            }
            directBinderNames.add(binderName);
        }

        // Read query filters (with/without/any) and mode
        AnnotationMirror q = getAnnotation(method, "com.ethnicthv.ecs.core.system.annotation.Query");
        List<String> withClasses = readTypeArray(q, "with");
        List<String> withoutClasses = readTypeArray(q, "without");
        List<String> anyClasses = readTypeArray(q, "any");
        String modeConst = readEnumConst(q, "mode");
        boolean isParallel = "PARALLEL".equals(modeConst);

        JavaFileObject file = processingEnv.getFiler().createSourceFile(fqn, owner);
        try (Writer w = file.openWriter()) {
            if (!pkg.isEmpty()) w.write("package " + pkg + ";\n\n");
            w.write("@SuppressWarnings(\"all\")\n");
            w.write("public final class " + name + " implements com.ethnicthv.ecs.core.api.archetype.IQuery {\n");
            // Fields
            w.write("  private final com.ethnicthv.ecs.core.archetype.ArchetypeWorld world;\n");
            w.write("  private final " + ownerQualified + " system;\n");
            if (isPrivate) {
                w.write("  private final java.lang.invoke.MethodHandle invExact;\n");
            }
            // Emit ID field support
            w.write("  private static final boolean HAS_ID_FIELD = " + (hasIdField ? "true" : "false") + ";\n");
            w.write("  private final java.lang.reflect.Field ID_FIELD;\n");
            w.write("  private static final boolean MODE_PARALLEL = " + (isParallel ? "true" : "false") + ";\n");
            // Param classes & flags
            w.write("  private static final Class<?>[] PARAM_CLASSES = new Class<?>[]{");
            for (int i = 0; i < paramComponentClasses.size(); i++) {
                w.write(paramComponentClasses.get(i) + ".class");
                if (i < paramComponentClasses.size()-1) w.write(", ");
            }
            w.write("};\n");
            w.write("  private static final int PARAM_COUNT = PARAM_CLASSES.length;\n");
            // Declared parameter types for MH signature
            w.write("  private static final Class<?>[] DECLARED_PARAM_TYPES = new Class<?>[]{");
            for (int i = 0; i < paramDeclaredTypes.size(); i++) {
                w.write(paramDeclaredTypes.get(i) + ".class");
                if (i < paramDeclaredTypes.size()-1) w.write(", ");
            }
            w.write("};\n");
            // Expected generated handle class names
            w.write("  private static final String[] HANDLE_CLASS_NAMES = new String[]{");
            for (int i = 0; i < expectedHandleClasses.size(); i++) {
                w.write("\"" + expectedHandleClasses.get(i) + "\"");
                if (i < expectedHandleClasses.size()-1) w.write(", ");
            }
            w.write("};\n");
            // Filter class arrays
            w.write("  private static final Class<?>[] WITH_CLASSES = new Class<?>[]{");
            for (int i = 0; i < withClasses.size(); i++) { w.write(withClasses.get(i) + ".class"); if (i < withClasses.size()-1) w.write(","); }
            w.write("};\n");
            w.write("  private static final Class<?>[] WITHOUT_CLASSES = new Class<?>[]{");
            for (int i = 0; i < withoutClasses.size(); i++) { w.write(withoutClasses.get(i) + ".class"); if (i < withoutClasses.size()-1) w.write(","); }
            w.write("};\n");
            w.write("  private static final Class<?>[] ANY_CLASSES = new Class<?>[]{");
            for (int i = 0; i < anyClasses.size(); i++) { w.write(anyClasses.get(i) + ".class"); if (i < anyClasses.size()-1) w.write(","); }
            w.write("};\n");

            // Cached ids and masks
            w.write("  private final int[] paramIds;\n");
            w.write("  private final int[] withIds;\n");
            w.write("  private final int[] withoutIds;\n");
            w.write("  private final int[] anyIds;\n");
            w.write("  private final com.ethnicthv.ecs.core.archetype.ComponentMask withMask;\n");
            w.write("  private final com.ethnicthv.ecs.core.archetype.ComponentMask withoutMask;\n");
            w.write("  private final com.ethnicthv.ecs.core.archetype.ComponentMask anyMask;\n");
            // Managed/unmanaged decision
            w.write("  private final boolean[] PARAM_IS_MANAGED;\n");
            w.write("  private final java.lang.invoke.MethodHandle[] BINDER_MH;\n");
            w.write("  private final com.ethnicthv.ecs.core.components.ComponentDescriptor[] PARAM_DESCRIPTORS;\n");
            // Direct binder availability
            w.write("  private static final boolean[] DIRECT_BIND = new boolean[]{");
            for (int i = 0; i < directBinderNames.size(); i++) { w.write(directBinderNames.get(i) != null ? "true" : "false"); if (i < directBinderNames.size()-1) w.write(", "); }
            w.write("};\n");
            w.write("  private static final String[] DIRECT_BIND_NAME = new String[]{");
            for (int i = 0; i < directBinderNames.size(); i++) { String n = directBinderNames.get(i); w.write(n == null ? "null" : ("\"" + n + "\"")); if (i < directBinderNames.size()-1) w.write(", "); }
            w.write("};\n");
            // Plan cache to avoid recomputing indices per archetype
            w.write("  private static final class ArchetypePlan { final int[] compIdx; final int[] managedIdx; ArchetypePlan(int[] a, int[] b){ this.compIdx=a; this.managedIdx=b; } }\n");
            w.write("  private final java.util.concurrent.ConcurrentHashMap<com.ethnicthv.ecs.core.archetype.Archetype, ArchetypePlan> PLAN_CACHE = new java.util.concurrent.ConcurrentHashMap<>();\n");
            // Strategy enum and array
            w.write("  private enum ParamStrategy { MANAGED, UNMANAGED_RAW, UNMANAGED_TYPED }\n");
            w.write("  private final ParamStrategy[] STRATEGY = new ParamStrategy[PARAM_COUNT];\n");
            // Typed handle reuse pools
            w.write("  private final java.lang.reflect.Constructor<?>[] TYPED_CTORS = new java.lang.reflect.Constructor<?>[PARAM_COUNT];\n");
            w.write("  private final Object[] SEQ_TYPED = new Object[PARAM_COUNT];\n");
            w.write("  private final ThreadLocal<Object[]> PAR_TYPED = ThreadLocal.withInitial(() -> { Object[] a = new Object[PARAM_COUNT]; try { for (int i = 0; i < PARAM_COUNT; i++) { if (STRATEGY[i] == ParamStrategy.UNMANAGED_TYPED) { a[i] = TYPED_CTORS[i].newInstance(); } } } catch (Throwable t) { throw new RuntimeException(t); } return a; });\n");

            // Constructor
            w.write("  public " + name + "(com.ethnicthv.ecs.core.archetype.ArchetypeWorld w, " + ownerQualified + " s){\n");
            if (isPrivate) {
                w.write("    this.world=w; this.system=s; this.invExact = createMH();\n");
            } else {
                w.write("    this.world=w; this.system=s;\n");
            }
            // Initialize ID_FIELD
            if (hasIdField) {
                w.write("    try { java.lang.reflect.Field __idf = " + ownerQualified + ".class.getDeclaredField(\"" + idFieldName + "\"); __idf.setAccessible(true); this.ID_FIELD = __idf; } catch (Throwable t) { throw new RuntimeException(t); }\n");
            } else {
                w.write("    this.ID_FIELD = null;\n");
            }
            // compute ids and masks once
            w.write("    this.paramIds = new int[PARAM_COUNT];\n");
            w.write("    for (int i = 0; i < PARAM_COUNT; i++) { Integer id = world.getComponentTypeId(PARAM_CLASSES[i]); if (id == null) throw new IllegalStateException(\"Component not registered: \" + PARAM_CLASSES[i]); this.paramIds[i] = id; }\n");
            w.write("    java.util.ArrayList<Integer> wl = new java.util.ArrayList<>(); for (Class<?> c : WITH_CLASSES) { Integer id = world.getComponentTypeId(c); if (id != null) wl.add(id); } this.withIds = wl.stream().mapToInt(Integer::intValue).toArray();\n");
            w.write("    java.util.ArrayList<Integer> wtl = new java.util.ArrayList<>(); for (Class<?> c : WITHOUT_CLASSES) { Integer id = world.getComponentTypeId(c); if (id != null) wtl.add(id); } this.withoutIds = wtl.stream().mapToInt(Integer::intValue).toArray();\n");
            w.write("    java.util.ArrayList<Integer> al = new java.util.ArrayList<>(); for (Class<?> c : ANY_CLASSES) { Integer id = world.getComponentTypeId(c); if (id != null) al.add(id); } this.anyIds = al.stream().mapToInt(Integer::intValue).toArray();\n");
            // Build masks; always include parameters in WITH to guarantee presence
            w.write("    com.ethnicthv.ecs.core.archetype.ComponentMask.Builder wb = com.ethnicthv.ecs.core.archetype.ComponentMask.builder(); for (int id : this.withIds) wb.with(id); for (int id : this.paramIds) wb.with(id); this.withMask = wb.build();\n");
            w.write("    com.ethnicthv.ecs.core.archetype.ComponentMask.Builder woutb = com.ethnicthv.ecs.core.archetype.ComponentMask.builder(); for (int id : this.withoutIds) woutb.with(id); this.withoutMask = woutb.build();\n");
            w.write("    com.ethnicthv.ecs.core.archetype.ComponentMask.Builder ab = com.ethnicthv.ecs.core.archetype.ComponentMask.builder(); for (int id : this.anyIds) ab.with(id); this.anyMask = ab.build();\n");
            // Determine param kinds and prepare binders using annotation-derived managed/unmanaged + declared type
            w.write("    this.PARAM_IS_MANAGED = new boolean[PARAM_COUNT];\n");
            w.write("    this.BINDER_MH = new java.lang.invoke.MethodHandle[PARAM_COUNT];\n");
            w.write("    this.PARAM_DESCRIPTORS = new com.ethnicthv.ecs.core.components.ComponentDescriptor[PARAM_COUNT];\n");
            w.write("    final var __cmgr = world.getComponentManager();\n");
            w.write("    final var __lookup = java.lang.invoke.MethodHandles.lookup();\n");
            w.write("    for (int i = 0; i < PARAM_COUNT; i++) {\n");
            w.write("      var __desc = __cmgr.getDescriptor(PARAM_CLASSES[i]); if (__desc == null) throw new IllegalStateException(\"Descriptor not found for component: \" + PARAM_CLASSES[i].getName());\n");
            w.write("      this.PARAM_IS_MANAGED[i] = __desc.isManaged();\n");
            w.write("      this.PARAM_DESCRIPTORS[i] = __desc;\n");
            w.write("      boolean declaredIsRawHandle = (DECLARED_PARAM_TYPES[i] == com.ethnicthv.ecs.core.components.ComponentHandle.class);\n");
            w.write("      boolean declaredIsGeneratedHandle = DECLARED_PARAM_TYPES[i].getName().equals(HANDLE_CLASS_NAMES[i]);\n");
            w.write("      if (this.PARAM_IS_MANAGED[i]) {\n");
            w.write("        this.STRATEGY[i] = ParamStrategy.MANAGED;\n");
            w.write("      } else {\n");
            w.write("        if (declaredIsRawHandle) { this.STRATEGY[i] = ParamStrategy.UNMANAGED_RAW; }\n");
            w.write("        else if (declaredIsGeneratedHandle || DIRECT_BIND[i]) { this.STRATEGY[i] = ParamStrategy.UNMANAGED_TYPED; }\n");
            w.write("        else { throw new IllegalStateException(\"Parameter type \" + DECLARED_PARAM_TYPES[i].getName() + \" is invalid for unmanaged component \" + PARAM_CLASSES[i].getName() + \". Use ComponentHandle or the generated Handle class.\"); }\n");
            w.write("      }\n");
            // Prepare MethodHandle binder only if needed and not direct
            w.write("      if (!this.PARAM_IS_MANAGED[i] && this.STRATEGY[i] == ParamStrategy.UNMANAGED_TYPED && !DIRECT_BIND[i]) {\n");
            w.write("        try {\n");
            w.write("          var __prv = java.lang.invoke.MethodHandles.privateLookupIn(DECLARED_PARAM_TYPES[i], __lookup);\n");
            w.write("          java.lang.invoke.MethodHandle __bh = null;\n");
            w.write("          try { __bh = __prv.findVirtual(DECLARED_PARAM_TYPES[i], \"__bind\", java.lang.invoke.MethodType.methodType(void.class, com.ethnicthv.ecs.core.components.ComponentHandle.class)); } catch (Throwable ignore) {}\n");
            w.write("          if (__bh == null) { try { __bh = __prv.findVirtual(DECLARED_PARAM_TYPES[i], \"bind\", java.lang.invoke.MethodType.methodType(void.class, com.ethnicthv.ecs.core.components.ComponentHandle.class)); } catch (Throwable ignore) {} }\n");
            w.write("          if (__bh == null) { try { __bh = __prv.findVirtual(DECLARED_PARAM_TYPES[i], \"reset\", java.lang.invoke.MethodType.methodType(void.class, com.ethnicthv.ecs.core.components.ComponentHandle.class)); } catch (Throwable ignore) {} }\n");
            w.write("          if (__bh == null) throw new IllegalStateException(\"Typed handle class \" + DECLARED_PARAM_TYPES[i].getName() + \" must declare __bind/ bind/ or reset method with (ComponentHandle)\");\n");
            w.write("          this.BINDER_MH[i] = __bh;\n");
            w.write("        } catch (Throwable t) { throw new IllegalStateException(\"Failed to prepare typed handle for parameter \" + i + \" of type \" + DECLARED_PARAM_TYPES[i].getName(), t); }\n");
            w.write("      }\n");
            w.write("    }\n");
            // Initialize typed constructors and instances outside loops
            w.write("    try { for (int i = 0; i < PARAM_COUNT; i++) { if (STRATEGY[i] == ParamStrategy.UNMANAGED_TYPED) { TYPED_CTORS[i] = DECLARED_PARAM_TYPES[i].getDeclaredConstructor(); TYPED_CTORS[i].setAccessible(true); SEQ_TYPED[i] = TYPED_CTORS[i].newInstance(); } } } catch (Throwable t) { throw new RuntimeException(t); }\n");
            w.write("    validateConfig();\n");
            w.write("  }\n");

            if (isPrivate) {
                w.write("  private java.lang.invoke.MethodHandle createMH(){\n");
                w.write("    try {\n");
                w.write("      var lookup = java.lang.invoke.MethodHandles.lookup();\n");
                w.write("      var prv = java.lang.invoke.MethodHandles.privateLookupIn(" + ownerQualified + ".class, lookup);\n");
                // Build method type for ALL method parameters (including @Id)
                w.write("      var mt = java.lang.invoke.MethodType.methodType(void.class");
                for (VariableElement methodParam : methodParams) {
                    String t = methodParam.asType().toString();
                    if ("int".equals(t)) {
                        w.write(", int.class");
                    } else {
                        w.write(", " + t + ".class");
                    }
                }
                w.write(");\n");
                w.write("      return prv.findVirtual(" + ownerQualified + ".class, \"" + methodName + "\", mt);\n");
                w.write("    } catch (Throwable t) { throw new RuntimeException(t); }\n");
                w.write("  }\n");
            }

            // validate config
            w.write("  private void validateConfig(){\n");
            w.write("    for (int i = 0; i < paramIds.length; i++) {\n");
            w.write("      boolean isManaged = PARAM_IS_MANAGED[i];\n");
            w.write("      if (isManaged) {\n");
            w.write("        if (DECLARED_PARAM_TYPES[i] != PARAM_CLASSES[i]) { throw new IllegalStateException(\"Parameter type mismatch: component \" + PARAM_CLASSES[i].getName() + \" is @Managed, method parameter must be that component type\"); }\n");
            w.write("      } else {\n");
            w.write("        if (DECLARED_PARAM_TYPES[i] == PARAM_CLASSES[i]) { throw new IllegalStateException(\"Parameter \" + i + \" declared as managed object (\" + PARAM_CLASSES[i].getName() + \") but component is unmanaged. Use ComponentHandle or the generated Handle class.\"); }\n");
            w.write("      }\n");
            w.write("    }\n");
            w.write("    if (HAS_ID_FIELD && MODE_PARALLEL) { throw new IllegalStateException(\"@Id field is not supported with PARALLEL Query mode; use SEQUENTIAL.\"); }\n");
            w.write("  }\n");

            w.write("  private ArchetypePlan buildPlan(com.ethnicthv.ecs.core.archetype.Archetype archetype){\n");
            w.write("    final int[] compIdx = new int[PARAM_COUNT]; final int[] managedIdx = new int[PARAM_COUNT]; boolean ok = true;\n");
            w.write("    for (int i = 0; i < PARAM_COUNT; i++) { int tid = paramIds[i]; if (!PARAM_IS_MANAGED[i]) { compIdx[i] = archetype.indexOfComponentType(tid); if (compIdx[i] < 0) { ok=false; break; } managedIdx[i] = -1; } else { managedIdx[i] = archetype.getManagedTypeIndex(tid); if (managedIdx[i] < 0) { ok=false; break; } compIdx[i] = -1; } }\n");
            w.write("    return ok ? new ArchetypePlan(compIdx, managedIdx) : null;\n");
            w.write("  }\n");
            w.write("  @Override public void runQuery(){ if (MODE_PARALLEL) runParallel(); else runSequential(); }\n");

            // Sequential execution
            w.write("  private void runSequential(){\n");
            w.write("    final var cm = world.getComponentManager();\n");
            w.write("    for (com.ethnicthv.ecs.core.archetype.Archetype archetype : world.getAllArchetypes()) {\n");
            w.write("      com.ethnicthv.ecs.core.archetype.ComponentMask am = archetype.getMask(); if (!am.containsAll(withMask)) continue; if (!am.containsNone(withoutMask)) continue; if (anyIds.length > 0 && !am.intersects(anyMask)) continue;\n");
            w.write("      ArchetypePlan plan = PLAN_CACHE.computeIfAbsent(archetype, this::buildPlan); if (plan == null) continue;\n");
            w.write("      final com.ethnicthv.ecs.core.components.ComponentHandle[] pooled = new com.ethnicthv.ecs.core.components.ComponentHandle[PARAM_COUNT];\n");
            w.write("      for (int i = 0; i < PARAM_COUNT; i++) { if (!PARAM_IS_MANAGED[i]) pooled[i] = cm.acquireHandle(); }\n");
            w.write("      final Object[] typed = SEQ_TYPED;\n");
            w.write("      try {\n");
            w.write("        archetype.forEach((entityId, location, chunk) -> {\n");
            w.write("          try {\n");
            // Set @Id field if present
            w.write("            if (HAS_ID_FIELD) { try { ID_FIELD.setInt(system, entityId); } catch (Throwable t) { throw new RuntimeException(t); } }\n");
            w.write("            for (int k = 0; k < PARAM_COUNT; k++) { if (STRATEGY[k] != ParamStrategy.MANAGED) { var seg = chunk.getComponentData(plan.compIdx[k], location.indexInChunk); pooled[k].reset(seg, PARAM_DESCRIPTORS[k]); } }\n");
            StringBuilder bindsSeq = new StringBuilder();
            for (int i = 0; i < paramDeclaredTypes.size(); i++) {
                String decl = paramDeclaredTypes.get(i);
                bindsSeq.append("            if (STRATEGY[").append(i).append("] == ParamStrategy.UNMANAGED_TYPED) { ");
                if (directBinderNames.get(i) != null) {
                    bindsSeq.append("((").append(decl).append(") typed[").append(i).append("]).").append(directBinderNames.get(i)).append("((com.ethnicthv.ecs.core.components.ComponentHandle) pooled[").append(i).append("]);\n");
                } else {
                    bindsSeq.append("try { BINDER_MH[").append(i).append("].invoke(typed[").append(i).append("], pooled[").append(i).append("]); } catch (Throwable __t) { throw new RuntimeException(__t); }\n");
                }
                bindsSeq.append("            }\n");
            }
            w.write(bindsSeq.toString());
            StringBuilder prepArgsSeq = new StringBuilder();
            for (int i = 0; i < paramDeclaredTypes.size(); i++) {
                String dt = paramDeclaredTypes.get(i);
                prepArgsSeq.append("            final Object __argObj_").append(i).append(" = ");
                prepArgsSeq.append("(STRATEGY[").append(i).append("] == ParamStrategy.MANAGED) ? world.getManagedComponent(entityId, PARAM_CLASSES[").append(i).append("]) : ");
                prepArgsSeq.append("(STRATEGY[").append(i).append("] == ParamStrategy.UNMANAGED_RAW) ? pooled[").append(i).append("] : typed[").append(i).append("];\n");
                prepArgsSeq.append("            final ").append(dt).append(" a").append(i).append(" = (").append(dt).append(") __argObj_").append(i).append(";\n");
            }
            w.write(prepArgsSeq.toString());
            // Build direct call with proper parameter ordering (including @Id)
            StringBuilder callSeq = new StringBuilder();
            if (isPrivate) {
                callSeq.append("            try { invExact.invokeExact(system");
                for (int i = 0; i < methodParams.size(); i++) {
                    String t = methodParams.get(i).asType().toString();
                    if (i == idParamIndex) {
                        // @Id param: entityId as int or boxed
                        if ("int".equals(t)) callSeq.append(", entityId");
                        else callSeq.append(", java.lang.Integer.valueOf(entityId)");
                    } else {
                        // find logical index that maps to this physical index
                        int li = -1; for (int k = 0; k < logicalToPhysical.size(); k++) { if (logicalToPhysical.get(k) == i) { li = k; break; } }
                        callSeq.append(", a").append(li);
                    }
                }
                callSeq.append("); } catch (Throwable t) { throw new RuntimeException(t); }\n");
            } else {
                callSeq.append("            system.").append(methodName).append("(");
                for (int i = 0; i < methodParams.size(); i++) {
                    if (i > 0) callSeq.append(", ");
                    String t = methodParams.get(i).asType().toString();
                    if (i == idParamIndex) {
                        if ("int".equals(t)) callSeq.append("entityId"); else callSeq.append("java.lang.Integer.valueOf(entityId)");
                    } else {
                        int li = -1; for (int k = 0; k < logicalToPhysical.size(); k++) { if (logicalToPhysical.get(k) == i) { li = k; break; } }
                        callSeq.append("a").append(li);
                    }
                }
                callSeq.append(");\n");
            }
            w.write(callSeq.toString());
            w.write("          } catch (Throwable tt) { throw new RuntimeException(tt); }\n");
            w.write("        });\n");
            w.write("      } finally { for (int i = 0; i < PARAM_COUNT; i++) { if (pooled[i] != null) cm.releaseHandle(pooled[i]); } }\n");
            w.write("    }\n");
            w.write("  }\n");
            // Parallel execution
            w.write("  private void runParallel(){\n");
            w.write("    final var cm = world.getComponentManager();\n");
            w.write("    for (com.ethnicthv.ecs.core.archetype.Archetype archetype : world.getAllArchetypes()) {\n");
            w.write("      com.ethnicthv.ecs.core.archetype.ComponentMask am = archetype.getMask(); if (!am.containsAll(withMask)) continue; if (!am.containsNone(withoutMask)) continue; if (anyIds.length > 0 && !am.intersects(anyMask)) continue;\n");
            w.write("      final ArchetypePlan plan = PLAN_CACHE.computeIfAbsent(archetype, this::buildPlan); if (plan == null) continue;\n");
            w.write("      final com.ethnicthv.ecs.core.archetype.ArchetypeChunk[] chunks = archetype.getChunksSnapshot(); final int count = archetype.chunkCount();\n");
            w.write("      java.util.Arrays.stream(chunks, 0, count).parallel().forEach(chunk -> {\n");
            w.write("        final com.ethnicthv.ecs.core.components.ComponentHandle[] pooled = new com.ethnicthv.ecs.core.components.ComponentHandle[PARAM_COUNT]; for (int i = 0; i < PARAM_COUNT; i++) { if (!PARAM_IS_MANAGED[i]) pooled[i] = cm.acquireHandle(); }\n");
            w.write("        final Object[] typed = PAR_TYPED.get();\n");
            w.write("        try {\n");
            w.write("          int idx = chunk.nextOccupiedIndex(0); while (idx >= 0) { int eid = chunk.getEntityId(idx);\n");
            w.write("            for (int k = 0; k < PARAM_COUNT; k++) { if (STRATEGY[k] != ParamStrategy.MANAGED) { var seg = chunk.getComponentData(plan.compIdx[k], idx); pooled[k].reset(seg, PARAM_DESCRIPTORS[k]); } }\n");
            StringBuilder bindsPar = new StringBuilder();
            for (int i = 0; i < paramDeclaredTypes.size(); i++) {
                String decl = paramDeclaredTypes.get(i);
                bindsPar.append("            if (STRATEGY[").append(i).append("] == ParamStrategy.UNMANAGED_TYPED) { ");
                if (directBinderNames.get(i) != null) {
                    bindsPar.append("((").append(decl).append(") typed[").append(i).append("]).").append(directBinderNames.get(i)).append("((com.ethnicthv.ecs.core.components.ComponentHandle) pooled[").append(i).append("]);\n");
                } else {
                    bindsPar.append("try { BINDER_MH[").append(i).append("].invoke(typed[").append(i).append("], pooled[").append(i).append("]); } catch (Throwable __t) { throw new RuntimeException(__t); }\n");
                }
                bindsPar.append("            }\n");
            }
            w.write(bindsPar.toString());
            StringBuilder prepArgsPar = new StringBuilder();
            for (int i = 0; i < paramDeclaredTypes.size(); i++) {
                String dt = paramDeclaredTypes.get(i);
                prepArgsPar.append("            final Object __argObj_").append(i).append(" = ");
                prepArgsPar.append("(STRATEGY[").append(i).append("] == ParamStrategy.MANAGED) ? world.getManagedComponent(eid, PARAM_CLASSES[").append(i).append("]) : ");
                prepArgsPar.append("(STRATEGY[").append(i).append("] == ParamStrategy.UNMANAGED_RAW) ? pooled[").append(i).append("] : typed[").append(i).append("];\n");
                prepArgsPar.append("            final ").append(dt).append(" a").append(i).append(" = (").append(dt).append(") __argObj_").append(i).append(";\n");
            }
            w.write(prepArgsPar.toString());
            // Build direct call for parallel path (use eid for entity id)
            StringBuilder callPar = new StringBuilder();
            if (isPrivate) {
                callPar.append("            try { invExact.invokeExact(system");
                for (int i = 0; i < methodParams.size(); i++) {
                    String t = methodParams.get(i).asType().toString();
                    if (i == idParamIndex) {
                        if ("int".equals(t)) callPar.append(", eid"); else callPar.append(", java.lang.Integer.valueOf(eid)");
                    } else {
                        int li = -1; for (int k = 0; k < logicalToPhysical.size(); k++) { if (logicalToPhysical.get(k) == i) { li = k; break; } }
                        callPar.append(", a").append(li);
                    }
                }
                callPar.append("); } catch (Throwable t) { throw new RuntimeException(t); }\n");
            } else {
                callPar.append("            system.").append(methodName).append("(");
                for (int i = 0; i < methodParams.size(); i++) {
                    if (i > 0) callPar.append(", ");
                    String t = methodParams.get(i).asType().toString();
                    if (i == idParamIndex) {
                        if ("int".equals(t)) callPar.append("eid"); else callPar.append("java.lang.Integer.valueOf(eid)");
                    } else {
                        int li = -1; for (int k = 0; k < logicalToPhysical.size(); k++) { if (logicalToPhysical.get(k) == i) { li = k; break; } }
                        callPar.append("a").append(li);
                    }
                }
                callPar.append(");\n");
            }
            w.write(callPar.toString());
            w.write("            idx = chunk.nextOccupiedIndex(idx + 1); }\n");
            w.write("        } finally { for (int i = 0; i < PARAM_COUNT; i++) { if (pooled[i] != null) cm.releaseHandle(pooled[i]); } }\n");
            w.write("      });\n");
            w.write("    }\n");
            w.write("  }\n");
            w.write("}\n");
        }
    }

    private String findPublicBinderMethodName(TypeElement typeEl) {
        for (Element e : typeEl.getEnclosedElements()) {
            if (e.getKind() == ElementKind.METHOD) {
                ExecutableElement m = (ExecutableElement) e;
                String n = m.getSimpleName().toString();
                if (!n.equals("__bind") && !n.equals("bind") && !n.equals("reset")) continue;
                if (!m.getModifiers().contains(Modifier.PUBLIC)) continue;
                List<? extends VariableElement> params = m.getParameters();
                if (params.size() != 1) continue;
                String p0 = params.getFirst().asType().toString();
                if ("com.ethnicthv.ecs.core.components.ComponentHandle".equals(p0)) return n;
            }
        }
        return null;
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
                    out.add(ev.toString());
                    if (out.getLast().endsWith(".class")) {
                        String s = out.getLast();
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

    private String readEnumConst(AnnotationMirror am, String name) {
        if (am == null) return null;
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : am.getElementValues().entrySet()) {
            if (!e.getKey().getSimpleName().contentEquals(name)) continue;
            Object v = e.getValue().getValue();
            // v is a VariableElement representing enum constant
            return v.toString().substring(v.toString().lastIndexOf('.') + 1);
        }
        return null;
    }

    private void error(String fmt, Object... args) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format(java.util.Locale.ROOT, fmt, args));
    }
}
