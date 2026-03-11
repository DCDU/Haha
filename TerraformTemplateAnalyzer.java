import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TerraformTemplateAnalyzer {
    public static void main(String[] args) throws Exception {
        Path inputPath = args.length > 0
            ? Paths.get(args[0]).toAbsolutePath().normalize()
            : Paths.get(".").toAbsolutePath().normalize();

        TerraformAnalysisTool tool = new TerraformAnalysisTool();
        String json = tool.analyzeToJson(inputPath);

        Path workspace = Files.isDirectory(inputPath) ? inputPath : inputPath.getParent();
        Path output = workspace.resolve("terraform-analysis-result.json");
        Files.write(output, json.getBytes(StandardCharsets.UTF_8));
        System.out.println("Wrote analysis to " + output);
    }
}
