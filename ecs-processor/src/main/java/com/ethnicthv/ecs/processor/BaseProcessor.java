package com.ethnicthv.ecs.processor;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import java.util.*;

/**
 * Shared base annotation processor providing common utilities for
 * QueryProcessor and ComponentProcessor to reduce duplication.
 */
public abstract class BaseProcessor extends AbstractProcessor {
    protected Elements elementUtils;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.elementUtils = processingEnv.getElementUtils();
    }

    // ---------------------------------------------------------------------
    // Messaging helpers
    // ---------------------------------------------------------------------
    protected void error(String fmt, Object... args) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                String.format(Locale.ROOT, fmt, args));
    }
    protected void note(String fmt, Object... args) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                String.format(Locale.ROOT, fmt, args));
    }

    // ---------------------------------------------------------------------
    // Element helpers
    // ---------------------------------------------------------------------
    protected TypeElement getTypeElement(String fqn) {
        return elementUtils.getTypeElement(fqn);
    }

    // ---------------------------------------------------------------------
    // Annotation mirror utilities (static for easy import)
    // ---------------------------------------------------------------------
    public static boolean hasAnnotation(Element e, String fqn) {
        for (AnnotationMirror am : e.getAnnotationMirrors()) {
            if (((TypeElement) am.getAnnotationType().asElement()).getQualifiedName().contentEquals(fqn)) return true;
        }
        return false;
    }
    public static AnnotationMirror getAnnotation(Element e, String fqn) {
        for (AnnotationMirror am : e.getAnnotationMirrors()) {
            if (((TypeElement) am.getAnnotationType().asElement()).getQualifiedName().contentEquals(fqn)) return am;
        }
        return null;
    }
    public static String readString(AnnotationMirror am, String name) {
        if (am == null) return null;
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : am.getElementValues().entrySet()) {
            if (e.getKey().getSimpleName().contentEquals(name)) return String.valueOf(e.getValue().getValue());
        }
        return null;
    }
    public static List<String> readTypeArray(AnnotationMirror am, String name) {
        List<String> out = new ArrayList<>();
        if (am == null) return out;
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : am.getElementValues().entrySet()) {
            if (!e.getKey().getSimpleName().contentEquals(name)) continue;
            Object v = e.getValue().getValue();
            if (v instanceof List<?> list) {
                for (Object o : list) {
                    Object ev = ((AnnotationValue) o).getValue();
                    String s = ev.toString();
                    out.add(s.endsWith(".class") ? s.substring(0, s.length() - 6) : s);
                }
            }
        }
        return out;
    }
    public static String readTypeClass(AnnotationMirror am, String name) {
        if (am == null) return null;
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : am.getElementValues().entrySet()) {
            if (!e.getKey().getSimpleName().contentEquals(name)) continue;
            String s = String.valueOf(e.getValue().getValue());
            if (s.endsWith(".class")) return s.substring(0, s.length() - 6);
            return s;
        }
        return null;
    }
    public static String readEnumConst(AnnotationMirror am, String name) {
        if (am == null) return null;
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : am.getElementValues().entrySet()) {
            if (!e.getKey().getSimpleName().contentEquals(name)) continue;
            Object v = e.getValue().getValue();
            return v.toString().substring(v.toString().lastIndexOf('.') + 1);
        }
        return null;
    }
}

