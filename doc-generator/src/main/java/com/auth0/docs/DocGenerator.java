package com.auth0.docs;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;
import com.github.javaparser.javadoc.description.JavadocDescription;
import com.github.javaparser.javadoc.description.JavadocDescriptionElement;
import com.github.javaparser.javadoc.description.JavadocInlineTag;
import com.google.gson.*;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.*;

/**
 * Generates an SDK documentation JSON file from Java source files.
 *
 * The output conforms to the Auth0 SDK Documentation JSON Schema and can be
 * fed directly into the docs site generator.
 *
 * Usage:
 *   DocGenerator <sourceDir> <outputFile> <version> [packageFilter] [mavenCoords]
 *
 * Arguments:
 *   sourceDir     - root of the Java sources (e.g. src/main/java)
 *   outputFile    - where to write the JSON  (e.g. build/sdk-docs/v1.json)
 *   version       - full version string       (e.g. 1.12.0)
 *   packageFilter - optional base package to restrict scanning (e.g. com.auth0)
 *   mavenCoords   - optional Maven artifact ID shown in meta (e.g. com.auth0:mvc-auth-commons)
 */
public class DocGenerator {

    // ─── Data models ──────────────────────────────────────────────────────────

    static class SdkClass {
        String id, title, kind, description, signature, category;
        SdkConstructor constructor;
        final List<SdkMember>   members    = new ArrayList<>();
        final List<SdkProperty> properties = new ArrayList<>();
        final List<SdkExample>  examples   = new ArrayList<>();
        boolean deprecated;
    }

    static class SdkConstructor {
        String signature;
        final List<SdkParam> parameters = new ArrayList<>();
    }

    static class SdkMember {
        String id, title, kind = "method", signature, description;
        final List<SdkParam>   parameters = new ArrayList<>();
        final List<SdkThrows>  throwsList = new ArrayList<>();
        final List<SdkExample> examples   = new ArrayList<>();
        SdkReturns returns;
        boolean deprecated;
    }

    static class SdkParam {
        String name, type, description;
        boolean optional;
    }

    static class SdkProperty {
        String name, type, description;
    }

    static class SdkReturns {
        String type, description;
    }

    static class SdkThrows {
        String name, type, description;
    }

    static class SdkExample {
        String title, language, code;
    }

