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

        // Collect candidate component types from both annotations
        Set<TypeElement> candidates = collectCandidateComponentTypes(roundEnv);
        note("Found %d candidate component types", candidates.size());

        if (!candidates.isEmpty()) {
            TypeElement componentInterface = getTypeElement(COMPONENT_IFACE);
            TypeMirror componentMirror = componentInterface != null ? componentInterface.asType() : null;
            for (TypeElement compType : candidates) {
                note("Processing component type: %s", compType.getQualifiedName());
                if (componentMirror != null && !processingEnv.getTypeUtils().isAssignable(compType.asType(), componentMirror)) {
                    note("Skipping %s (not assignable to Component)", compType.getQualifiedName());
                    continue;
                }
                try {
                    generateForComponent(compType);
                    collectedComponents.add(compType.getQualifiedName().toString());
                } catch (IOException ex) {
                    error("Failed to generate meta/access for %s: %s", compType.getQualifiedName(), ex.getMessage());
                }
            }
        }

        // Final round: emit central registry once
        if (roundEnv.processingOver() && !collectedComponents.isEmpty()) {
            try {
                generateCentralRegistry();
            } catch (IOException ex) {
                error("Failed to generate central registry: %s", ex.getMessage());
            }
        }
        // Allow other processors to continue
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
    // Generation pipeline for one component
    // ---------------------------------------------------------------------
    private void generateForComponent(TypeElement compType) throws IOException {
        String pkg = elementUtils.getPackageOf(compType).getQualifiedName().toString();
        String simpleName = compType.getSimpleName().toString();

        // Layout info
        ComponentLayout layout = readLayout(compType);
        // Field list
        List<VariableElement> rawFields = collectAnnotatedFields(compType);
        List<GeneratedField> genFields = layoutAndSizeFields(layout, rawFields);
        long totalSize = computeTotalSize(layout, genFields);

        // Generate sources
        generateMetaSource(pkg, simpleName, compType, genFields, totalSize, layout);
        generateAccessSource(pkg, simpleName, genFields);
        generateHandleSource(pkg, simpleName, genFields); // typed handle
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

    private List<GeneratedField> layoutAndSizeFields(ComponentLayout layout, List<VariableElement> fields) {
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
            FieldType ft = mapFieldType(ve);
            int alignment = attrs.alignment > 0 ? attrs.alignment : ft.naturalAlignment;
            long size = attrs.size > 0 ? attrs.size : ft.size;

            long offset;
            if (layout.type == LayoutType.EXPLICIT && attrs.offset >= 0) {
                offset = attrs.offset;
            } else if (layout.type == LayoutType.PADDING) {
                offset = alignUp(currentOffset, alignment);
            } else { // SEQUENTIAL default
                offset = currentOffset;
            }
            out.add(new GeneratedField(name, ft, offset, size, alignment));
            currentOffset = offset + size;
            maxAlignment = Math.max(maxAlignment, alignment);
        }
        // Store max alignment inside layout calculation via size compute method if needed
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

    private void generateAccessSource(String pkg, String simpleName, List<GeneratedField> genFields) throws IOException {
        String accessName = simpleName + "Access";
        String fqn = pkg.isEmpty() ? accessName : pkg + "." + accessName;
        JavaFileObject file = processingEnv.getFiler().createSourceFile(fqn);
        try (Writer w = file.openWriter()) {
            if (!pkg.isEmpty()) w.write("package " + pkg + ";\n\n");
            w.write("@SuppressWarnings(\"all\")\n");
            w.write("public final class " + accessName + " {\n");
            w.write("    private " + accessName + "() {}\n\n");
            w.write("    public static final int FIELD_COUNT = " + genFields.size() + ";\n");
            for (GeneratedField f : genFields) {
                String constName = "IDX_" + f.name.toUpperCase(Locale.ROOT);
                w.write("    public static final int " + constName + " = " + simpleName + "Meta." + constName + ";\n");
            }
            w.write("\n");
            writeGenericAccessors(w);
            // Named per-field helpers
            for (GeneratedField f : genFields) {
                writeNamedAccessor(w, f);
            }
            w.write("}\n");
        }
    }

    private void writeGenericAccessors(Writer w) throws IOException {
        w.write("    // Type-safe generic getters\n");
        w.write("    public static float getFloat(com.ethnicthv.ecs.core.components.ComponentHandle h, int fieldIndex) { return h.getFloat(fieldIndex); }\n");
        w.write("    public static int getInt(com.ethnicthv.ecs.core.components.ComponentHandle h, int fieldIndex) { return h.getInt(fieldIndex); }\n");
        w.write("    public static long getLong(com.ethnicthv.ecs.core.components.ComponentHandle h, int fieldIndex) { return h.getLong(fieldIndex); }\n");
        w.write("    public static double getDouble(com.ethnicthv.ecs.core.components.ComponentHandle h, int fieldIndex) { return h.getDouble(fieldIndex); }\n");
        w.write("    public static boolean getBoolean(com.ethnicthv.ecs.core.components.ComponentHandle h, int fieldIndex) { return h.getBoolean(fieldIndex); }\n");
        w.write("    public static byte getByte(com.ethnicthv.ecs.core.components.ComponentHandle h, int fieldIndex) { return h.getByte(fieldIndex); }\n");
        w.write("    public static short getShort(com.ethnicthv.ecs.core.components.ComponentHandle h, int fieldIndex) { return h.getShort(fieldIndex); }\n");
        w.write("    public static char getChar(com.ethnicthv.ecs.core.components.ComponentHandle h, int fieldIndex) { return h.getChar(fieldIndex); }\n\n");
        w.write("    // Type-safe generic setters\n");
        w.write("    public static void setFloat(com.ethnicthv.ecs.core.components.ComponentHandle h, int fieldIndex, float v) { h.setFloat(fieldIndex, v); }\n");
        w.write("    public static void setInt(com.ethnicthv.ecs.core.components.ComponentHandle h, int fieldIndex, int v) { h.setInt(fieldIndex, v); }\n");
        w.write("    public static void setLong(com.ethnicthv.ecs.core.components.ComponentHandle h, int fieldIndex, long v) { h.setLong(fieldIndex, v); }\n");
        w.write("    public static void setDouble(com.ethnicthv.ecs.core.components.ComponentHandle h, int fieldIndex, double v) { h.setDouble(fieldIndex, v); }\n");
        w.write("    public static void setBoolean(com.ethnicthv.ecs.core.components.ComponentHandle h, int fieldIndex, boolean v) { h.setBoolean(fieldIndex, v); }\n");
        w.write("    public static void setByte(com.ethnicthv.ecs.core.components.ComponentHandle h, int fieldIndex, byte v) { h.setByte(fieldIndex, v); }\n");
        w.write("    public static void setShort(com.ethnicthv.ecs.core.components.ComponentHandle h, int fieldIndex, short v) { h.setShort(fieldIndex, v); }\n");
        w.write("    public static void setChar(com.ethnicthv.ecs.core.components.ComponentHandle h, int fieldIndex, char v) { h.setChar(fieldIndex, v); }\n\n");
    }

    private void writeNamedAccessor(Writer w, GeneratedField f) throws IOException {
        String methodSuffix = toCamelCase(f.name);
        String idxConst = "IDX_" + f.name.toUpperCase(Locale.ROOT);
        switch (f.ft) {
            case FLOAT -> {
                w.write("    public static float get" + methodSuffix + "(com.ethnicthv.ecs.core.components.ComponentHandle h) { return h.getFloat(" + idxConst + "); }\n");
                w.write("    public static void set" + methodSuffix + "(com.ethnicthv.ecs.core.components.ComponentHandle h, float v) { h.setFloat(" + idxConst + ", v); }\n");
            }
            case INT -> {
                w.write("    public static int get" + methodSuffix + "(com.ethnicthv.ecs.core.components.ComponentHandle h) { return h.getInt(" + idxConst + "); }\n");
                w.write("    public static void set" + methodSuffix + "(com.ethnicthv.ecs.core.components.ComponentHandle h, int v) { h.setInt(" + idxConst + ", v); }\n");
            }
            case LONG -> {
                w.write("    public static long get" + methodSuffix + "(com.ethnicthv.ecs.core.components.ComponentHandle h) { return h.getLong(" + idxConst + "); }\n");
                w.write("    public static void set" + methodSuffix + "(com.ethnicthv.ecs.core.components.ComponentHandle h, long v) { h.setLong(" + idxConst + ", v); }\n");
            }
            case DOUBLE -> {
                w.write("    public static double get" + methodSuffix + "(com.ethnicthv.ecs.core.components.ComponentHandle h) { return h.getDouble(" + idxConst + "); }\n");
                w.write("    public static void set" + methodSuffix + "(com.ethnicthv.ecs.core.components.ComponentHandle h, double v) { h.setDouble(" + idxConst + ", v); }\n");
            }
            case BOOLEAN -> {
                w.write("    public static boolean get" + methodSuffix + "(com.ethnicthv.ecs.core.components.ComponentHandle h) { return h.getBoolean(" + idxConst + "); }\n");
                w.write("    public static void set" + methodSuffix + "(com.ethnicthv.ecs.core.components.ComponentHandle h, boolean v) { h.setBoolean(" + idxConst + ", v); }\n");
            }
            case BYTE -> {
                w.write("    public static byte get" + methodSuffix + "(com.ethnicthv.ecs.core.components.ComponentHandle h) { return h.getByte(" + idxConst + "); }\n");
                w.write("    public static void set" + methodSuffix + "(com.ethnicthv.ecs.core.components.ComponentHandle h, byte v) { h.setByte(" + idxConst + ", v); }\n");
            }
            case SHORT -> {
                w.write("    public static short get" + methodSuffix + "(com.ethnicthv.ecs.core.components.ComponentHandle h) { return h.getShort(" + idxConst + "); }\n");
                w.write("    public static void set" + methodSuffix + "(com.ethnicthv.ecs.core.components.ComponentHandle h, short v) { h.setShort(" + idxConst + ", v); }\n");
            }
            case CHAR -> {
                w.write("    public static char get" + methodSuffix + "(com.ethnicthv.ecs.core.components.ComponentHandle h) { return h.getChar(" + idxConst + "); }\n");
                w.write("    public static void set" + methodSuffix + "(com.ethnicthv.ecs.core.components.ComponentHandle h, char v) { h.setChar(" + idxConst + ", v); }\n");
            }
        }
    }

    private void generateHandleSource(String pkg, String simpleName, List<GeneratedField> genFields) throws IOException {
        String handleName = simpleName + "Handle";
        String fqn = pkg.isEmpty() ? handleName : pkg + "." + handleName;
        JavaFileObject file = processingEnv.getFiler().createSourceFile(fqn);
        try (Writer w = file.openWriter()) {
            if (!pkg.isEmpty()) w.write("package " + pkg + ";\n\n");
            w.write("@SuppressWarnings(\"all\")\n");
            w.write("public final class " + handleName + " {\n");
            w.write("  private com.ethnicthv.ecs.core.components.ComponentHandle __internalHandle;\n");
            w.write("  public void __bind(com.ethnicthv.ecs.core.components.ComponentHandle h) { this.__internalHandle = h; }\n\n");
            String meta = simpleName + "Meta";
            for (GeneratedField f : genFields) {
                String prop = toCamelCase(f.name);
                String idxConst = meta + ".IDX_" + f.name.toUpperCase(Locale.ROOT);
                switch (f.ft) {
                    case FLOAT -> {
                        w.write("  public float get" + prop + "() { return __internalHandle.getFloat(" + idxConst + "); }\n");
                        w.write("  public void set" + prop + "(float v) { __internalHandle.setFloat(" + idxConst + ", v); }\n");
                    }
                    case INT -> {
                        w.write("  public int get" + prop + "() { return __internalHandle.getInt(" + idxConst + "); }\n");
                        w.write("  public void set" + prop + "(int v) { __internalHandle.setInt(" + idxConst + ", v); }\n");
                    }
                    case LONG -> {
                        w.write("  public long get" + prop + "() { return __internalHandle.getLong(" + idxConst + "); }\n");
                        w.write("  public void set" + prop + "(long v) { __internalHandle.setLong(" + idxConst + ", v); }\n");
                    }
                    case DOUBLE -> {
                        w.write("  public double get" + prop + "() { return __internalHandle.getDouble(" + idxConst + "); }\n");
                        w.write("  public void set" + prop + "(double v) { __internalHandle.setDouble(" + idxConst + ", v); }\n");
                    }
                    case BOOLEAN -> {
                        w.write("  public boolean is" + prop + "() { return __internalHandle.getBoolean(" + idxConst + "); }\n");
                        w.write("  public void set" + prop + "(boolean v) { __internalHandle.setBoolean(" + idxConst + ", v); }\n");
                    }
                    case BYTE -> {
                        w.write("  public byte get" + prop + "() { return __internalHandle.getByte(" + idxConst + "); }\n");
                        w.write("  public void set" + prop + "(byte v) { __internalHandle.setByte(" + idxConst + ", v); }\n");
                    }
                    case SHORT -> {
                        w.write("  public short get" + prop + "() { return __internalHandle.getShort(" + idxConst + "); }\n");
                        w.write("  public void set" + prop + "(short v) { __internalHandle.setShort(" + idxConst + ", v); }\n");
                    }
                    case CHAR -> {
                        w.write("  public char get" + prop + "() { return __internalHandle.getChar(" + idxConst + "); }\n");
                        w.write("  public void set" + prop + "(char v) { __internalHandle.setChar(" + idxConst + ", v); }\n");
                    }
                }
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
                w.write("        mgr.registerComponentWithDescriptor(" + fqnComp + ".class, " + fqnComp + "Meta.DESCRIPTOR);\n");
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

    private FieldType mapFieldType(VariableElement ve) {
        String tn = ve.asType().toString();
        return switch (tn) {
            case "byte", "java.lang.Byte" -> FieldType.BYTE;
            case "short", "java.lang.Short" -> FieldType.SHORT;
            case "int", "java.lang.Integer" -> FieldType.INT;
            case "long", "java.lang.Long" -> FieldType.LONG;
            case "float", "java.lang.Float" -> FieldType.FLOAT;
            case "double", "java.lang.Double" -> FieldType.DOUBLE;
            case "boolean", "java.lang.Boolean" -> FieldType.BOOLEAN;
            case "char", "java.lang.Character" -> FieldType.CHAR;
            default -> {
                error("Unsupported field type %s in %s", tn, ve.getEnclosingElement().getSimpleName());
                yield FieldType.INT; // fallback to continue processing
            }
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
    private enum FieldType {
        BYTE(1,1,"BYTE"), SHORT(2,2,"SHORT"), INT(4,4,"INT"), LONG(8,8,"LONG"), FLOAT(4,4,"FLOAT"), DOUBLE(8,8,"DOUBLE"), BOOLEAN(1,1,"BOOLEAN"), CHAR(2,2,"CHAR");
        final long size; final int naturalAlignment; final String enumName;
        FieldType(long sz, int na, String en) { this.size = sz; this.naturalAlignment = na; this.enumName = en; }
    }
}
