package com.ethnicthv.ecs.processor;

import com.google.auto.service.AutoService;

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
@AutoService(Processor.class)
public class QueryProcessor extends BaseProcessor {
    // ---------------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------------
    private static final String ANNO_QUERY = "com.ethnicthv.ecs.core.system.annotation.Query";
    private static final String ANNO_ID = "com.ethnicthv.ecs.core.system.annotation.Id";
    private static final String ANNO_COMPONENT = "com.ethnicthv.ecs.core.system.annotation.Component";

    private Elements elementUtils;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.elementUtils = processingEnv.getElementUtils();
        note("QueryProcessor init");
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        TypeElement queryAnno = elementUtils.getTypeElement(ANNO_QUERY);
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

    // ---------------------------------------------------------------------
    // Code generation: Injector
    // ---------------------------------------------------------------------
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
                AnnotationMirror q = BaseProcessor.getAnnotation(m, ANNO_QUERY);
                String fieldInject = BaseProcessor.readString(q, "fieldInject");
                if (fieldInject == null || fieldInject.isEmpty()) {
                    error("@Query on %s missing fieldInject", m);
                    continue;
                }
                // Compile-time validation: ensure field exists and is typed as IGeneratedQuery
                VariableElement targetField = null;
                for (Element e : owner.getEnclosedElements()) {
                    if (e.getKind() == ElementKind.FIELD && e.getSimpleName().contentEquals(fieldInject)) {
                        targetField = (VariableElement) e; break;
                    }
                }
                if (targetField == null) {
                    error("Field '%s' referenced by fieldInject on method %s not found in %s", fieldInject, m.getSimpleName(), owner.getQualifiedName());
                    continue;
                }
                String fieldType = targetField.asType().toString();
                String requiredType = "com.ethnicthv.ecs.core.api.archetype.IGeneratedQuery";
                if (!fieldType.equals(requiredType)) {
                    error("Field '%s' must be of type %s (found %s) for @Query injection", fieldInject, requiredType, fieldType);
                    continue;
                }
                String runner = simple + "__" + m.getSimpleName() + "__QueryRunner";
                w.write("    try {\n");
                w.write("      java.lang.reflect.Field f = " + ownerQualified + ".class.getDeclaredField(\"" + fieldInject + "\");\n");
                w.write("      f.setAccessible(true);\n");
                w.write("      if(!com.ethnicthv.ecs.core.api.archetype.IGeneratedQuery.class.isAssignableFrom(f.getType())) throw new IllegalStateException(\"Field '" + fieldInject + "' must be assignable to IGeneratedQuery\");\n");
                w.write("      f.set(self, new " + runner + "(world, self));\n");
                w.write("    } catch (Throwable t) { throw new RuntimeException(t); }\n");
            }
            w.write("  }\n");
            w.write("}\n");
        }
    }

    // ---------------------------------------------------------------------
    // Method analysis structures
    // ---------------------------------------------------------------------
    private static final class ParamAnalysis {
        final List<String> componentClasses = new ArrayList<>();
        final List<String> declaredTypes = new ArrayList<>();
        final List<Integer> logicalToPhysical = new ArrayList<>();
        final List<String> expectedHandleClasses = new ArrayList<>();
        final List<String> directBinderNames = new ArrayList<>();
    }

    // ---------------------------------------------------------------------
    // Code generation: Runner per method
    // ---------------------------------------------------------------------
    private void generateRunner(TypeElement owner, ExecutableElement method) throws IOException {
        String pkg = elementUtils.getPackageOf(owner).getQualifiedName().toString();
        String simple = owner.getSimpleName().toString();
        String ownerQualified = owner.getQualifiedName().toString();
        String name = simple + "__" + method.getSimpleName() + "__QueryRunner";
        String fqn = pkg.isEmpty() ? name : pkg + "." + name;
        boolean isPrivate = method.getModifiers().contains(Modifier.PRIVATE);
        String methodName = method.getSimpleName().toString();

        // ID field detection
        String idFieldName = findIdFieldName(owner);
        boolean hasIdField = (idFieldName != null);

        // ID parameter detection
        int idParamIndex = findIdParamIndex(method);

        // Parameter component / type analysis
        ParamAnalysis pa = analyzeParameters(method, idParamIndex);

        AnnotationMirror q = BaseProcessor.getAnnotation(method, ANNO_QUERY);
        List<String> withClasses = BaseProcessor.readTypeArray(q, "with");
        List<String> withoutClasses = BaseProcessor.readTypeArray(q, "without");
        List<String> anyClasses = BaseProcessor.readTypeArray(q, "any");
        String modeConst = BaseProcessor.readEnumConst(q, "mode");
        boolean isParallel = "PARALLEL".equals(modeConst);

        JavaFileObject file = processingEnv.getFiler().createSourceFile(fqn, owner);
        try (Writer w = file.openWriter()) {
            if (!pkg.isEmpty()) w.write("package " + pkg + ";\n\n");
            w.write("@SuppressWarnings(\"all\")\n");
            w.write("public final class " + name + " implements com.ethnicthv.ecs.core.api.archetype.IQuery, com.ethnicthv.ecs.core.api.archetype.IQueryBuilder, com.ethnicthv.ecs.core.api.archetype.IGeneratedQuery {\n");
            // Fields
            w.write("  private final com.ethnicthv.ecs.core.archetype.ArchetypeWorld world;\n");
            w.write("  private final " + ownerQualified + " system;\n");
            if (isPrivate) w.write("  private final java.lang.invoke.MethodHandle invExact;\n");
            w.write("  private Object __managedSharedFilter = null;\n");
            w.write("  private java.util.List<com.ethnicthv.ecs.core.archetype.ArchetypeQuery.UnmanagedFilter> __unmanagedSharedFilters = null;\n");
            w.write("  private static final boolean HAS_ID_FIELD = " + (hasIdField ? "true" : "false") + ";\n");
            w.write("  private final java.lang.reflect.Field ID_FIELD;\n");
            w.write("  private static final boolean MODE_PARALLEL = " + (isParallel ? "true" : "false") + ";\n");
            // Param arrays
            w.write("  private static final Class<?>[] PARAM_CLASSES = new Class<?>[]{");
            for (int i = 0; i < pa.componentClasses.size(); i++) w.write(pa.componentClasses.get(i) + ".class" + (i < pa.componentClasses.size() - 1 ? ", " : ""));
            w.write("};\n");
            w.write("  private static final Class<?>[] DECLARED_PARAM_TYPES = new Class<?>[]{");
            for (int i = 0; i < pa.declaredTypes.size(); i++) w.write(pa.declaredTypes.get(i) + ".class" + (i < pa.declaredTypes.size() - 1 ? ", " : ""));
            w.write("};\n");
            w.write("  private static final String[] HANDLE_CLASS_NAMES = new String[]{");
            for (int i = 0; i < pa.expectedHandleClasses.size(); i++) w.write("\"" + pa.expectedHandleClasses.get(i) + "\"" + (i < pa.expectedHandleClasses.size() - 1 ? ", " : ""));
            w.write("};\n");
            w.write("  private static final int PARAM_COUNT = PARAM_CLASSES.length;\n");
            // Filters arrays
            writeClassArray(w, "WITH_CLASSES", withClasses);
            writeClassArray(w, "WITHOUT_CLASSES", withoutClasses);
            writeClassArray(w, "ANY_CLASSES", anyClasses);
            // IDs & masks + strategy arrays
            w.write("  private final int[] paramIds;\n");
            w.write("  private final int[] withIds;\n");
            w.write("  private final int[] withoutIds;\n");
            w.write("  private final int[] anyIds;\n");
            w.write("  private final com.ethnicthv.ecs.core.archetype.ComponentMask withMask;\n");
            w.write("  private final com.ethnicthv.ecs.core.archetype.ComponentMask withoutMask;\n");
            w.write("  private final com.ethnicthv.ecs.core.archetype.ComponentMask anyMask;\n");
            w.write("  private final boolean[] PARAM_IS_MANAGED;\n");
            w.write("  private final java.lang.invoke.MethodHandle[] BINDER_MH;\n");
            w.write("  private final com.ethnicthv.ecs.core.components.ComponentDescriptor[] PARAM_DESCRIPTORS;\n");
            w.write("  private static final boolean[] DIRECT_BIND = new boolean[]{");
            for (int i = 0; i < pa.directBinderNames.size(); i++) w.write((pa.directBinderNames.get(i) != null ? "true" : "false") + (i < pa.directBinderNames.size() - 1 ? ", " : ""));
            w.write("};\n");
            w.write("  private static final String[] DIRECT_BIND_NAME = new String[]{");
            for (int i = 0; i < pa.directBinderNames.size(); i++) { String n = pa.directBinderNames.get(i); w.write(n == null ? "null" : ("\"" + n + "\"")); if (i < pa.directBinderNames.size() - 1) w.write(", "); }
            w.write("};\n");
            w.write("  private static final class ArchetypePlan { final int[] compIdx; final int[] managedIdx; ArchetypePlan(int[] a, int[] b){ this.compIdx=a; this.managedIdx=b; } }\n");
            w.write("  private final java.util.concurrent.ConcurrentHashMap<com.ethnicthv.ecs.core.archetype.Archetype, ArchetypePlan> PLAN_CACHE = new java.util.concurrent.ConcurrentHashMap<>();\n");
            w.write("  private enum ParamStrategy { MANAGED, UNMANAGED_RAW, UNMANAGED_TYPED }\n");
            w.write("  private final ParamStrategy[] STRATEGY = new ParamStrategy[PARAM_COUNT];\n");
            w.write("  private final java.lang.reflect.Constructor<?>[] TYPED_CTORS = new java.lang.reflect.Constructor<?>[PARAM_COUNT];\n");
            w.write("  private final Object[] SEQ_TYPED = new Object[PARAM_COUNT];\n");
            w.write("  private final ThreadLocal<Object[]> PAR_TYPED = ThreadLocal.withInitial(() -> { Object[] a = new Object[PARAM_COUNT]; try { for (int i = 0; i < PARAM_COUNT; i++) { if (STRATEGY[i] == ParamStrategy.UNMANAGED_TYPED) { a[i] = TYPED_CTORS[i].newInstance(); } } } catch (Throwable t) { throw new RuntimeException(t); } return a; });\n");
            // Constructor
            w.write("  public " + name + "(com.ethnicthv.ecs.core.archetype.ArchetypeWorld w, " + ownerQualified + " s){\n");
            if (isPrivate) w.write("    this.world=w; this.system=s; this.invExact = createMH();\n"); else w.write("    this.world=w; this.system=s;\n");
            if (hasIdField) {
                w.write("    try { java.lang.reflect.Field __idf = " + ownerQualified + ".class.getDeclaredField(\"" + idFieldName + "\"); __idf.setAccessible(true); this.ID_FIELD = __idf; } catch (Throwable t) { throw new RuntimeException(t); }\n");
            } else w.write("    this.ID_FIELD = null;\n");
            w.write("    this.paramIds = new int[PARAM_COUNT]; for (int i = 0; i < PARAM_COUNT; i++) { Integer id = w.getComponentTypeId(PARAM_CLASSES[i]); if (id == null) throw new IllegalStateException(\"Component not registered: \" + PARAM_CLASSES[i]); this.paramIds[i] = id; }\n");
            w.write("    java.util.ArrayList<Integer> wl = new java.util.ArrayList<>(); for (Class<?> c : WITH_CLASSES) { Integer id = w.getComponentTypeId(c); if (id != null) wl.add(id); } this.withIds = wl.stream().mapToInt(Integer::intValue).toArray();\n");
            w.write("    java.util.ArrayList<Integer> wtl = new java.util.ArrayList<>(); for (Class<?> c : WITHOUT_CLASSES) { Integer id = w.getComponentTypeId(c); if (id != null) wtl.add(id); } this.withoutIds = wtl.stream().mapToInt(Integer::intValue).toArray();\n");
            w.write("    java.util.ArrayList<Integer> al = new java.util.ArrayList<>(); for (Class<?> c : ANY_CLASSES) { Integer id = w.getComponentTypeId(c); if (id != null) al.add(id); } this.anyIds = al.stream().mapToInt(Integer::intValue).toArray();\n");
            w.write("    com.ethnicthv.ecs.core.archetype.ComponentMask.Builder wb = com.ethnicthv.ecs.core.archetype.ComponentMask.builder(); for (int id : this.withIds) wb.with(id); for (int id : this.paramIds) wb.with(id); this.withMask = wb.build();\n");
            w.write("    com.ethnicthv.ecs.core.archetype.ComponentMask.Builder woutb = com.ethnicthv.ecs.core.archetype.ComponentMask.builder(); for (int id : this.withoutIds) woutb.with(id); this.withoutMask = woutb.build();\n");
            w.write("    com.ethnicthv.ecs.core.archetype.ComponentMask.Builder ab = com.ethnicthv.ecs.core.archetype.ComponentMask.builder(); for (int id : this.anyIds) ab.with(id); this.anyMask = ab.build();\n");
            w.write("    this.PARAM_IS_MANAGED = new boolean[PARAM_COUNT]; this.BINDER_MH = new java.lang.invoke.MethodHandle[PARAM_COUNT]; this.PARAM_DESCRIPTORS = new com.ethnicthv.ecs.core.components.ComponentDescriptor[PARAM_COUNT];\n");
            w.write("    final var __cmgr = w.getComponentManager(); final var __lookup = java.lang.invoke.MethodHandles.lookup();\n");
            w.write(buildParamStrategyBlock());
            w.write("    try { for (int i = 0; i < PARAM_COUNT; i++) { if (STRATEGY[i] == ParamStrategy.UNMANAGED_TYPED) { TYPED_CTORS[i] = DECLARED_PARAM_TYPES[i].getDeclaredConstructor(); TYPED_CTORS[i].setAccessible(true); SEQ_TYPED[i] = TYPED_CTORS[i].newInstance(); } } } catch (Throwable t) { throw new RuntimeException(t); }\n");
            // Defer config validation to run-time so system registration doesn't fail eagerly
            // w.write("    validateConfig();\n");
            w.write("  }\n");

            if (isPrivate) w.write(buildPrivateMethodHandleCreator(ownerQualified, methodName, method.getParameters()));

            w.write(buildValidateConfigBlock());
            w.write(buildPlanBuilderBlock());

            // IQueryBuilder implementations
            w.write("  @Override public com.ethnicthv.ecs.core.api.archetype.IQueryBuilder withShared(Object managedValue) { this.__managedSharedFilter = managedValue; return this; }\n");
            w.write("  @Override public com.ethnicthv.ecs.core.api.archetype.IQueryBuilder withShared(Class<?> unmanagedSharedType, long value) { if (this.__unmanagedSharedFilters == null) this.__unmanagedSharedFilters = new java.util.ArrayList<>(); this.__unmanagedSharedFilters.add(new com.ethnicthv.ecs.core.archetype.ArchetypeQuery.UnmanagedFilter(unmanagedSharedType, value)); return this; }\n");
            w.write("  @Override public <T> com.ethnicthv.ecs.core.api.archetype.IQueryBuilder with(Class<T> c) { return this; }\n");
            w.write("  @Override public <T> com.ethnicthv.ecs.core.api.archetype.IQueryBuilder without(Class<T> c) { return this; }\n");
            w.write("  @Override public com.ethnicthv.ecs.core.api.archetype.IQueryBuilder any(Class<?>... cs) { return this; }\n");
            w.write("  @Override public com.ethnicthv.ecs.core.api.archetype.IQuery build() { return this; }\n");

            // Shared value key builder
            w.write(buildSharedKeyBuilderBlock());

            // Binding/prep/call sequences
            String bindsSeq = buildBindSequence(pa.declaredTypes, pa.directBinderNames);
            String prepSeq = buildPrepSequence(pa.declaredTypes, "entityId");
            String prepPar = buildPrepSequence(pa.declaredTypes, "eid");
            String callSeq = buildCallSequence(isPrivate, method, idParamIndex, pa.logicalToPhysical, "entityId");
            String callPar = buildCallSequence(isPrivate, method, idParamIndex, pa.logicalToPhysical, "eid");

            // Chunk runners
            w.write(buildSequentialChunkRunner(hasIdField, bindsSeq, prepSeq, callSeq));
            w.write(buildParallelChunkRunner(bindsSeq, prepPar, callPar));
            // Group runners
            w.write("  private void runOnGroup_Sequential(com.ethnicthv.ecs.core.archetype.ChunkGroup group, ArchetypePlan plan, com.ethnicthv.ecs.core.components.ComponentManager cm){ com.ethnicthv.ecs.core.archetype.ArchetypeChunk[] chunks = group.getChunksSnapshot(); int count = group.chunkCount(); for (int i = 0; i < count; i++) runOnChunk_Sequential(chunks[i], plan, cm); }\n");
            w.write("  private void runOnGroup_Parallel(com.ethnicthv.ecs.core.archetype.ChunkGroup group, ArchetypePlan plan, com.ethnicthv.ecs.core.components.ComponentManager cm){ com.ethnicthv.ecs.core.archetype.ArchetypeChunk[] chunks = group.getChunksSnapshot(); int count = group.chunkCount(); java.util.Arrays.stream(chunks, 0, count).parallel().forEach(chunk -> runOnChunk_Parallel(chunk, plan, cm)); }\n");
            // Drivers
            w.write("  @Override public void runQuery(){ validateConfig(); if (MODE_PARALLEL) runParallel(); else runSequential(); this.__managedSharedFilter = null; if (this.__unmanagedSharedFilters != null) this.__unmanagedSharedFilters.clear(); }\n");
            w.write(buildRunSequentialBlock());
            w.write(buildRunParallelBlock());
            w.write("}\n");
        }
    }

    // ---------------------------------------------------------------------
    // Analysis helpers
    // ---------------------------------------------------------------------
    private String findIdFieldName(TypeElement owner) {
        String idFieldName = null;
        for (Element e : owner.getEnclosedElements()) {
            if (e.getKind() != ElementKind.FIELD) continue;
            if (BaseProcessor.hasAnnotation(e, ANNO_ID)) {
                if (idFieldName != null) { error("Only one @Id field is allowed per @Query method owner %s", owner.getQualifiedName()); break; }
                String ft = e.asType().toString();
                if (!"int".equals(ft)) { error("@Id field must be of type int: %s.%s", owner.getQualifiedName(), e.getSimpleName()); break; }
                idFieldName = e.getSimpleName().toString();
            }
        }
        return idFieldName;
    }

    private int findIdParamIndex(ExecutableElement method) {
        int idParamIndex = -1;
        List<? extends VariableElement> methodParams = method.getParameters();
        for (int i = 0; i < methodParams.size(); i++) {
            VariableElement p = methodParams.get(i);
            if (BaseProcessor.getAnnotation(p, ANNO_ID) != null) {
                if (idParamIndex != -1) { error("Only one @Id parameter is allowed on method %s", method); break; }
                String dt = p.asType().toString();
                if (!"int".equals(dt) && !"java.lang.Integer".equals(dt)) { error("@Id parameter must be int or java.lang.Integer on method %s", method); break; }
                idParamIndex = i;
            }
        }
        return idParamIndex;
    }

    private ParamAnalysis analyzeParameters(ExecutableElement method, int idParamIndex) {
        ParamAnalysis pa = new ParamAnalysis();
        List<? extends VariableElement> params = method.getParameters();
        for (int phys = 0; phys < params.size(); phys++) {
            if (phys == idParamIndex) continue;
            VariableElement p = params.get(phys);
            AnnotationMirror compAnno = BaseProcessor.getAnnotation(p, ANNO_COMPONENT);
            String declaredType = p.asType().toString();
            String compTypeFqn;
            if (compAnno != null) {
                compTypeFqn = BaseProcessor.readTypeClass(compAnno, "type");
                if (compTypeFqn == null || compTypeFqn.isEmpty()) { error("@Component on %s must specify type()", p); continue; }
            } else {
                if ("com.ethnicthv.ecs.core.components.ComponentHandle".equals(declaredType)) { error("Parameter %s must be annotated with @Component(type=...) to specify component type", p); continue; }
                if (declaredType.endsWith("Handle")) {
                    String base = declaredType.substring(0, declaredType.length() - "Handle".length());
                    compTypeFqn = (elementUtils.getTypeElement(base) != null) ? base : declaredType;
                } else compTypeFqn = declaredType;
            }
            pa.componentClasses.add(compTypeFqn);
            pa.declaredTypes.add(declaredType);
            pa.logicalToPhysical.add(phys);
            int lastDot = compTypeFqn.lastIndexOf('.');
            String compPkg = (lastDot >= 0) ? compTypeFqn.substring(0, lastDot) : "";
            String compSimple = (lastDot >= 0) ? compTypeFqn.substring(lastDot + 1) : compTypeFqn;
            String handleFqn = (compPkg.isEmpty() ? compSimple + "Handle" : compPkg + "." + compSimple + "Handle");
            pa.expectedHandleClasses.add(handleFqn);
            String binderName = null;
            if (!"com.ethnicthv.ecs.core.components.ComponentHandle".equals(declaredType)) {
                TypeElement declEl = elementUtils.getTypeElement(declaredType);
                if (declEl != null) binderName = findPublicBinderMethodName(declEl);
            }
            pa.directBinderNames.add(binderName);
        }
        return pa;
    }

    // ---------------------------------------------------------------------
    // Generation block builders (return code strings)
    // ---------------------------------------------------------------------
    private String buildParamStrategyBlock() {
        return """
                for (int i = 0; i < PARAM_COUNT; i++) {
                  var __desc = __cmgr.getDescriptor(PARAM_CLASSES[i]);
                  if (__desc == null) throw new IllegalStateException("Descriptor not found for component: " + PARAM_CLASSES[i].getName());
                  this.PARAM_IS_MANAGED[i] = __desc.isManaged();
                  this.PARAM_DESCRIPTORS[i] = __desc;
                  boolean declaredIsRawHandle = (DECLARED_PARAM_TYPES[i] == com.ethnicthv.ecs.core.components.ComponentHandle.class);
                  boolean declaredIsGeneratedHandle = DECLARED_PARAM_TYPES[i].getName().equals(HANDLE_CLASS_NAMES[i]);
                  boolean declaredEqualsComponentClass = (DECLARED_PARAM_TYPES[i] == PARAM_CLASSES[i]);
                  if (this.PARAM_IS_MANAGED[i]) {
                    this.STRATEGY[i] = ParamStrategy.MANAGED;
                  } else {
                    if (declaredIsRawHandle) {
                      this.STRATEGY[i] = ParamStrategy.UNMANAGED_RAW;
                    } else if (declaredIsGeneratedHandle || DIRECT_BIND[i]) {
                      this.STRATEGY[i] = ParamStrategy.UNMANAGED_TYPED;
                    } else if (declaredEqualsComponentClass) {
                      // User mistakenly declared managed object for an unmanaged component; defer error to validateConfig()
                      this.STRATEGY[i] = ParamStrategy.MANAGED;
                    } else {
                      throw new IllegalStateException("Parameter type " + DECLARED_PARAM_TYPES[i].getName() + " is invalid for unmanaged component " + PARAM_CLASSES[i].getName() + ". Use ComponentHandle or the generated Handle class.");
                    }
                  }
                  if (!this.PARAM_IS_MANAGED[i] && this.STRATEGY[i] == ParamStrategy.UNMANAGED_TYPED && !DIRECT_BIND[i]) {
                    try {
                      var __prv = java.lang.invoke.MethodHandles.privateLookupIn(DECLARED_PARAM_TYPES[i], __lookup);
                      java.lang.invoke.MethodHandle __bh = null;
                      try { __bh = __prv.findVirtual(DECLARED_PARAM_TYPES[i], "__bind", java.lang.invoke.MethodType.methodType(void.class, com.ethnicthv.ecs.core.components.ComponentHandle.class)); } catch (Throwable ignore) {}
                      if (__bh == null) { try { __bh = __prv.findVirtual(DECLARED_PARAM_TYPES[i], "bind", java.lang.invoke.MethodType.methodType(void.class, com.ethnicthv.ecs.core.components.ComponentHandle.class)); } catch (Throwable ignore) {} }
                      if (__bh == null) { try { __bh = __prv.findVirtual(DECLARED_PARAM_TYPES[i], "reset", java.lang.invoke.MethodType.methodType(void.class, com.ethnicthv.ecs.core.components.ComponentHandle.class)); } catch (Throwable ignore) {} }
                      if (__bh == null) throw new IllegalStateException("Typed handle class " + DECLARED_PARAM_TYPES[i].getName() + " must declare __bind/ bind/ or reset method with (ComponentHandle)");
                      this.BINDER_MH[i] = __bh;
                    } catch (Throwable t) {
                      throw new IllegalStateException("Failed to prepare typed handle for parameter " + i + " of type " + DECLARED_PARAM_TYPES[i].getName(), t);
                    }
                  }
                }

                """;
    }

    private String buildPrivateMethodHandleCreator(String ownerQualified, String methodName, List<? extends VariableElement> params) {
        StringBuilder sb = new StringBuilder();
        sb.append("  private java.lang.invoke.MethodHandle createMH(){ try { var lookup = java.lang.invoke.MethodHandles.lookup(); var prv = java.lang.invoke.MethodHandles.privateLookupIn(")
          .append(ownerQualified).append(".class, lookup); var mt = java.lang.invoke.MethodType.methodType(void.class");
        for (VariableElement p : params) {
            String t = p.asType().toString();
            sb.append(", ").append("int".equals(t) ? "int.class" : t + ".class");
        }
        sb.append("); return prv.findVirtual(").append(ownerQualified).append(".class, \"")
          .append(methodName).append("\", mt); } catch (Throwable t) { throw new RuntimeException(t); } }\n");
        return sb.toString();
    }

    private String buildValidateConfigBlock() {
        return """
  private void validateConfig(){
    for (int i = 0; i < paramIds.length; i++) {
      boolean isManaged = PARAM_IS_MANAGED[i];
      if (isManaged) {
        if (DECLARED_PARAM_TYPES[i] != PARAM_CLASSES[i]) {
          throw new IllegalStateException("Parameter type mismatch: component " + PARAM_CLASSES[i].getName() + " is @Managed, method parameter must be that component type");
        }
      } else {
        if (DECLARED_PARAM_TYPES[i] == PARAM_CLASSES[i]) {
          throw new IllegalStateException("Parameter " + i + " declared as managed object (" + PARAM_CLASSES[i].getName() + ") but component is unmanaged. Use ComponentHandle or the generated Handle class.");
        }
      }
    }
    if (HAS_ID_FIELD && MODE_PARALLEL) {
      throw new IllegalStateException("@Id field is not supported with PARALLEL Query mode; use SEQUENTIAL.");
    }
  }
""";
    }

    private String buildPlanBuilderBlock() {
        return "  private ArchetypePlan buildPlan(com.ethnicthv.ecs.core.archetype.Archetype archetype){ final int[] compIdx = new int[PARAM_COUNT]; final int[] managedIdx = new int[PARAM_COUNT]; boolean ok = true; for (int i = 0; i < PARAM_COUNT; i++) { int tid = paramIds[i]; if (!PARAM_IS_MANAGED[i]) { compIdx[i] = archetype.indexOfComponentType(tid); if (compIdx[i] < 0) { ok=false; break; } managedIdx[i] = -1; } else { managedIdx[i] = archetype.getManagedTypeIndex(tid); if (managedIdx[i] < 0) { ok=false; break; } compIdx[i] = -1; } } return ok ? new ArchetypePlan(compIdx, managedIdx) : null; }\n";
    }

    private String buildSharedKeyBuilderBlock() {
        return "  private com.ethnicthv.ecs.core.archetype.SharedValueKey buildQueryKey(com.ethnicthv.ecs.core.archetype.Archetype archetype){ int[] managedIdx = null; long[] unmanagedVals = null; boolean any=false; if (this.__managedSharedFilter != null) { int ticket = world.findSharedIndex(this.__managedSharedFilter); if (ticket < 0) return null; int managedCount = archetype.getSharedManagedTypeIds().length; if (managedCount == 0) return null; managedIdx = new int[managedCount]; java.util.Arrays.fill(managedIdx, -1); for (int typeId : archetype.getSharedManagedTypeIds()) { int pos = archetype.getSharedManagedIndex(typeId); if (pos >= 0) { managedIdx[pos] = ticket; any = true; } } } if (this.__unmanagedSharedFilters != null && !this.__unmanagedSharedFilters.isEmpty()) { int unmanagedCount = archetype.getSharedUnmanagedTypeIds().length; if (unmanagedCount == 0) return null; unmanagedVals = new long[unmanagedCount]; java.util.Arrays.fill(unmanagedVals, Long.MIN_VALUE); for (var f : this.__unmanagedSharedFilters) { Integer typeId = world.getComponentTypeId(f.type); if (typeId == null) return null; int pos = archetype.getSharedUnmanagedIndex(typeId); if (pos < 0) return null; unmanagedVals[pos] = f.value; any = true; } } if (!any) return null; return new com.ethnicthv.ecs.core.archetype.SharedValueKey(managedIdx, unmanagedVals); }\n";
    }

    private String buildBindSequence(List<String> declaredTypes, List<String> directBinderNames) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < declaredTypes.size(); i++) {
            String decl = declaredTypes.get(i);
            String direct = directBinderNames.get(i);
            sb.append("            if (STRATEGY[").append(i).append("] == ParamStrategy.UNMANAGED_TYPED) { ");
            if (direct != null) {
                sb.append("((").append(decl).append(") typed[").append(i).append("]).").append(direct).append("((com.ethnicthv.ecs.core.components.ComponentHandle) pooled[").append(i).append("]);\n");
            } else {
                sb.append("try { BINDER_MH[").append(i).append("]\n")
                  .append(".invoke(typed[").append(i).append("], pooled[").append(i).append("]); } catch (Throwable __t) { throw new RuntimeException(__t); }\n");
            }
            sb.append("            }\n");
        }
        return sb.toString();
    }

    private String buildPrepSequence(List<String> declaredTypes, String entityVar) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < declaredTypes.size(); i++) {
            String dt = declaredTypes.get(i);
            sb.append("            final Object __argObj_").append(i).append(" = (STRATEGY[").append(i)
              .append("] == ParamStrategy.MANAGED) ? world.getManagedComponent(").append(entityVar).append(", PARAM_CLASSES[").append(i)
              .append("]) : (STRATEGY[").append(i).append("] == ParamStrategy.UNMANAGED_RAW) ? pooled[").append(i).append("] : typed[").append(i).append("];\n")
              .append("            final ").append(dt).append(" a").append(i).append(" = (").append(dt).append(") __argObj_").append(i).append(";\n");
        }
        return sb.toString();
    }

    private String buildCallSequence(boolean isPrivate, ExecutableElement method, int idParamIndex, List<Integer> logicalToPhysical, String entityVar) {
        StringBuilder sb = new StringBuilder();
        List<? extends VariableElement> params = method.getParameters();
        if (isPrivate) {
            sb.append("            try { invExact.invokeExact(system");
            for (int i = 0; i < params.size(); i++) {
                String t = params.get(i).asType().toString();
                if (i == idParamIndex) {
                    sb.append(", ").append("int".equals(t) ? entityVar : "java.lang.Integer.valueOf(" + entityVar + ")");
                } else {
                    int li = -1; for (int k = 0; k < logicalToPhysical.size(); k++) { if (logicalToPhysical.get(k) == i) { li = k; break; } }
                    sb.append(", a").append(li);
                }
            }
            sb.append("); } catch (Throwable t) { throw new RuntimeException(t); }\n");
        } else {
            sb.append("            system.").append(method.getSimpleName()).append("(");
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) sb.append(", ");
                String t = params.get(i).asType().toString();
                if (i == idParamIndex) sb.append("int".equals(t) ? entityVar : "java.lang.Integer.valueOf(" + entityVar + ")");
                else { int li = -1; for (int k = 0; k < logicalToPhysical.size(); k++) { if (logicalToPhysical.get(k) == i) { li = k; break; } } sb.append("a").append(li); }
            }
            sb.append(");\n");
        }
        return sb.toString();
    }

    private String buildSequentialChunkRunner(boolean hasIdField, String bindsSeq, String prepSeq, String callSeq) {
        StringBuilder sb = new StringBuilder();
        sb.append("  private void runOnChunk_Sequential(com.ethnicthv.ecs.core.archetype.ArchetypeChunk chunk, ArchetypePlan plan, com.ethnicthv.ecs.core.components.ComponentManager cm){ final com.ethnicthv.ecs.core.components.ComponentHandle[] pooled = new com.ethnicthv.ecs.core.components.ComponentHandle[PARAM_COUNT]; for (int i = 0; i < PARAM_COUNT; i++) { if (!PARAM_IS_MANAGED[i]) pooled[i] = cm.acquireHandle(); } final Object[] typed = SEQ_TYPED; try { int idx = chunk.nextOccupiedIndex(0); while (idx >= 0) { int entityId = chunk.getEntityId(idx);\n");
        if (hasIdField) sb.append("            try { ID_FIELD.setInt(system, entityId); } catch (Throwable t) { throw new RuntimeException(t); }\n");
        sb.append("            for (int k = 0; k < PARAM_COUNT; k++) { if (STRATEGY[k] != ParamStrategy.MANAGED) { var seg = chunk.getComponentData(plan.compIdx[k], idx); pooled[k].reset(seg, PARAM_DESCRIPTORS[k]); } }\n");
        sb.append(bindsSeq);
        sb.append(prepSeq);
        sb.append(callSeq);
        sb.append("            idx = chunk.nextOccupiedIndex(idx + 1); } } finally { for (int i = 0; i < PARAM_COUNT; i++) { if (pooled[i] != null) cm.releaseHandle(pooled[i]); } } }\n");
        return sb.toString();
    }

    private String buildParallelChunkRunner(String bindsSeq, String prepPar, String callPar) {
        StringBuilder sb = new StringBuilder();
        sb.append("  private void runOnChunk_Parallel(com.ethnicthv.ecs.core.archetype.ArchetypeChunk chunk, ArchetypePlan plan, com.ethnicthv.ecs.core.components.ComponentManager cm){ final com.ethnicthv.ecs.core.components.ComponentHandle[] pooled = new com.ethnicthv.ecs.core.components.ComponentHandle[PARAM_COUNT]; for (int i = 0; i < PARAM_COUNT; i++) { if (!PARAM_IS_MANAGED[i]) pooled[i] = cm.acquireHandle(); } final Object[] typed = PAR_TYPED.get(); try { int idx = chunk.nextOccupiedIndex(0); while (idx >= 0) { int eid = chunk.getEntityId(idx); for (int k = 0; k < PARAM_COUNT; k++) { if (STRATEGY[k] != ParamStrategy.MANAGED) { var seg = chunk.getComponentData(plan.compIdx[k], idx); pooled[k].reset(seg, PARAM_DESCRIPTORS[k]); } }\n");
        sb.append(bindsSeq);
        sb.append(prepPar);
        sb.append(callPar);
        sb.append("            idx = chunk.nextOccupiedIndex(idx + 1); } } finally { for (int i = 0; i < PARAM_COUNT; i++) { if (pooled[i] != null) cm.releaseHandle(pooled[i]); } } }\n");
        return sb.toString();
    }

    private String buildRunSequentialBlock() {
        return "  private void runSequential(){ final var cm = world.getComponentManager(); final boolean hasFilter = (this.__managedSharedFilter != null || (this.__unmanagedSharedFilters != null && !this.__unmanagedSharedFilters.isEmpty())); for (com.ethnicthv.ecs.core.archetype.Archetype archetype : world.getAllArchetypes()) { com.ethnicthv.ecs.core.archetype.ComponentMask am = archetype.getMask(); if (!am.containsAll(withMask)) continue; if (!am.containsNone(withoutMask)) continue; if (anyIds.length > 0 && !am.intersects(anyMask)) continue; ArchetypePlan plan = PLAN_CACHE.computeIfAbsent(archetype, a -> buildPlan(a)); if (plan == null) continue; if (hasFilter) { com.ethnicthv.ecs.core.archetype.SharedValueKey qk = buildQueryKey(archetype); if (qk == null) continue; com.ethnicthv.ecs.core.archetype.ChunkGroup g = archetype.getChunkGroup(qk); if (g == null) continue; runOnGroup_Sequential(g, plan, cm); } else { for (com.ethnicthv.ecs.core.archetype.ChunkGroup g : archetype.getAllChunkGroups()) runOnGroup_Sequential(g, plan, cm); } } }\n";
    }

    private String buildRunParallelBlock() {
        return "  private void runParallel(){ final var cm = world.getComponentManager(); final boolean hasFilter = (this.__managedSharedFilter != null || (this.__unmanagedSharedFilters != null && !this.__unmanagedSharedFilters.isEmpty())); java.util.List<Runnable> tasks = new java.util.ArrayList<>(); for (com.ethnicthv.ecs.core.archetype.Archetype archetype : world.getAllArchetypes()) { com.ethnicthv.ecs.core.archetype.ComponentMask am = archetype.getMask(); if (!am.containsAll(withMask)) continue; if (!am.containsNone(withoutMask)) continue; if (anyIds.length > 0 && !am.intersects(anyMask)) continue; ArchetypePlan plan = PLAN_CACHE.computeIfAbsent(archetype, a -> buildPlan(a)); if (plan == null) continue; if (hasFilter) { com.ethnicthv.ecs.core.archetype.SharedValueKey qk = buildQueryKey(archetype); if (qk == null) continue; com.ethnicthv.ecs.core.archetype.ChunkGroup g = archetype.getChunkGroup(qk); if (g == null) continue; com.ethnicthv.ecs.core.archetype.ArchetypeChunk[] chunks = g.getChunksSnapshot(); int count = g.chunkCount(); for (int i = 0; i < count; i++) { final com.ethnicthv.ecs.core.archetype.ArchetypeChunk c = chunks[i]; final ArchetypePlan p = plan; tasks.add(() -> runOnChunk_Parallel(c, p, cm)); } } else { for (com.ethnicthv.ecs.core.archetype.ChunkGroup g : archetype.getAllChunkGroups()) { com.ethnicthv.ecs.core.archetype.ArchetypeChunk[] chunks = g.getChunksSnapshot(); int count = g.chunkCount(); for (int i = 0; i < count; i++) { final com.ethnicthv.ecs.core.archetype.ArchetypeChunk c = chunks[i]; final ArchetypePlan p = plan; tasks.add(() -> runOnChunk_Parallel(c, p, cm)); } } } } tasks.parallelStream().forEach(Runnable::run); }\n";
    }

    private void writeClassArray(Writer w, String label, List<String> classes) throws IOException {
        w.write("  private static final Class<?>[] " + label + " = new Class<?>[]{");
        for (int i = 0; i < classes.size(); i++) w.write(classes.get(i) + ".class" + (i < classes.size() - 1 ? "," : ""));
        w.write("};\n");
    }

    // ---------------------------------------------------------------------
    // Low-level utilities
    // ---------------------------------------------------------------------
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
}
