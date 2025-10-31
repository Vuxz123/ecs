package com.ethnicthv.ecs.processor;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
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
        "com.ethnicthv.ecs.core.components.Component.Layout"
})
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class ComponentProcessor extends AbstractProcessor {
    private Elements elementUtils;

    // Accumulate discovered component types to generate a central registry
    private final Set<String> collectedComponents = new LinkedHashSet<>(); // FQNs

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "ComponentProcessor init");
        this.elementUtils = processingEnv.getElementUtils();
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        processingEnv
                .getMessager()
                .printMessage(Diagnostic.Kind.NOTE, "ComponentProcessor: processing round with " + annotations.size() + " annotations");

        TypeElement layoutAnno = getType("com.ethnicthv.ecs.core.components.Component.Layout");
        TypeElement fieldAnno = getType("com.ethnicthv.ecs.core.components.Component.Field");

        Set<TypeElement> candidates = new LinkedHashSet<>();
        if (layoutAnno != null) {
            for (Element e : roundEnv.getElementsAnnotatedWith(layoutAnno)) {
                if (e.getKind().isClass()) candidates.add((TypeElement) e);
            }
        }
        if (fieldAnno != null) {
            for (Element e : roundEnv.getElementsAnnotatedWith(fieldAnno)) {
                Element owner = e.getEnclosingElement();
                if (owner.getKind().isClass()) candidates.add((TypeElement) owner);
            }
        }

        processingEnv
                .getMessager()
                .printMessage(Diagnostic.Kind.NOTE, "ComponentProcessor: found " + candidates.size() + " candidate component types");

        if (!candidates.isEmpty()) {
            TypeElement componentInterface = getType("com.ethnicthv.ecs.core.components.Component");
            TypeMirror componentMirror = componentInterface != null ? componentInterface.asType() : null;
            for (TypeElement compType : candidates) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "ComponentProcessor: found component: " + compType);

                if (componentMirror != null && !processingEnv.getTypeUtils().isAssignable(compType.asType(), componentMirror)) {
                    continue;
                }
                try {
                    // Generate Meta + Access for this component
                    generateForComponent(compType);
                    collectedComponents.add(compType.getQualifiedName().toString());
                } catch (IOException ex) {
                    error("Failed to generate meta/access for %s: %s", compType.getQualifiedName(), ex.getMessage());
                }
            }
        }

        // At the final round, emit the central registry
        if (roundEnv.processingOver() && !collectedComponents.isEmpty()) {
            try {
                generateCentralRegistry();
            } catch (IOException ex) {
                error("Failed to generate central registry: %s", ex.getMessage());
            }
        }

        return false;
    }

    private void generateForComponent(TypeElement compType) throws IOException {
        String pkg = elementUtils.getPackageOf(compType).getQualifiedName().toString();
        String simpleName = compType.getSimpleName().toString();
        String metaName = simpleName + "Meta";
        String accessName = simpleName + "Access";
        String fqnMeta = pkg.isEmpty() ? metaName : pkg + "." + metaName;
        String fqnAccess = pkg.isEmpty() ? accessName : pkg + "." + accessName;

        // Read layout annotation
        ComponentLayout layout = readLayout(compType);

        // Collect fields annotated with @Component.Field, skipping static
        List<VariableElement> fields = compType.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.FIELD)
                .map(VariableElement.class::cast)
                .filter(f -> f.getAnnotationMirrors().stream()
                        .anyMatch(am -> ((TypeElement) am.getAnnotationType().asElement()).getQualifiedName().contentEquals("com.ethnicthv.ecs.core.components.Component.Field")))
                .filter(f -> !f.getModifiers().contains(Modifier.STATIC))
                .collect(Collectors.toList());

        // Build field list and offsets
        List<GeneratedField> genFields = new ArrayList<>();
        long currentOffset = 0L;
        int maxAlignment = 1;
        if (layout.type == LayoutType.EXPLICIT) {
            fields.sort(Comparator.comparingInt(f -> readField(f).offset));
        }
        for (VariableElement ve : fields) {
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
            } else {
                offset = currentOffset;
            }
            genFields.add(new GeneratedField(name, ft, offset, size, alignment));
            currentOffset = offset + size;
            maxAlignment = Math.max(maxAlignment, alignment);
        }

        long totalSize;
        if (layout.sizeOverride > 0) {
            totalSize = layout.sizeOverride;
        } else if (layout.type == LayoutType.PADDING && !genFields.isEmpty()) {
            totalSize = alignUp(currentOffset, maxAlignment);
        } else {
            totalSize = currentOffset;
        }

        // Generate Meta class
        generateMetaSource(fqnMeta, pkg, metaName, compType, genFields, totalSize, layout);
        // Generate Access class
        generateAccessSource(fqnAccess, pkg, accessName, simpleName, genFields);
    }

    private void generateMetaSource(String fqn, String pkg, String metaName, TypeElement compType,
                                    List<GeneratedField> genFields, long totalSize, ComponentLayout layout) throws IOException {
        JavaFileObject file = processingEnv.getFiler().createSourceFile(fqn, compType);
        try (Writer w = file.openWriter()) {
            if (!pkg.isEmpty()) {
                w.write("package " + pkg + ";\n\n");
            }
            w.write("@SuppressWarnings(\"all\")\n");
            w.write("public final class " + metaName + " {\n");

            for (int i = 0; i < genFields.size(); i++) {
                String constName = "IDX_" + genFields.get(i).name.toUpperCase(Locale.ROOT);
                w.write("    public static final int " + constName + " = " + i + ";\n");
            }
            w.write("\n");

            w.write("    public static final com.ethnicthv.ecs.core.components.ComponentDescriptor DESCRIPTOR =\n");
            w.write("        new com.ethnicthv.ecs.core.components.ComponentDescriptor(\n");
            w.write("            " + compType.getQualifiedName() + ".class,\n");
            w.write("            " + totalSize + "L,\n");
            w.write("            java.util.List.of(\n");
            for (int i = 0; i < genFields.size(); i++) {
                GeneratedField f = genFields.get(i);
                w.write("                new com.ethnicthv.ecs.core.components.ComponentDescriptor.FieldDescriptor(\n");
                w.write("                    \"" + f.name + "\", ");
                w.write("com.ethnicthv.ecs.core.components.ComponentDescriptor.FieldType." + f.ft.enumName + ", ");
                w.write(f.offset + "L, " + f.size + "L, " + f.alignment + ")");
                if (i < genFields.size() - 1) w.write(",");
                w.write("\n");
            }
            w.write("            ),\n");
            w.write("            com.ethnicthv.ecs.core.components.Component.LayoutType." + layout.type.name() + "\n");
            w.write("        );\n\n");

            w.write("    public static com.ethnicthv.ecs.core.components.ComponentDescriptor descriptor() { return DESCRIPTOR; }\n\n");
            w.write("    private " + metaName + "() {}\n");
            w.write("}\n");
        }
    }

    private void generateAccessSource(String fqn, String pkg, String accessName, String simpleName,
                                      List<GeneratedField> genFields) throws IOException {
        JavaFileObject file = processingEnv.getFiler().createSourceFile(fqn);
        try (Writer w = file.openWriter()) {
            if (!pkg.isEmpty()) {
                w.write("package " + pkg + ";\n\n");
            }
            w.write("@SuppressWarnings(\"all\")\n");
            w.write("public final class " + accessName + " {\n");
            w.write("    private " + accessName + "() {}\n\n");
            w.write("    // Re-export field indices for convenience\n");
            w.write("    public static final int FIELD_COUNT = " + genFields.size() + ";\n");
            for (GeneratedField f : genFields) {
                String constName = "IDX_" + f.name.toUpperCase(Locale.ROOT);
                w.write("    public static final int " + constName + " = " + simpleName + "Meta." + constName + ";\n");
            }
            w.write("\n");
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

            // Per-field named getters/setters
            for (int i = 0; i < genFields.size(); i++) {
                GeneratedField f = genFields.get(i);
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

            w.write("}\n");
        }
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
                String meta = fqnComp + "Meta";
                w.write("        mgr.registerComponentWithDescriptor(" + fqnComp + ".class, " + meta + ".DESCRIPTOR);\n");
            }
            w.write("    }\n");
            w.write("}\n");
        }
    }

    private TypeElement getType(String fqn) {
        return processingEnv.getElementUtils().getTypeElement(fqn);
    }

    private void error(String fmt, Object... args) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format(Locale.ROOT, fmt, args));
    }

    private long alignUp(long value, int alignment) {
        return ((value + alignment - 1L) / alignment) * alignment;
    }

    private ComponentLayout readLayout(TypeElement type) {
        for (AnnotationMirror am : type.getAnnotationMirrors()) {
            Element el = am.getAnnotationType().asElement();
            if (!(el instanceof TypeElement te)) continue;
            if (!te.getQualifiedName().contentEquals("com.ethnicthv.ecs.core.components.Component.Layout")) continue;
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
        return new ComponentLayout(LayoutType.SEQUENTIAL, -1);
    }

    private FieldAttrs readField(VariableElement ve) {
        for (AnnotationMirror am : ve.getAnnotationMirrors()) {
            Element el = am.getAnnotationType().asElement();
            if (!(el instanceof TypeElement te)) continue;
            if (!te.getQualifiedName().contentEquals("com.ethnicthv.ecs.core.components.Component.Field")) continue;
            int size = 0;
            int offset = -1;
            int alignment = 0;
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : am.getElementValues().entrySet()) {
                String name = e.getKey().getSimpleName().toString();
                Object av = e.getValue().getValue();
                if (!(av instanceof Number n)) continue;
                switch (name) {
                    case "size" -> size = n.intValue();
                    case "offset" -> offset = n.intValue();
                    case "alignment" -> alignment = n.intValue();
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
                yield FieldType.INT; // keep going to report all errors
            }
        };
    }

    private record ComponentLayout(LayoutType type, long sizeOverride) {}
    private enum LayoutType { SEQUENTIAL, PADDING, EXPLICIT }
    private record FieldAttrs(int size, int offset, int alignment) {}
    private static class GeneratedField {
        final String name; final FieldType ft; final long offset; final long size; final int alignment;
        GeneratedField(String n, FieldType ft, long off, long sz, int a) { this.name=n; this.ft=ft; this.offset=off; this.size=sz; this.alignment=a; }
    }
    private enum FieldType {
        BYTE("BYTE",1,1), SHORT("SHORT",2,2), INT("INT",4,4), LONG("LONG",8,8), FLOAT("FLOAT",4,4), DOUBLE("DOUBLE",8,8), BOOLEAN("BOOLEAN",1,1), CHAR("CHAR",2,2);
        final String enumName; final long size; final int naturalAlignment;
        FieldType(String n, long s, int a) { this.enumName=n; this.size=s; this.naturalAlignment=a; }
    }
}

