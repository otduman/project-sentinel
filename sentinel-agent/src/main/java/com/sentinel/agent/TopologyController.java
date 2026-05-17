package com.sentinel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Returns the live infrastructure topology to the dashboard so the hex grid
 * can stop hardcoding service names. Two distinct outputs:
 *
 * <ul>
 *   <li>{@code services} — application services Prometheus is actively
 *       scraping. The dashboard renders these as hex cells.</li>
 *   <li>{@code infrastructure} — the observability stack itself (Prometheus,
 *       AlertManager, Grafana). The dashboard renders these as a compact
 *       status row at the top, not full hexes — they're the same in every
 *       deployment, not the thing being investigated.</li>
 * </ul>
 *
 * <p>Service discovery: walks the scrape-target list from Prometheus's
 * {@code /api/v1/targets}, grouping by {@code labels.job}. Infrastructure
 * status: hardcoded list, each entry health-checked directly via its own
 * "is it alive?" endpoint. Going through the agent's proxy avoids CORS
 * pain in the browser and gives the dashboard a single, consistent shape.
 */
@RestController
@RequestMapping("/api/topology")
public class TopologyController {

    /**
     * Service names treated as infrastructure rather than application targets.
     * If Prometheus is also scraping them (e.g. a job_name='prometheus' scrape),
     * they're filtered out of the service list so they don't appear twice.
     */
    private static final Set<String> INFRA_JOBS = Set.of("prometheus", "alertmanager", "grafana");

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${sentinel.proxy.prometheus-url}")
    private String prometheusUrl;

    @Value("${sentinel.proxy.alertmanager-url}")
    private String alertmanagerUrl;

    @Value("${sentinel.proxy.grafana-url:http://grafana:3000}")
    private String grafanaUrl;

    public record ServiceNode(String name, String instance, boolean healthy) { }

    public record InfraNode(String name, String url, boolean healthy) { }

    public record Topology(List<ServiceNode> services, List<InfraNode> infrastructure) { }

    @GetMapping
    public Topology topology() {
        return new Topology(discoverServices(), checkInfrastructure());
    }

    /**
     * Walks Prometheus active scrape targets and returns one entry per
     * job/instance pair. Empty list on any failure — the dashboard should
     * degrade gracefully to "no services discovered yet" rather than
     * crash.
     */
    private List<ServiceNode> discoverServices() {
        List<ServiceNode> out = new ArrayList<>();
        try {
            String body = restClient.get()
                    .uri(prometheusUrl + "/api/v1/targets?state=any")
                    .retrieve()
                    .body(String.class);
            JsonNode root = mapper.readTree(body);
            JsonNode active = root.path("data").path("activeTargets");
            if (!active.isArray()) return out;
            for (JsonNode target : active) {
                String job = target.path("labels").path("job").asText("");
                if (job.isEmpty() || INFRA_JOBS.contains(job)) continue;
                String instance = target.path("labels").path("instance").asText("");
                boolean healthy = "up".equalsIgnoreCase(target.path("health").asText(""));
                out.add(new ServiceNode(job, instance, healthy));
            }
        } catch (Exception e) {
            // Swallow — empty list is the safe degraded state.
        }
        return out;
    }

    /**
     * Direct health checks against each infrastructure component. We deliberately
     * don't route these through Prometheus's targets API because we'd rather
     * the dashboard still show "Prometheus is down" when Prometheus is, in
     * fact, down — which is precisely when its own /api/v1/targets would be
     * unreachable.
     */
    private List<InfraNode> checkInfrastructure() {
        return List.of(
                new InfraNode("prometheus", prometheusUrl, probe(prometheusUrl + "/-/healthy")),
                new InfraNode("alertmanager", alertmanagerUrl, probe(alertmanagerUrl + "/-/healthy")),
                new InfraNode("grafana", grafanaUrl, probe(grafanaUrl + "/api/health"))
        );
    }

    /**
     * Lightweight health probe — any 2xx counts as alive. Falls back to false
     * on connection errors, timeouts, or non-2xx without distinguishing,
     * because the dashboard only needs a binary "should this dot be green"
     * answer.
     */
    private boolean probe(String url) {
        try {
            restClient.get().uri(url).retrieve().body(String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
