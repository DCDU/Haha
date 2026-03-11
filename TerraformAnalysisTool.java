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
    private static final Set<String> MODULE_META_ARGS = Set.of(
        "source", "version", "providers", "depends_on", "count", "for_each"
    );

    private final ObjectMapper mapper;

    public TerraformAnalysisTool() {
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String analyzeToJson(String filePath) throws Exception {
        return analyzeToJson(Paths.get(filePath));
    }

    public String analyzeToJson(Path filePath) throws Exception {
        Path workspace = resolveWorkspace(filePath);
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

    private static Path resolveWorkspace(Path filePath) {
        Path normalized = filePath.toAbsolutePath().normalize();
        return Files.isDirectory(normalized) ? normalized : normalized.getParent();
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

        private Analyzer(Path workspace) {
            this.workspace = workspace;
        }

        private Map<String, Object> analyzeModule(Path dir, String moduleName, Map<String, Object> inputs) throws Exception {
            return analyzeModuleInternal(dir, moduleName, inputs).json;
        }

        private ModuleAnalysis analyzeModuleInternal(Path dir, String moduleName, Map<String, Object> inputs) throws Exception {
            Path normalized = dir.toAbsolutePath().normalize();
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
                return new ModuleAnalysis(moduleJson, aggregatedData, aggregatedResources);
            } finally {
                stack.remove(normalized);
            }
        }

        private void mergeChildModules(Path dir, Map<String, Object> modules, List<Object> aggregatedData, List<Object> aggregatedResources) throws Exception {
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
                    if (Files.isDirectory(moduleDir)) {
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

        private List<String> listTfFiles(Path dir) throws IOException {
            try (var stream = Files.list(dir)) {
                return stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".tf"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> workspace.relativize(path.toAbsolutePath().normalize()).toString())
                    .collect(Collectors.toList());
            }
        }

        private String readCombinedTf(Path dir) throws IOException {
            List<Path> files;
            try (var stream = Files.list(dir)) {
                files = stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".tf"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .collect(Collectors.toList());
            }

            StringBuilder builder = new StringBuilder();
            for (Path file : files) {
                builder.append("# file: ").append(file.getFileName()).append('\n');
                builder.append(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                builder.append("\n\n");
            }
            return builder.toString();
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> mapValue(Object value) {
            return value instanceof Map<?, ?> ? (Map<String, Object>) value : Collections.emptyMap();
        }

        private Map<String, Object> parseWithVariables(String combinedTf, Map<String, Object> variables) throws Exception {
            HCLParser parser = new HCLParser();
            parser.setVariables(variables);
            PrintStream originalOut = System.out;
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try {
                System.setOut(new PrintStream(sink, true, StandardCharsets.UTF_8.name()));
                return parser.parse(combinedTf, true);
            } finally {
                System.setOut(originalOut);
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
        }
    }
}
