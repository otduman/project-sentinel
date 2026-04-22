package com.sentinel.agent;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Seeds the runbooks table from YAML files in classpath:runbooks/ on startup.
 * Existing runbooks are skipped (idempotent), so re-deploying does not wipe customisations.
 */
@Component
public class RunbookSeeder implements ApplicationRunner {

    private final RunbookRepository repository;

    public RunbookSeeder(RunbookRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:runbooks/*.yml");

        Yaml yaml = new Yaml();
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                Map<String, Object> data = yaml.load(in);
                String alertName = (String) data.get("alertName");
                if (alertName == null) continue;

                // Idempotent — skip if runbook already exists
                if (repository.findByAlertNameIgnoreCase(alertName).isPresent()) continue;

                Runbook runbook = new Runbook();
                runbook.setAlertName(alertName);
                runbook.setTitle((String) data.getOrDefault("title", alertName));
                runbook.setSeverity((String) data.getOrDefault("severity", "warning"));
                runbook.setDescription((String) data.getOrDefault("description", ""));

                Object stepsRaw = data.get("steps");
                if (stepsRaw instanceof List<?> list) {
                    runbook.setSteps(String.join("\n", list.stream().map(Object::toString).toList()));
                } else if (stepsRaw instanceof String s) {
                    runbook.setSteps(s);
                }

                runbook.setLastUpdated(Instant.now());
                repository.save(runbook);
                System.out.println("[RunbookSeeder] Loaded runbook: " + alertName);
            } catch (Exception e) {
                System.err.println("[RunbookSeeder] Failed to load runbook from " + resource.getFilename() + ": " + e.getMessage());
            }
        }
    }
}
