package com.ethnicthv.ecs.processor;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Annotation processor that generates per-component metaclasses with:
 * - integer field-index constants (IDX_<NAME>)
 * - a prebuilt ComponentDescriptor (DESCRIPTOR)
 * - a type-safe <Component>Access with getX/setX(ComponentHandle) API
 * Also generates a central registry com.ethnicthv.ecs.generated.GeneratedComponents
 * with registerAll(ComponentManager) for quick startup registration.
 */
@SupportedAnnotationTypes({
        "com.ethnicthv.ecs.core.components.Component.Field",
        "com.ethnicthv.ecs.core.components.Component.Layout",
})
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class ComponentProcessor extends BaseProcessor {
    // ---------------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------------
    private static final String ANNO_LAYOUT = "com.ethnicthv.ecs.core.components.Component.Layout";
    private static final String ANNO_FIELD = "com.ethnicthv.ecs.core.components.Component.Field";
    private static final String COMPONENT_IFACE = "com.ethnicthv.ecs.core.components.Component";

    private Elements elementUtils;
    // Accumulate discovered component types to generate a central registry (FQNs)
    private final Set<String> collectedComponents = new LinkedHashSet<>();
    // In-memory descriptors for already-processed component types within a round
    private final Map<String, LocalDescriptor> generatedDescriptors = new LinkedHashMap<>();

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------
    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.elementUtils = processingEnv.getElementUtils();
        note("ComponentProcessor init");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        note("Processing round: annotations=%d over=%s", annotations.size(), roundEnv.processingOver());
        generatedDescriptors.clear();
        Set<TypeElement> candidates = collectCandidateComponentTypes(roundEnv);
        note("Found %d candidate component types", candidates.size());
        if (!candidates.isEmpty()) {
            DependencyGraph graph = buildDependencyGraph(candidates);
            List<TypeElement> ordered = topoSort(graph);
            note("Topologically sorted component order (size=%d)", ordered.size());

            TypeElement componentInterface = getTypeElement(COMPONENT_IFACE);
            TypeMirror componentMirror = componentInterface != null ? componentInterface.asType() : null;
            for (TypeElement compType : ordered) {
                note("Processing component type (topo): %s", compType.getQualifiedName());
                try {
                    boolean generated = generateForComponent(compType);
                    if (generated) {
                        boolean isComponent = componentMirror != null && processingEnv.getTypeUtils().isAssignable(compType.asType(), componentMirror);
                        if (isComponent) collectedComponents.add(compType.getQualifiedName().toString());
                    } else {
                        note("Deferring generation for %s due to unresolved composite dependencies", compType.getQualifiedName());
                    }
                } catch (IOException ex) {
                    error("Failed to generate meta/handle for %s: %s", compType.getQualifiedName(), ex.getMessage());
                }
            }
        }
        if (roundEnv.processingOver() && !collectedComponents.isEmpty()) {
            try { generateCentralRegistry(); } catch (IOException ex) { error("Failed to generate central registry: %s", ex.getMessage()); }
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Candidate discovery
    // ---------------------------------------------------------------------
    private Set<TypeElement> collectCandidateComponentTypes(RoundEnvironment roundEnv) {
        Set<TypeElement> result = new LinkedHashSet<>();
        TypeElement layoutAnno = getTypeElement(ANNO_LAYOUT);
        TypeElement fieldAnno = getTypeElement(ANNO_FIELD);

        if (layoutAnno != null) {
            for (Element e : roundEnv.getElementsAnnotatedWith(layoutAnno)) {
                if (e.getKind().isClass()) result.add((TypeElement) e);
            }
        }
        if (fieldAnno != null) {
            for (Element e : roundEnv.getElementsAnnotatedWith(fieldAnno)) {
                Element owner = e.getEnclosingElement();
                if (owner != null && owner.getKind().isClass()) result.add((TypeElement) owner);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Dependency Graph Construction (Phase 1)
    // ---------------------------------------------------------------------
    private static final class DependencyGraph {
        final Map<String, TypeElement> nodes = new LinkedHashMap<>(); // fqn -> element
        final Map<String, Set<String>> edges = new LinkedHashMap<>(); // component fqn -> set of dependency fqns
    }

    private DependencyGraph buildDependencyGraph(Set<TypeElement> candidates) {
        DependencyGraph graph = new DependencyGraph();
        for (TypeElement te : candidates) {
            String fqn = te.getQualifiedName().toString();
            graph.nodes.put(fqn, te);
            graph.edges.put(fqn, new LinkedHashSet<>());
        }
        for (TypeElement comp : candidates) {
            String compFqn = comp.getQualifiedName().toString();
            for (Element e : comp.getEnclosedElements()) {
                if (e.getKind() != ElementKind.FIELD) continue;
                if (!hasFieldAnnotation(e)) continue;
                VariableElement ve = (VariableElement) e;
                String fieldTypeFqn = ve.asType().toString();
                if (isPrimitiveOrBoxed(fieldTypeFqn)) continue;
                if (graph.nodes.containsKey(fieldTypeFqn)) {
                    // Invert edge: dependency -> component
                    graph.edges.get(fieldTypeFqn).add(compFqn);
                    note("Dependency edge (inverted): %s -> %s", fieldTypeFqn, compFqn);
                }
            }
        }
        return graph;
    }

    private boolean isPrimitiveOrBoxed(String fqn) {
        return switch (fqn) {
            case "byte", "java.lang.Byte",
                 "short", "java.lang.Short",
                 "int", "java.lang.Integer",
                 "long", "java.lang.Long",
                 "float", "java.lang.Float",
                 "double", "java.lang.Double",
                 "boolean", "java.lang.Boolean",
                 "char", "java.lang.Character" -> true;
            default -> false;
        };
    }

    private List<TypeElement> topoSort(DependencyGraph graph) {
        // Kahn's algorithm using edges: component -> dependency
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (String node : graph.nodes.keySet()) indegree.put(node, 0);
        for (Map.Entry<String, Set<String>> e : graph.edges.entrySet()) {
            for (String dep : e.getValue()) indegree.put(dep, indegree.get(dep) + 1);
        }
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }
        List<String> orderedNames = new ArrayList<>();
        while (!queue.isEmpty()) {
            String n = queue.removeFirst();
            orderedNames.add(n);
            for (String dep : graph.edges.getOrDefault(n, Collections.emptySet())) {
                int remaining = indegree.compute(dep, (k, v) -> v - 1);
                if (remaining == 0) queue.add(dep);
            }
        }
        if (orderedNames.size() != graph.nodes.size()) {
            // Cycle detection: produce diagnostics for involved nodes
            Set<String> cycleNodes = new LinkedHashSet<>();
            for (Map.Entry<String, Integer> entry : indegree.entrySet()) if (entry.getValue() > 0) cycleNodes.add(entry.getKey());
            error("Cycle detected in component dependency graph: %s", cycleNodes);
            // Fallback: return original insertion order to proceed (graceful degrade)
            return new ArrayList<>(graph.nodes.values());
        }
        List<TypeElement> out = new ArrayList<>(orderedNames.size());
        for (String name : orderedNames) out.add(graph.nodes.get(name));
        return out;
    }

    // ---------------------------------------------------------------------
    // Generation pipeline for one component
    // ---------------------------------------------------------------------
    private boolean generateForComponent(TypeElement compType) throws IOException {
        String pkg = elementUtils.getPackageOf(compType).getQualifiedName().toString();
        String simpleName = compType.getSimpleName().toString();
        String compFqn = compType.getQualifiedName().toString();
        ComponentLayout layout = readLayout(compType);
        List<VariableElement> rawFields = collectAnnotatedFields(compType);
        List<GeneratedField> genFields;
        List<CompositeFieldInfo> compositeInfos = new ArrayList<>();
        try {
            genFields = layoutAndSizeFields(layout, rawFields, compType, compositeInfos);
        } catch (UnresolvedCompositeException uce) {
            note("Unresolved composite for %s: %s", compType.getQualifiedName(), uce.getMessage());
            return false;
        }
        compositeFieldMap.put(compFqn, compositeInfos);
        long totalSize = computeTotalSize(layout, genFields);
        int maxAlignment = 1;
        for (GeneratedField f : genFields) maxAlignment = Math.max(maxAlignment, f.alignment);
        generatedDescriptors.put(compFqn, new LocalDescriptor(genFields, totalSize, maxAlignment));
        generateMetaSource(pkg, simpleName, compType, genFields, totalSize, layout);
        generateHandleSource(pkg, simpleName, genFields, compType, compositeInfos);
        return true;
    }

    private List<VariableElement> collectAnnotatedFields(TypeElement compType) {
        return compType.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.FIELD)
                .map(VariableElement.class::cast)
                .filter(this::hasFieldAnnotation)
                .filter(f -> !f.getModifiers().contains(Modifier.STATIC))
                .collect(Collectors.toList());
    }

    private boolean hasFieldAnnotation(Element e) {
        for (AnnotationMirror am : e.getAnnotationMirrors()) {
            Element el = am.getAnnotationType().asElement();
            if (el instanceof TypeElement te && te.getQualifiedName().contentEquals(ANNO_FIELD)) return true;
        }
        return false;
    }

    // Exception used to defer generation when a composite dependency isn't ready yet
    private static final class UnresolvedCompositeException extends RuntimeException {
        UnresolvedCompositeException(String msg) { super(msg); }
    }

    private List<GeneratedField> layoutAndSizeFields(ComponentLayout layout, List<VariableElement> fields, TypeElement ownerType, List<CompositeFieldInfo> compositeOut) {
        List<VariableElement> work = new ArrayList<>(fields);
        if (layout.type == LayoutType.EXPLICIT) {
            work.sort(Comparator.comparingInt(f -> readField(f).offset));
        }
        List<GeneratedField> out = new ArrayList<>();
        long currentOffset = 0L;
        int maxAlignment = 1;
        for (VariableElement ve : work) {
            FieldAttrs attrs = readField(ve);
            String name = ve.getSimpleName().toString();
            String typeFqn = ve.asType().toString();
            FieldType primitive = tryMapPrimitive(typeFqn);
            if (primitive != null) {
                int alignment = attrs.alignment > 0 ? attrs.alignment : primitive.naturalAlignment;
                long size = attrs.size > 0 ? attrs.size : primitive.size;
                long offset;
                if (layout.type == LayoutType.EXPLICIT && attrs.offset >= 0) {
                    offset = attrs.offset;
                } else if (layout.type == LayoutType.PADDING) {
                    offset = alignUp(currentOffset, alignment);
                } else {
                    offset = currentOffset;
                }
                out.add(new GeneratedField(name, primitive, offset, size, alignment));
                currentOffset = offset + size;
                maxAlignment = Math.max(maxAlignment, alignment);
                continue;
            }
            LocalDescriptor sub = generatedDescriptors.get(typeFqn);
            if (sub == null) {
                throw new UnresolvedCompositeException("Missing descriptor for composite type '" + typeFqn + "' used in " + ownerType.getQualifiedName());
            }
            int compositeAlign = attrs.alignment > 0 ? attrs.alignment : sub.maxAlignment();
            long compositeSize = attrs.size > 0 ? attrs.size : sub.totalSize();
            long baseOffset;
            if (layout.type == LayoutType.EXPLICIT && attrs.offset >= 0) {
                baseOffset = attrs.offset;
            } else if (layout.type == LayoutType.PADDING) {
                baseOffset = alignUp(currentOffset, compositeAlign);
            } else {
                baseOffset = currentOffset;
            }
            compositeOut.add(new CompositeFieldInfo(name, typeFqn, baseOffset));
            for (GeneratedField sf : sub.fields()) {
                String flatName = name + "_" + sf.name;
                long flatOffset = baseOffset + sf.offset;
                out.add(new GeneratedField(flatName, sf.ft, flatOffset, sf.size, sf.alignment));
            }
            currentOffset = baseOffset + compositeSize;
            maxAlignment = Math.max(maxAlignment, compositeAlign);
        }
        return out;
    }

    private long computeTotalSize(ComponentLayout layout, List<GeneratedField> fields) {
        if (layout.sizeOverride > 0) return layout.sizeOverride;
        long currentOffset = 0L;
        int maxAlignment = 1;
        for (GeneratedField f : fields) {
            currentOffset = Math.max(currentOffset, f.offset + f.size);
            maxAlignment = Math.max(maxAlignment, f.alignment);
        }
        if (layout.type == LayoutType.PADDING && !fields.isEmpty()) {
            return alignUp(currentOffset, maxAlignment);
        }
        return currentOffset;
    }

    // ---------------------------------------------------------------------
    // Individual source generation helpers
    // ---------------------------------------------------------------------
    private void generateMetaSource(String pkg, String simpleName, TypeElement compType,
                                    List<GeneratedField> genFields, long totalSize, ComponentLayout layout) throws IOException {
        String metaName = simpleName + "Meta";
        String fqn = pkg.isEmpty() ? metaName : pkg + "." + metaName;
        JavaFileObject file = processingEnv.getFiler().createSourceFile(fqn, compType);
        // Compute explicit ComponentKind from annotations
        boolean hasManaged = BaseProcessor.hasAnnotation(compType, "com.ethnicthv.ecs.core.components.Component.Managed");
        boolean hasShared = BaseProcessor.hasAnnotation(compType, "com.ethnicthv.ecs.core.components.Component.Shared");
        String kindLiteral;
        if (hasShared) {
            kindLiteral = hasManaged
                ? "com.ethnicthv.ecs.core.components.ComponentDescriptor.ComponentKind.SHARED_MANAGED"
                : "com.ethnicthv.ecs.core.components.ComponentDescriptor.ComponentKind.SHARED_UNMANAGED";
        } else {
            kindLiteral = hasManaged
                ? "com.ethnicthv.ecs.core.components.ComponentDescriptor.ComponentKind.INSTANCE_MANAGED"
                : "com.ethnicthv.ecs.core.components.ComponentDescriptor.ComponentKind.INSTANCE_UNMANAGED";
        }
        try (Writer w = file.openWriter()) {
            if (!pkg.isEmpty()) w.write("package " + pkg + ";\n\n");
            w.write("@SuppressWarnings(\"all\")\n");
            w.write("public final class " + metaName + " {\n");

            // Field index constants
            for (int i = 0; i < genFields.size(); i++) {
                w.write("    public static final int IDX_" + genFields.get(i).name.toUpperCase(Locale.ROOT) + " = " + i + ";\n");
            }
            w.write("\n");

            // Descriptor build with explicit kind
            w.write("    public static final com.ethnicthv.ecs.core.components.ComponentDescriptor DESCRIPTOR =\n");
            w.write("        new com.ethnicthv.ecs.core.components.ComponentDescriptor(\n");
            w.write("            " + compType.getQualifiedName() + ".class,\n");
            w.write("            " + totalSize + "L,\n");
            w.write("            java.util.List.of(\n");
            for (int i = 0; i < genFields.size(); i++) {
                GeneratedField f = genFields.get(i);
                w.write("                new com.ethnicthv.ecs.core.components.ComponentDescriptor.FieldDescriptor(\"" + f.name + "\", ");
                w.write("com.ethnicthv.ecs.core.components.ComponentDescriptor.FieldType." + f.ft.enumName + ", ");
                w.write(f.offset + "L, " + f.size + "L, " + f.alignment + ")");
                if (i < genFields.size() - 1) w.write(",");
                w.write("\n");
            }
            w.write("            ),\n");
            w.write("            com.ethnicthv.ecs.core.components.Component.LayoutType." + layout.type.name() + ",\n");
            w.write("            " + kindLiteral + "\n");
            w.write("        );\n\n");

            w.write("    public static com.ethnicthv.ecs.core.components.ComponentDescriptor descriptor() { return DESCRIPTOR; }\n\n");
            w.write("    private " + metaName + "() {}\n");
            w.write("}\n");
        }
    }

    private void generateHandleSource(String pkg, String simpleName, List<GeneratedField> genFields, TypeElement compType, List<CompositeFieldInfo> compositeInfos) throws IOException {
        String handleName = simpleName + "Handle";
        String fqn = pkg.isEmpty() ? handleName : pkg + "." + handleName;
        JavaFileObject file = processingEnv.getFiler().createSourceFile(fqn, compType);
        boolean isInterface = compType.getKind() == ElementKind.INTERFACE;
        try (Writer w = file.openWriter()) {
            if (!pkg.isEmpty()) w.write("package " + pkg + ";\n\n");
            w.write("@SuppressWarnings(\"all\")\n");
            if (isInterface) {
                w.write("public final class " + handleName + " implements " + compType.getQualifiedName() + ", com.ethnicthv.ecs.core.components.IBindableHandle {\n");
            } else {
                w.write("public final class " + handleName + " implements com.ethnicthv.ecs.core.components.IBindableHandle {\n");
            }
            w.write("  private com.ethnicthv.ecs.core.components.ComponentHandle __internalHandle;\n");
            w.write("  private long __baseOffset;\n");
            w.write("  public void __bind(com.ethnicthv.ecs.core.components.ComponentHandle h) { this.__internalHandle = h; this.__baseOffset = 0L; }\n");
            w.write("  void __setBaseOffset(long off) { this.__baseOffset = off; }\n");
            w.write("  public com.ethnicthv.ecs.core.components.ComponentHandle __raw() { return __internalHandle; }\n\n");
            String meta = simpleName + "Meta";
            for (GeneratedField f : genFields) {
                String prop = toCamelCase(f.name);
                String idxConst = meta + ".IDX_" + f.name.toUpperCase(Locale.ROOT);
                String valueLayout;
                switch (f.ft) {
                    case FLOAT -> valueLayout = "JAVA_FLOAT";
                    case INT -> valueLayout = "JAVA_INT";
                    case LONG -> valueLayout = "JAVA_LONG";
                    case DOUBLE -> valueLayout = "JAVA_DOUBLE";
                    case BOOLEAN -> valueLayout = "JAVA_BOOLEAN";
                    case BYTE -> valueLayout = "JAVA_BYTE";
                    case SHORT -> valueLayout = "JAVA_SHORT";
                    case CHAR -> valueLayout = "JAVA_CHAR";
                    default -> valueLayout = "JAVA_INT";
                }
                // absolute offset based on meta field offset
                w.write("  public " + javaTypeFor(f.ft) + " get" + prop + "() { ");
                w.write("var __seg = __internalHandle.getSegment(); long __off = this.__baseOffset + " + meta + ".DESCRIPTOR.getField(" + idxConst + ").offset(); ");
                w.write("return __seg.get(java.lang.foreign.ValueLayout." + valueLayout + ", __off); }\n");
                w.write("  public void set" + prop + "(" + javaTypeFor(f.ft) + " v) { ");
                w.write("var __seg = __internalHandle.getSegment(); long __off = this.__baseOffset + " + meta + ".DESCRIPTOR.getField(" + idxConst + ").offset(); ");
                w.write("__seg.set(java.lang.foreign.ValueLayout." + valueLayout + ", __off, v); }\n");
            }
            // Slice composite getters pass base offset to child handle
            for (CompositeFieldInfo c : compositeInfos) {
                String compSimple = c.typeFqn.substring(c.typeFqn.lastIndexOf('.') + 1);
                String handleClass = compSimple + "Handle";
                String methodName = "get" + toCamelCase(c.originalName);
                w.write("  public " + handleClass + " " + methodName + "() {\n");
                w.write("    " + handleClass + " h = new " + handleClass + "(); h.__bind(this.__internalHandle); h.__setBaseOffset(this.__baseOffset + " + c.baseOffset + "L); return h;\n  }\n");
            }
            w.write("}\n");
        }
    }

    private void generateCentralRegistry() throws IOException {
        String pkg = "com.ethnicthv.ecs.generated";
        String name = "GeneratedComponents";
        String fqn = pkg + "." + name;
        JavaFileObject file = processingEnv.getFiler().createSourceFile(fqn);
        try (Writer w = file.openWriter()) {
            w.write("package " + pkg + ";\n\n");
            w.write("@SuppressWarnings(\"all\")\n");
            w.write("public final class " + name + " {\n");
            w.write("    private " + name + "() {}\n\n");
            w.write("    public static void registerAll(com.ethnicthv.ecs.core.components.ComponentManager mgr) {\n");
            for (String fqnComp : collectedComponents) {
                String handleFqn = fqnComp + "Handle";
                w.write("        mgr.registerComponentWithHandle(\n");
                w.write("            " + fqnComp + ".class,\n");
                w.write("            " + fqnComp + "Meta.DESCRIPTOR,\n");
                w.write("            " + handleFqn + "::new\n");
                w.write("        );\n");
            }
            w.write("    }\n");
            w.write("}\n");
        }
    }

    // ---------------------------------------------------------------------
    // Annotation reading & utility
    // ---------------------------------------------------------------------
    private ComponentLayout readLayout(TypeElement type) {
        for (AnnotationMirror am : type.getAnnotationMirrors()) {
            Element el = am.getAnnotationType().asElement();
            if (!(el instanceof TypeElement te)) continue;
            if (!te.getQualifiedName().contentEquals(ANNO_LAYOUT)) continue;
            LayoutType kind = LayoutType.SEQUENTIAL;
            long sz = -1;
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : am.getElementValues().entrySet()) {
                String name = e.getKey().getSimpleName().toString();
                if (name.equals("value")) {
                    kind = LayoutType.valueOf(e.getValue().getValue().toString());
                } else if (name.equals("size")) {
                    Number n = (Number) e.getValue().getValue();
                    sz = n.longValue();
                }
            }
            return new ComponentLayout(kind, sz);
        }
        return new ComponentLayout(LayoutType.SEQUENTIAL, -1); // default
    }

    private FieldAttrs readField(VariableElement ve) {
        for (AnnotationMirror am : ve.getAnnotationMirrors()) {
            Element el = am.getAnnotationType().asElement();
            if (!(el instanceof TypeElement te)) continue;
            if (!te.getQualifiedName().contentEquals(ANNO_FIELD)) continue;
            int size = 0;
            int offset = -1;
            int alignment = 0;
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : am.getElementValues().entrySet()) {
                String name = e.getKey().getSimpleName().toString();
                Object av = e.getValue().getValue();
                if (av instanceof Number n) {
                    switch (name) {
                        case "size" -> size = n.intValue();
                        case "offset" -> offset = n.intValue();
                        case "alignment" -> alignment = n.intValue();
                    }
                }
            }
            return new FieldAttrs(size, offset, alignment);
        }
        return new FieldAttrs(0, -1, 0);
    }

    private FieldType tryMapPrimitive(String typeName) {
        return switch (typeName) {
            case "byte", "java.lang.Byte" -> FieldType.BYTE;
            case "short", "java.lang.Short" -> FieldType.SHORT;
            case "int", "java.lang.Integer" -> FieldType.INT;
            case "long", "java.lang.Long" -> FieldType.LONG;
            case "float", "java.lang.Float" -> FieldType.FLOAT;
            case "double", "java.lang.Double" -> FieldType.DOUBLE;
            case "boolean", "java.lang.Boolean" -> FieldType.BOOLEAN;
            case "char", "java.lang.Character" -> FieldType.CHAR;
            default -> null;
        };
    }

    private enum FieldType {
        BYTE(1,1,"BYTE"), SHORT(2,2,"SHORT"), INT(4,4,"INT"), LONG(8,8,"LONG"), FLOAT(4,4,"FLOAT"), DOUBLE(8,8,"DOUBLE"), BOOLEAN(1,1,"BOOLEAN"), CHAR(2,2,"CHAR");
        final long size; final int naturalAlignment; final String enumName;
        FieldType(long sz, int na, String en) { this.size = sz; this.naturalAlignment = na; this.enumName = en; }
    }
    private String javaTypeFor(FieldType ft) {
        return switch (ft) {
            case BYTE -> "byte";
            case SHORT -> "short";
            case INT -> "int";
            case LONG -> "long";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case BOOLEAN -> "boolean";
            case CHAR -> "char";
        };
    }

    private String toCamelCase(String name) {
        StringBuilder sb = new StringBuilder();
        boolean upperNext = true;
        for (char c : name.toCharArray()) {
            if (c == '_' || c == '-') {
                upperNext = true;
            } else if (upperNext) {
                sb.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private long alignUp(long value, int alignment) {
        return ((value + alignment - 1L) / alignment) * alignment;
    }

    // ---------------------------------------------------------------------
    // Internal data structures
    // ---------------------------------------------------------------------
    private record ComponentLayout(LayoutType type, long sizeOverride) {}
    private enum LayoutType { SEQUENTIAL, PADDING, EXPLICIT }
    private record FieldAttrs(int size, int offset, int alignment) {}
    private static final class GeneratedField {
        final String name; final FieldType ft; final long offset; final long size; final int alignment;
        GeneratedField(String n, FieldType ft, long off, long sz, int a) { this.name = n; this.ft = ft; this.offset = off; this.size = sz; this.alignment = a; }
    }
    // New structure to remember composite fields for slice handle generation
    private static final class CompositeFieldInfo {
        final String originalName; // e.g., position
        final String typeFqn;      // e.g., com.foo.PositionComponent
        final long baseOffset;     // offset where composite starts
        CompositeFieldInfo(String originalName, String typeFqn, long baseOffset) {this.originalName = originalName; this.typeFqn = typeFqn; this.baseOffset = baseOffset; }
    }
    private record LocalDescriptor(List<GeneratedField> fields, long totalSize, int maxAlignment) {}
    // Map component FQN -> list of composite field infos (filled during layout)
    private final Map<String, List<CompositeFieldInfo>> compositeFieldMap = new HashMap<>();
}