    // ─── Entry point ──────────────────────────────────────────────────────────

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage: DocGenerator <sourceDir> <outputFile> <version> [packageFilter] [mavenCoords]");
            System.exit(1);
        }
        new DocGenerator(
            Paths.get(args[0]),
            Paths.get(args[1]),
            args[2],
            args.length > 3 ? args[3] : "",
            args.length > 4 ? args[4] : ""
        ).run();
    }

    private final Path   sourceDir;
    private final Path   outputFile;
    private final String version;
    private final String packageFilter;
    private final String mavenCoords;

    public DocGenerator(Path sourceDir, Path outputFile, String version,
                        String packageFilter, String mavenCoords) {
        this.sourceDir     = sourceDir;
        this.outputFile    = outputFile;
        this.version       = version;
        this.packageFilter = packageFilter;
        this.mavenCoords   = mavenCoords;
    }

    public void run() throws IOException {
        List<SdkClass> classes = collectClasses();

        Files.createDirectories(outputFile.getParent());
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (Writer w = Files.newBufferedWriter(outputFile)) {
            gson.toJson(buildJson(classes), w);
        }
        System.out.println("Written: " + outputFile.toAbsolutePath());
    }

    // ─── Source scanning ──────────────────────────────────────────────────────

    private List<SdkClass> collectClasses() throws IOException {
        List<SdkClass> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(sourceDir)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                  .sorted()
                  .forEach(p -> {
                      try { parseFile(p, result); }
                      catch (Exception e) {
                          System.err.println("Skipping " + p.getFileName() + ": " + e.getMessage());
                      }
                  });
        }
        return result;
    }

    private void parseFile(Path file, List<SdkClass> result) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(file.toFile());

        String pkg = cu.getPackageDeclaration()
            .map(pd -> pd.getNameAsString()).orElse("");
        if (!packageFilter.isEmpty() && !pkg.startsWith(packageFilter)) return;

        for (TypeDeclaration<?> type : cu.getTypes()) {
            if (!type.isPublic() || !(type instanceof ClassOrInterfaceDeclaration)) continue;

            ClassOrInterfaceDeclaration outer = (ClassOrInterfaceDeclaration) type;
            result.add(buildSdkClass(outer, null));

            // Public static nested classes (e.g. Builder)
            outer.getMembers().stream()
                 .filter(m -> m instanceof ClassOrInterfaceDeclaration)
                 .map(m -> (ClassOrInterfaceDeclaration) m)
                 .filter(n -> n.isPublic() && n.isStatic())
                 .forEach(nested -> result.add(buildSdkClass(nested, outer.getNameAsString())));
        }
    }

    // ─── Class model building ─────────────────────────────────────────────────

    private SdkClass buildSdkClass(ClassOrInterfaceDeclaration decl, String outerName) {
        SdkClass cls = new SdkClass();
        cls.title      = outerName != null ? outerName + "." + decl.getNameAsString() : decl.getNameAsString();
        cls.id         = toId(cls.title);
        cls.kind       = decl.isInterface() ? "interface" : "class";
        cls.deprecated = isDeprecated(decl);
        cls.description = javadocDescription(decl);
        cls.signature  = classSignature(decl);
        cls.category   = category(decl);

        // Constructor — pick the public one with the most parameters
        if (!decl.isInterface()) {
            decl.getConstructors().stream()
                .filter(ConstructorDeclaration::isPublic)
                .max(Comparator.comparingInt(c -> c.getParameters().size()))
                .ifPresent(ctor -> cls.constructor = buildConstructor(ctor));
        }

        // Members — public/protected methods, skip @VisibleForTesting
        Set<String> usedIds = new HashSet<>();
        for (MethodDeclaration m : decl.getMethods()) {
            if (!isIncluded(m, decl.isInterface())) continue;
            if (hasAnnotation(m, "VisibleForTesting"))  continue;
            SdkMember member = buildMember(m);
            member.id = uniqueId(member.id, usedIds);
            cls.members.add(member);
        }

        // Properties — public fields
        for (FieldDeclaration f : decl.getFields()) {
            if (!f.isPublic()) continue;
            for (VariableDeclarator var : f.getVariables()) {
                SdkProperty prop = new SdkProperty();
                prop.name        = var.getNameAsString();
                prop.type        = f.getElementType().asString();
                prop.description = javadocDescription(f);
                cls.properties.add(prop);
            }
        }

        // Class-level @example tags
        if (decl instanceof NodeWithJavadoc<?>) {
            ((NodeWithJavadoc<?>) decl).getJavadocComment().ifPresent(jc -> {
                for (JavadocBlockTag tag : jc.parse().getBlockTags()) {
                    if (tag.getType() == JavadocBlockTag.Type.UNKNOWN
                            && "example".equals(tag.getTagName())) {
                        SdkExample ex = parseExample(tag);
                        if (ex != null) cls.examples.add(ex);
                    }
                }
            });
        }

        return cls;
    }

    /** Returns true when the method should appear in the public docs. */
    private boolean isIncluded(MethodDeclaration m, boolean inInterface) {
        // In interfaces all non-private, non-default, non-static methods are the API surface.
        if (inInterface) return !m.isPrivate() && !m.isDefault() && !m.isStatic();
        return m.isPublic() || m.isProtected();
    }

    // ─── Constructor model ────────────────────────────────────────────────────

    private SdkConstructor buildConstructor(ConstructorDeclaration decl) {
        SdkConstructor ctor = new SdkConstructor();
        ctor.signature = "new " + decl.getNameAsString() + "("
            + decl.getParameters().stream()
                  .map(p -> p.getType().asString() + " " + p.getNameAsString())
                  .collect(Collectors.joining(", "))
            + ")";

        Map<String, String> paramDocs = paramTagsFrom(decl);
        for (com.github.javaparser.ast.body.Parameter p : decl.getParameters()) {
            SdkParam param = new SdkParam();
            param.name        = p.getNameAsString();
            param.type        = p.getType().asString();
            param.optional    = false;
            param.description = paramDocs.getOrDefault(p.getNameAsString(), "");
            ctor.parameters.add(param);
        }
        return ctor;
    }

    // ─── Member (method) model ────────────────────────────────────────────────

    private SdkMember buildMember(MethodDeclaration m) {
        SdkMember member = new SdkMember();
        member.title     = m.getNameAsString();
        member.id        = toId(m.getNameAsString());
        member.kind      = "method";
        member.deprecated = isDeprecated(m);
        member.signature = methodSignature(m);

        Optional<JavadocComment> jcOpt = m.getJavadocComment();
        if (jcOpt.isPresent()) {
            Javadoc javadoc = jcOpt.get().parse();
            member.description = descriptionText(javadoc.getDescription());

            Map<String, String> paramDocs  = new LinkedHashMap<>();
            Map<String, String> throwDocs  = new LinkedHashMap<>();
            String[]            returnDesc = {null};

            for (JavadocBlockTag tag : javadoc.getBlockTags()) {
                switch (tag.getType()) {
                    case PARAM:
                        tag.getName()
                           .filter(n -> !n.startsWith("<"))
                           .ifPresent(n -> paramDocs.put(n, descriptionText(tag.getContent())));
                        break;
                    case RETURN:
                        returnDesc[0] = descriptionText(tag.getContent());
                        break;
                    case THROWS:
                    case EXCEPTION:
                        tag.getName().ifPresent(n -> throwDocs.put(n, descriptionText(tag.getContent())));
                        break;
                    case DEPRECATED:
                        member.deprecated = true;
                        String note = descriptionText(tag.getContent());
                        if (!note.isEmpty()) {
                            String existing = member.description != null ? member.description : "";
                            member.description = existing.isEmpty()
                                ? "**Deprecated.** " + note
                                : existing + "\n\n**Deprecated.** " + note;
                        }
                        break;
                    case UNKNOWN:
                        if ("example".equals(tag.getTagName())) {
                            SdkExample ex = parseExample(tag);
                            if (ex != null) member.examples.add(ex);
                        }
                        break;
                    default:
                        break;
                }
            }

            populateParameters(member, m, paramDocs);
            populateReturns(member, m, returnDesc[0]);
            populateThrows(member, throwDocs);
        } else {
            // No Javadoc — still surface signature info
            populateParameters(member, m, Collections.emptyMap());
            populateReturns(member, m, null);
        }

        return member;
    }

    private void populateParameters(SdkMember member, MethodDeclaration m, Map<String, String> docs) {
        for (com.github.javaparser.ast.body.Parameter p : m.getParameters()) {
            SdkParam param = new SdkParam();
            param.name        = p.getNameAsString();
            param.type        = p.getType().asString();
            param.optional    = false;
            param.description = docs.getOrDefault(p.getNameAsString(), "");
            member.parameters.add(param);
        }
    }

    private void populateReturns(SdkMember member, MethodDeclaration m, String desc) {
        String retType = m.getType().asString();
        if ("void".equals(retType)) return;
        member.returns             = new SdkReturns();
        member.returns.type        = retType;
        member.returns.description = desc != null ? desc : "";
    }

    private void populateThrows(SdkMember member, Map<String, String> throwDocs) {
        for (Map.Entry<String, String> e : throwDocs.entrySet()) {
            SdkThrows t   = new SdkThrows();
            t.name        = e.getKey();
            t.type        = e.getKey();
            t.description = e.getValue();
            member.throwsList.add(t);
        }
    }

    // ─── Javadoc utilities ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String javadocDescription(Node decl) {
        if (!(decl instanceof NodeWithJavadoc<?>)) return "";
        return ((NodeWithJavadoc<?>) decl).getJavadocComment()
                   .map(jc -> descriptionText(jc.parse().getDescription()))
                   .orElse("");
    }

    /**
     * Converts a JavadocDescription to plain Markdown-friendly text.
     * Processes inline tags ({@code}, {@link}) and strips HTML tags.
     */
    private String descriptionText(JavadocDescription desc) {
        if (desc == null) return "";
        StringBuilder sb = new StringBuilder();

        for (JavadocDescriptionElement el : desc.getElements()) {
            if (el instanceof JavadocInlineTag) {
                JavadocInlineTag tag     = (JavadocInlineTag) el;
                String           content = tag.getContent().trim();
                switch (tag.getType()) {
                    case CODE:
                    case VALUE:
                    case LITERAL:
                        sb.append("`").append(content).append("`");
                        break;
                    case LINK:
                    case LINKPLAIN:
                        // "com.auth0.Foo#barMethod" → "`Foo.barMethod`"
                        // "Foo#bar()" → "`Foo.bar()`"
                        String ref = content.split("\\s+")[0]  // strip trailing description text
                                            .replace("#", ".");
                        // strip package prefix — keep from last uppercase-starting segment
                        ref = ref.replaceAll("(?:[a-z][a-z0-9]*\\.)+([A-Z])", "$1");
                        sb.append("`").append(ref).append("`");
                        break;
                    default:
                        sb.append(content);
                }
            } else {
                String text = el.toText()
                    .replaceAll("(?i)<p\\s*/?>",               "\n\n")
                    .replaceAll("(?i)<br\\s*/?>",              "\n")
                    .replaceAll("(?i)<strong>(.*?)</strong>",  "**$1**")
                    .replaceAll("(?i)<em>(.*?)</em>",          "_$1_")
                    .replaceAll("<[^>]+>",                     "")
                    .replace("&lt;",  "<")
                    .replace("&gt;",  ">")
                    .replace("&amp;", "&")
                    .replace("&nbsp;"," ");
                sb.append(text);
            }
        }

        return sb.toString()
            .trim()
            // Collapse source-indentation whitespace on wrapped lines into a single space,
            // but preserve intentional paragraph breaks (double newline).
            .replaceAll("(?<!\n)[ \t]*\n[ \t]*(?!\n)", " ")
            .replaceAll("\n{3,}", "\n\n");
    }

    private SdkExample parseExample(JavadocBlockTag tag) {
        String raw = tag.getContent().toText();
        int newline = raw.indexOf('\n');
        SdkExample ex = new SdkExample();
        ex.language = "java";
        if (newline == -1) {
            ex.title = raw.trim();
            ex.code  = "";
        } else {
            ex.title = raw.substring(0, newline).trim();
            ex.code  = normalizeIndent(raw.substring(newline + 1));
        }
        return ex.title.isEmpty() && ex.code.isEmpty() ? null : ex;
    }

    private String normalizeIndent(String code) {
        String[] lines = code.split("\n", -1);
        int minIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') indent++;
                minIndent = Math.min(minIndent, indent);
            }
        }
        if (minIndent == Integer.MAX_VALUE) minIndent = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            sb.append(line.length() >= minIndent ? line.substring(minIndent) : line.trim());
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb.toString().trim();
    }

    private Map<String, String> paramTagsFrom(ConstructorDeclaration decl) {
        Map<String, String> result = new LinkedHashMap<>();
        decl.getJavadocComment().ifPresent(jc -> {
            for (JavadocBlockTag tag : jc.parse().getBlockTags()) {
                if (tag.getType() == JavadocBlockTag.Type.PARAM) {
                    tag.getName()
                       .filter(n -> !n.startsWith("<"))
                       .ifPresent(n -> result.put(n, descriptionText(tag.getContent())));
                }
            }
        });
        return result;
    }

    // ─── Signature helpers ────────────────────────────────────────────────────

    private String classSignature(ClassOrInterfaceDeclaration d) {
        StringBuilder sb = new StringBuilder();
        if (d.isPublic())                      sb.append("public ");
        if (d.isAbstract() && !d.isInterface()) sb.append("abstract ");
        if (d.isStatic())                      sb.append("static ");
        sb.append(d.isInterface() ? "interface " : "class ");
        sb.append(d.getNameAsString());

        if (!d.getTypeParameters().isEmpty()) {
            sb.append("<")
              .append(d.getTypeParameters().stream()
                       .map(tp -> tp.getNameAsString())
                       .collect(Collectors.joining(", ")))
              .append(">");
        }
        if (!d.getExtendedTypes().isEmpty()) {
            sb.append(" extends ")
              .append(d.getExtendedTypes().stream().map(t -> t.asString()).collect(Collectors.joining(", ")));
        }
        if (!d.getImplementedTypes().isEmpty()) {
            sb.append(" implements ")
              .append(d.getImplementedTypes().stream().map(t -> t.asString()).collect(Collectors.joining(", ")));
        }
        return sb.toString();
    }

    private String methodSignature(MethodDeclaration m) {
        StringBuilder sb = new StringBuilder();
        if (m.isPublic())    sb.append("public ");
        else if (m.isProtected()) sb.append("protected ");
        if (m.isStatic())    sb.append("static ");
        sb.append(m.getType().asString())
          .append(" ")
          .append(m.getNameAsString())
          .append("(")
          .append(m.getParameters().stream()
                   .map(p -> p.getType().asString() + " " + p.getNameAsString())
                   .collect(Collectors.joining(", ")))
          .append(")");

        if (!m.getThrownExceptions().isEmpty()) {
            sb.append(" throws ")
              .append(m.getThrownExceptions().stream()
                       .map(e -> e.asString())
                       .collect(Collectors.joining(", ")));
        }
        return sb.toString();
    }

    // ─── Classification helpers ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private boolean isDeprecated(Node decl) {
        if (decl instanceof NodeWithAnnotations<?>) {
            if (((NodeWithAnnotations<?>) decl).getAnnotations().stream()
                    .anyMatch(a -> a.getNameAsString().equals("Deprecated"))) return true;
        }
        if (decl instanceof NodeWithJavadoc<?>) {
            return ((NodeWithJavadoc<?>) decl).getJavadocComment()
                       .map(jc -> jc.parse().getBlockTags().stream()
                                    .anyMatch(t -> t.getType() == JavadocBlockTag.Type.DEPRECATED))
                       .orElse(false);
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean hasAnnotation(Node decl, String name) {
        if (!(decl instanceof NodeWithAnnotations<?>)) return false;
        return ((NodeWithAnnotations<?>) decl).getAnnotations().stream()
                   .anyMatch(a -> a.getNameAsString().equals(name));
    }

    private String category(ClassOrInterfaceDeclaration d) {
        if (d.isInterface()) return "Interfaces";
        boolean isException = d.getExtendedTypes().stream()
            .anyMatch(t -> t.getNameAsString().endsWith("Exception")
                        || t.getNameAsString().endsWith("Error"));
        if (isException)    return "Exceptions";
        if (d.isAbstract()) return "Utilities";
        return "Core";
    }

    // ─── Naming utilities ─────────────────────────────────────────────────────

    /**
     * Converts a Java class/method name to a kebab-case page ID.
     *   "AuthenticationController.Builder" → "authentication-controller-builder"
     *   "buildAuthorizeUrl"                → "build-authorize-url"
     *   "isAPIError"                       → "is-api-error"
     */
    private String toId(String name) {
        return name
            .replace(".", "-")
            .replaceAll("([a-z])([A-Z])",   "$1-$2")   // camelCase boundary
            .replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2") // consecutive-uppercase boundary (e.g. API→A-PI)
            .toLowerCase()
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");
    }

    /** Returns a unique ID by appending -2, -3, … when base is already taken. */
    private String uniqueId(String base, Set<String> used) {
        if (used.add(base)) return base;
        int i = 2;
        while (!used.add(base + "-" + i)) i++;
        return base + "-" + i;
    }

    // ─── JSON assembly ────────────────────────────────────────────────────────

    private JsonObject buildJson(List<SdkClass> classes) {
        JsonObject root = new JsonObject();
        root.add("meta",       buildMeta());
        root.add("navigation", buildNavigation(classes));
        root.add("pages",      buildPages(classes));
        return root;
    }

    private JsonObject buildMeta() {
        JsonObject meta = new JsonObject();
        if (!mavenCoords.isEmpty()) meta.addProperty("package",     mavenCoords);
        meta.addProperty("version",     version);
        meta.addProperty("status",      "active");
        meta.addProperty("generatedAt", Instant.now().toString());
        return meta;
    }

    private JsonArray buildNavigation(List<SdkClass> classes) {
        // Preserve insertion order and define section priority
        Map<String, List<SdkClass>> grouped = new LinkedHashMap<>();
        for (String section : Arrays.asList("Core", "Interfaces", "Utilities", "Exceptions")) {
            grouped.put(section, new ArrayList<>());
        }
        for (SdkClass cls : classes) {
            grouped.computeIfAbsent(cls.category, k -> new ArrayList<>()).add(cls);
        }

        JsonArray nav = new JsonArray();
        grouped.forEach((section, items) -> {
            if (items.isEmpty()) return;
            JsonObject sec  = new JsonObject();
            JsonArray  navItems = new JsonArray();
            sec.addProperty("section", section);
            for (SdkClass cls : items) {
                JsonObject item = new JsonObject();
                item.addProperty("id",    cls.id);
                item.addProperty("title", cls.title);
                item.addProperty("kind",  cls.kind);
                navItems.add(item);
            }
            sec.add("items", navItems);
            nav.add(sec);
        });
        return nav;
    }

    private JsonObject buildPages(List<SdkClass> classes) {
        JsonObject pages = new JsonObject();
        for (SdkClass cls : classes) pages.add(cls.id, buildPage(cls));
        return pages;
    }

    private JsonObject buildPage(SdkClass cls) {
        JsonObject page = new JsonObject();
        page.addProperty("id",    cls.id);
        page.addProperty("title", cls.title);
        page.addProperty("kind",  cls.kind);
        if (!cls.description.isEmpty()) page.addProperty("description", cls.description);
        if (cls.signature != null && !cls.signature.isEmpty()) page.addProperty("signature", cls.signature);
        if (cls.constructor != null && !cls.constructor.parameters.isEmpty()) page.add("constructor", constructorJson(cls.constructor));
        if (!cls.properties.isEmpty()) page.add("properties", propertiesJson(cls.properties));
        if (!cls.members.isEmpty())    page.add("members",    membersJson(cls.members));
        if (!cls.examples.isEmpty())   page.add("examples",   examplesJson(cls.examples));
        return page;
    }

    private JsonObject constructorJson(SdkConstructor ctor) {
        JsonObject obj = new JsonObject();
        obj.addProperty("signature", ctor.signature);
        if (!ctor.parameters.isEmpty()) obj.add("parameters", parametersJson(ctor.parameters));
        return obj;
    }

    private JsonArray propertiesJson(List<SdkProperty> properties) {
        JsonArray arr = new JsonArray();
        for (SdkProperty p : properties) {
            JsonObject o = new JsonObject();
            o.addProperty("name",        p.name);
            o.addProperty("type",        p.type);
            o.addProperty("description", p.description != null ? p.description : "");
            arr.add(o);
        }
        return arr;
    }

    private JsonArray membersJson(List<SdkMember> members) {
        JsonArray arr = new JsonArray();
        for (SdkMember m : members) arr.add(memberJson(m));
        return arr;
    }

    private JsonObject memberJson(SdkMember m) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id",    m.id);
        obj.addProperty("title", m.title);
        obj.addProperty("kind",  m.kind);
        if (m.signature  != null) obj.addProperty("signature",  m.signature);
        if (m.description != null && !m.description.isEmpty()) obj.addProperty("description", m.description);
        if (!m.parameters.isEmpty())  obj.add("parameters", parametersJson(m.parameters));
        if (m.returns != null)        obj.add("returns",    returnsJson(m.returns));
        if (!m.throwsList.isEmpty())  obj.add("throws",     throwsJson(m.throwsList));
        if (!m.examples.isEmpty())    obj.add("examples",   examplesJson(m.examples));
        return obj;
    }

    private JsonArray parametersJson(List<SdkParam> params) {
        JsonArray arr = new JsonArray();
        for (SdkParam p : params) {
            JsonObject o = new JsonObject();
            o.addProperty("name",        p.name);
            o.addProperty("type",        p.type);
            o.addProperty("optional",    p.optional);
            o.addProperty("description", p.description != null ? p.description : "");
            arr.add(o);
        }
        return arr;
    }

    private JsonObject returnsJson(SdkReturns r) {
        JsonObject o = new JsonObject();
        o.addProperty("type", r.type);
        if (r.description != null && !r.description.isEmpty()) o.addProperty("description", r.description);
        return o;
    }

    private JsonArray throwsJson(List<SdkThrows> throwsList) {
        JsonArray arr = new JsonArray();
        for (SdkThrows t : throwsList) {
            JsonObject o = new JsonObject();
            o.addProperty("name",        t.name);
            o.addProperty("type",        t.type);
            o.addProperty("description", t.description != null ? t.description : "");
            arr.add(o);
        }
        return arr;
    }

    private JsonArray examplesJson(List<SdkExample> examples) {
        JsonArray arr = new JsonArray();
        for (SdkExample ex : examples) {
            JsonObject o = new JsonObject();
            if (ex.title != null && !ex.title.isEmpty()) o.addProperty("title", ex.title);
            o.addProperty("language", ex.language != null ? ex.language : "java");
            o.addProperty("code",     ex.code    != null ? ex.code     : "");
            arr.add(o);
        }
        return arr;
    }
}
