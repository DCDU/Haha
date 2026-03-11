import com.bertramlabs.plugins.hcl4j.HCLParser;
import com.bertramlabs.plugins.hcl4j.symbols.Symbol;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class TerraformAnalysisTool {
    private static final Object HCL_PARSE_LOCK = new Object();
    private static final int MAX_TF_FILES = 100;
    private static final Set<String> MODULE_META_ARGS = Set.of(
        "source", "version", "providers", "depends_on", "count", "for_each"
    );

    private final ObjectMapper mapper;

    public TerraformAnalysisTool() {
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String analyzeToJson(String filePath) throws Exception {
        return analyzePathToJson(Paths.get(filePath));
    }

    public String analyzeToJson(Path filePath) throws Exception {
        return analyzePathToJson(filePath);
    }

    public String analyzePathToJson(String filePath) throws Exception {
        return analyzePathToJson(Paths.get(filePath));
    }

    public String analyzePathToJson(Path filePath) throws Exception {
        Path workspace = resolveWorkspaceFromPath(filePath);
        return analyzeWorkspaceToJson(workspace);
    }

    public String analyzeWorkspaceToJson(String workspacePath) throws Exception {
        return analyzeWorkspaceToJson(Paths.get(workspacePath));
    }

    public String analyzeWorkspaceToJson(Path workspacePath) throws Exception {
        Path workspace = resolveWorkspaceDirectory(workspacePath);
        Analyzer analyzer = new Analyzer(workspace);
        List<Path> roots = discoverTemplateRoots(workspace);
        List<Object> templates = new ArrayList<>();
        for (Path root : roots) {
            templates.add(analyzer.analyzeModule(root, root.getFileName().toString(), Collections.emptyMap()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", Instant.now().toString());
        result.put("workspace", workspace.toString());
        result.put("library", Map.of(
            "name", "hcl4j",
            "artifact", "com.bertramlabs.plugins:hcl4j:0.9.4"
        ));
        result.put("templates", templates);
        return mapper.writeValueAsString(result);
    }

    private static Path resolveWorkspaceFromPath(Path filePath) throws TerraformAnalysisException {
        Path normalized = filePath.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            return resolveWorkspaceDirectory(normalized);
        }
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new InvalidInputPathException("Input path has no parent directory: " + normalized);
        }
        return resolveWorkspaceDirectory(parent);
    }

    private static Path resolveWorkspaceDirectory(Path workspacePath) throws TerraformAnalysisException {
        Path normalized = workspacePath.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            throw new InvalidInputPathException("Path does not exist: " + normalized);
        }
        if (!Files.isDirectory(normalized)) {
            throw new InvalidInputPathException("Workspace path is not a directory: " + normalized);
        }
        try {
            return normalized.toRealPath();
        } catch (IOException ex) {
            throw new InvalidInputPathException("Failed to resolve workspace path: " + normalized, ex);
        }
    }

    private static List<Path> discoverTemplateRoots(Path workspace) throws IOException {
        try (var stream = Files.list(workspace)) {
            return stream
                .filter(Files::isDirectory)
                .filter(path -> !path.getFileName().toString().startsWith("."))
                .filter(path -> Files.exists(path.resolve("main.tf")))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .collect(Collectors.toList());
        }
    }

    private static final class Analyzer {
        private final Path workspace;
        private final LinkedHashSet<Path> stack = new LinkedHashSet<>();
        private final Map<ModuleCacheKey, ModuleAnalysis> cache = new LinkedHashMap<>();
        private int parsedTfFileCount;

        private Analyzer(Path workspace) {
            this.workspace = workspace;
        }

        private Map<String, Object> analyzeModule(Path dir, String moduleName, Map<String, Object> inputs) throws TerraformAnalysisException {
            return analyzeModuleInternal(dir, moduleName, inputs).json;
        }

        private ModuleAnalysis analyzeModuleInternal(Path dir, String moduleName, Map<String, Object> inputs) throws TerraformAnalysisException {
            Path normalized = resolveRealDirectory(dir);
            ModuleCacheKey cacheKey;
            try {
                cacheKey = new ModuleCacheKey(normalized, cacheKeyInputs(inputs));
            } catch (IOException ex) {
                throw new TerraformParseException("Failed to build module cache key", ex);
            }
            ModuleAnalysis cached = cache.get(cacheKey);
            if (cached != null) {
                return cached.copyFor(moduleName);
            }
            if (!stack.add(normalized)) {
                Map<String, Object> cycle = Map.of(
                    "name", moduleName,
                    "path", workspace.relativize(normalized).toString(),
                    "status", "cycle",
                    "note", "cyclic module reference"
                );
                return new ModuleAnalysis(cycle, new ArrayList<>(), new ArrayList<>());
            }

            try {
                String combinedTf = readCombinedTf(normalized);
                Map<String, Object> firstPass = parseWithVariables(combinedTf, inputs);
                Map<String, Object> effectiveVariables = buildEffectiveVariables(firstPass, inputs);
                Map<String, Object> parsed = effectiveVariables.equals(inputs)
                    ? firstPass
                    : parseWithVariables(combinedTf, effectiveVariables);

                Map<String, Object> moduleJson = new LinkedHashMap<>();
                String modulePath = workspace.relativize(normalized).toString();
                moduleJson.put("name", moduleName);
                moduleJson.put("path", modulePath);
                moduleJson.put("files", listTfFiles(normalized));
                moduleJson.put("inputVariables", sanitize(inputs));
                moduleJson.put("variables", buildVariablesJson(parsed, inputs, effectiveVariables));
                moduleJson.put("locals", sanitize(mapValue(parsed.get("locals"))));
                List<Object> aggregatedData = flattenBlocks(mapValue(parsed.get("data")), "data", modulePath);
                List<Object> aggregatedResources = flattenBlocks(mapValue(parsed.get("resource")), "resource", modulePath);
                moduleJson.put("outputs", sanitize(mapValue(parsed.get("output"))));
                mergeChildModules(normalized, mapValue(parsed.get("module")), aggregatedData, aggregatedResources);
                moduleJson.put("data", aggregatedData);
                moduleJson.put("resources", aggregatedResources);
                ModuleAnalysis analysis = new ModuleAnalysis(moduleJson, aggregatedData, aggregatedResources);
                cache.put(cacheKey, analysis);
                return analysis.copyFor(moduleName);
            } finally {
                stack.remove(normalized);
            }
        }

        private void mergeChildModules(Path dir, Map<String, Object> modules, List<Object> aggregatedData, List<Object> aggregatedResources) throws TerraformAnalysisException {
            for (Map.Entry<String, Object> entry : modules.entrySet()) {
                Map<String, Object> attrs = mapValue(entry.getValue());
                Object source = attrs.get("source");

                Map<String, Object> childInputs = new LinkedHashMap<>();
                for (Map.Entry<String, Object> attr : attrs.entrySet()) {
                    if (!MODULE_META_ARGS.contains(attr.getKey())) {
                        childInputs.put(attr.getKey(), attr.getValue());
                    }
                }

                if (source instanceof String) {
                    Path moduleDir = dir.resolve((String) source).normalize();
                    if (isWorkspaceLocalDirectory(moduleDir)) {
                        ModuleAnalysis child = analyzeModuleInternal(moduleDir, entry.getKey(), childInputs);
                        aggregatedData.addAll(child.data);
                        aggregatedResources.addAll(child.resources);
                    }
                }
            }
        }

        private Map<String, Object> buildVariablesJson(Map<String, Object> parsed, Map<String, Object> providedInputs, Map<String, Object> effectiveVariables) {
            Map<String, Object> variables = new LinkedHashMap<>();
            Map<String, Object> definitions = mapValue(parsed.get("variable"));
            for (Map.Entry<String, Object> entry : definitions.entrySet()) {
                Map<String, Object> definition = mapValue(entry.getValue());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("definition", sanitize(definition));
                item.put("provided", providedInputs.containsKey(entry.getKey()));
                item.put("effectiveValue", sanitize(effectiveVariables.get(entry.getKey())));
                variables.put(entry.getKey(), item);
            }
            return variables;
        }

        private Map<String, Object> buildEffectiveVariables(Map<String, Object> parsed, Map<String, Object> providedInputs) {
            Map<String, Object> effective = new LinkedHashMap<>(providedInputs);
            Map<String, Object> definitions = mapValue(parsed.get("variable"));
            for (Map.Entry<String, Object> entry : definitions.entrySet()) {
                if (!effective.containsKey(entry.getKey())) {
                    Map<String, Object> definition = mapValue(entry.getValue());
                    if (definition.containsKey("default")) {
                        effective.put(entry.getKey(), definition.get("default"));
                    }
                }
            }
            return effective;
        }

        private List<Object> flattenBlocks(Map<String, Object> groups, String kind, String modulePath) {
            List<Object> items = new ArrayList<>();
            for (Map.Entry<String, Object> typeEntry : groups.entrySet()) {
                Map<String, Object> blocks = mapValue(typeEntry.getValue());
                for (Map.Entry<String, Object> blockEntry : blocks.entrySet()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("modulePath", modulePath);
                    item.put("kind", kind);
                    item.put("type", typeEntry.getKey());
                    item.put("name", blockEntry.getKey());
                    item.put("values", sanitize(blockEntry.getValue()));
                    items.add(item);
                }
            }
            return items;
        }

        private List<String> listTfFiles(Path dir) throws TerraformAnalysisException {
            try (var stream = Files.list(dir)) {
                return stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".tf"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> workspace.relativize(path.toAbsolutePath().normalize()).toString())
                    .collect(Collectors.toList());
            } catch (IOException ex) {
                throw new TerraformParseException("Failed to list Terraform files in directory: " + dir, ex);
            }
        }

        private String readCombinedTf(Path dir) throws TerraformAnalysisException {
            List<Path> files;
            try (var stream = Files.list(dir)) {
                files = stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".tf"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .collect(Collectors.toList());
            } catch (IOException ex) {
                throw new ParseLimitExceededException("Failed to list .tf files in directory: " + dir, ex);
            }

            parsedTfFileCount += files.size();
            if (parsedTfFileCount > MAX_TF_FILES) {
                throw new ParseLimitExceededException("Too many .tf files to analyze: " + parsedTfFileCount + " > " + MAX_TF_FILES);
            }

            StringBuilder builder = new StringBuilder();
            for (Path file : files) {
                try {
                    builder.append("# file: ").append(file.getFileName()).append('\n');
                    builder.append(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                    builder.append("\n\n");
                } catch (IOException ex) {
                    throw new ParseLimitExceededException("Failed to read .tf file: " + file, ex);
                }
            }
            return builder.toString();
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> mapValue(Object value) {
            return value instanceof Map<?, ?> ? (Map<String, Object>) value : Collections.emptyMap();
        }

        private Map<String, Object> parseWithVariables(String combinedTf, Map<String, Object> variables) throws TerraformAnalysisException {
            HCLParser parser = new HCLParser();
            parser.setVariables(variables);
            try {
                synchronized (HCL_PARSE_LOCK) {
                    PrintStream originalOut = System.out;
                    ByteArrayOutputStream sink = new ByteArrayOutputStream();
                    try {
                        System.setOut(new PrintStream(sink, true, StandardCharsets.UTF_8.name()));
                        return parser.parse(combinedTf, true);
                    } finally {
                        System.setOut(originalOut);
                    }
                }
            } catch (Exception ex) {
                throw new TerraformParseException("Failed to parse Terraform content", ex);
            }
        }

        private String cacheKeyInputs(Map<String, Object> inputs) throws IOException {
            return new ObjectMapper().writeValueAsString(sanitize(inputs));
        }

        private Path resolveRealDirectory(Path dir) throws TerraformAnalysisException {
            Path normalized = dir.toAbsolutePath().normalize();
            if (!Files.exists(normalized)) {
                throw new InvalidInputPathException("Module path does not exist: " + normalized);
            }
            if (!Files.isDirectory(normalized)) {
                throw new InvalidInputPathException("Module path is not a directory: " + normalized);
            }
            try {
                return normalized.toRealPath();
            } catch (IOException ex) {
                throw new InvalidInputPathException("Failed to resolve module path: " + normalized, ex);
            }
        }

        private boolean isWorkspaceLocalDirectory(Path dir) {
            try {
                Path realPath = dir.toRealPath();
                return realPath.startsWith(workspace) && Files.isDirectory(realPath);
            } catch (IOException ex) {
                return false;
            }
        }

        private Object sanitize(Object value) {
            return sanitize(value, Collections.newSetFromMap(new IdentityHashMap<>()));
        }

        private Object sanitize(Object value, Set<Object> seen) {
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
                return value;
            }
            if (seen.contains(value)) {
                return Map.of("status", "cycle");
            }
            if (value instanceof Symbol) {
                Symbol symbol = (Symbol) value;
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("status", "unresolved");
                json.put("symbolType", symbol.getClass().getSimpleName());
                json.put("expression", value.toString());
                if (symbol.getName() != null) {
                    json.put("name", symbol.getName());
                }
                return json;
            }

            seen.add(value);
            try {
                if (value instanceof Map<?, ?>) {
                    Map<String, Object> json = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                        json.put(String.valueOf(entry.getKey()), sanitize(entry.getValue(), seen));
                    }
                    return json;
                }
                if (value instanceof Collection<?>) {
                    List<Object> json = new ArrayList<>();
                    for (Object item : (Collection<?>) value) {
                        json.add(sanitize(item, seen));
                    }
                    return json;
                }
                return Map.of(
                    "status", "unresolved",
                    "javaType", value.getClass().getName(),
                    "value", String.valueOf(value)
                );
            } finally {
                seen.remove(value);
            }
        }

        private static final class ModuleAnalysis {
            private final Map<String, Object> json;
            private final List<Object> data;
            private final List<Object> resources;

            private ModuleAnalysis(Map<String, Object> json, List<Object> data, List<Object> resources) {
                this.json = json;
                this.data = data;
                this.resources = resources;
            }

            private ModuleAnalysis copyFor(String moduleName) {
                Map<String, Object> jsonCopy = new LinkedHashMap<>(json);
                jsonCopy.put("name", moduleName);
                return new ModuleAnalysis(jsonCopy, new ArrayList<>(data), new ArrayList<>(resources));
            }
        }

        private static final class ModuleCacheKey {
            private final Path path;
            private final String inputsKey;

            private ModuleCacheKey(Path path, String inputsKey) {
                this.path = path;
                this.inputsKey = inputsKey;
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj) {
                    return true;
                }
                if (!(obj instanceof ModuleCacheKey)) {
                    return false;
                }
                ModuleCacheKey other = (ModuleCacheKey) obj;
                return path.equals(other.path) && inputsKey.equals(other.inputsKey);
            }

            @Override
            public int hashCode() {
                return 31 * path.hashCode() + inputsKey.hashCode();
            }
        }
    }

    public static class TerraformAnalysisException extends Exception {
        public TerraformAnalysisException(String message) {
            super(message);
        }

        public TerraformAnalysisException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class InvalidInputPathException extends TerraformAnalysisException {
        public InvalidInputPathException(String message) {
            super(message);
        }

        public InvalidInputPathException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class ParseLimitExceededException extends TerraformAnalysisException {
        public ParseLimitExceededException(String message) {
            super(message);
        }

        public ParseLimitExceededException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class TerraformParseException extends TerraformAnalysisException {
        public TerraformParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
