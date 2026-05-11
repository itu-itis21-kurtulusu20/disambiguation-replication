package com.example.llmdyn.service;

import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ProjectContextService {

    private final Path projectRoot;

    public ProjectContextService() {
        // Initialize projectRoot to the current working directory
        this.projectRoot = Paths.get("").toAbsolutePath();
    }

    public String getProjectContext() {
        StringBuilder context = new StringBuilder();

        // Add project structure
        context.append("Project Structure:\n");
        context.append(getProjectStructure());

        // Add existing source files
        context.append("\nExisting Source Files:\n");
        context.append(getSourceFiles());

        // Add dependencies from pom.xml
        context.append("\nProject Dependencies:\n");
        context.append(getPomDependencies());

        return context.toString();
    }

    private String getProjectStructure() {
        // Basic project structure info
        return "- Spring Boot application\n- Package: com.example.llmdyn\n- Main class: LlMDynApplication\n";
    }

    private String getSourceFiles() {
        StringBuilder files = new StringBuilder();
        try {
            Path srcPath = Paths.get("src/main/java/com/example/llmdyn");
            if (Files.exists(srcPath)) {
                Files.walk(srcPath)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> {
                            try {
                                files.append("File: ").append(path.getFileName()).append("\n");
                                files.append(Files.readString(path)).append("\n\n");
                            } catch (IOException e) {
                                // Log error
                            }
                        });
            }
        } catch (IOException e) {
            files.append("Error reading source files\n");
        }
        return files.toString();
    }

    private String getPomDependencies() {
        try {
            Path pomPath = Paths.get("pom.xml");
            if (Files.exists(pomPath)) {
                return Files.readString(pomPath);
            }
        } catch (IOException e) {
            // Log error
        }
        return "Could not read pom.xml";
    }

    private void addPomFile(StringBuilder context) {
        Path pomPath = projectRoot.resolve("pom.xml");
        if (Files.exists(pomPath)) {
            context.append("\n--- pom.xml (Key Dependencies) ---\n");
            try {
                String content = Files.readString(pomPath);

                // Extract Spring Boot version
                String parent = extractSection(content, "<parent>", "</parent>");
                String springBootVersion = extractSpringBootVersion(parent);
                if (!springBootVersion.isEmpty()) {
                    context.append("Spring Boot Version: ").append(springBootVersion).append("\n");

                    // Add import guidelines based on version
                    if (isSpringBoot3OrHigher(springBootVersion)) {
                        context.append("\nIMPORTANT: Use Jakarta EE imports (NOT javax):\n");
                        context.append("- Use jakarta.persistence.* (NOT javax.persistence.*)\n");
                        context.append("- Use jakarta.validation.* (NOT javax.validation.*)\n");
                        context.append("- Use jakarta.servlet.* (NOT javax.servlet.*)\n\n");
                    } else {
                        context.append("\nIMPORTANT: Use javax imports:\n");
                        context.append("- Use javax.persistence.*\n");
                        context.append("- Use javax.validation.*\n\n");
                    }
                }

                String dependencies = extractSection(content, "<dependencies>", "</dependencies>");
                String properties = extractSection(content, "<properties>", "</properties>");

                context.append(properties).append("\n");
                context.append(dependencies).append("\n");
            } catch (Exception e) {
                context.append("Error reading pom.xml\n");
            }
        }
    }

    private boolean isSpringBoot3OrHigher(String version) {
        try {
            // Extract major version (e.g., "3.2.1" -> 3)
            String majorVersion = version.split("\\.")[0];
            return Integer.parseInt(majorVersion) >= 3;
        } catch (Exception e) {
            return false; // Default to javax if version parsing fails
        }
    }

    private String extractSpringBootVersion(String parentSection) {
        Pattern versionPattern = Pattern.compile("<version>([^<]+)</version>");
        Matcher matcher = versionPattern.matcher(parentSection);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private String extractSection(String content, String startTag, String endTag) {
        int start = content.indexOf(startTag);
        int end = content.indexOf(endTag);
        if (start != -1 && end != -1) {
            return content.substring(start, end + endTag.length());
        }
        return "";
    }

    public String getOptimizedProjectContext() {
        StringBuilder context = new StringBuilder();

        context.append("=== Project Structure (Sample Files) ===\n");

        try {
            // Add pom.xml
            addPomFile(context);

            // Get one sample from each category
            addSampleFile(context, "Entity", path -> path.toString().contains("/entity/"));
            addSampleFile(context, "Repository", path -> path.toString().contains("/repository/"));
            addSampleFile(context, "Service", path -> path.toString().contains("/service/"));
            addSampleFile(context, "Controller", path -> path.toString().contains("/controller/"));

        } catch (Exception e) {
            context.append("Error reading project files: ").append(e.getMessage()).append("\n");
        }

        return context.toString();
    }


    private void addSampleFile(StringBuilder context, String category, java.util.function.Predicate<Path> filter) throws Exception {
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(filter)
                    .findFirst()
                    .ifPresent(path -> {
                        context.append("\n--- Sample ").append(category).append(": ")
                                .append(projectRoot.relativize(path)).append(" ---\n");
                        try {
                            String content = Files.readString(path);
                            context.append(content).append("\n");
                        } catch (Exception e) {
                            context.append("Error reading file\n");
                        }
                    });
        }
    }
}