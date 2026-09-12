# Interview Notes — Enterprise Microservices Observability Platform

Senior-level interview questions and answers based specifically on this
project. Use these to explain design decisions and to reason about failure
scenarios out loud.

---

## Spring Boot

**How does the application start?**
`SpringApplication.run()` bootstraps the Spring `ApplicationContext`,
triggers auto-configuration (Boot scans `META-INF/spring/...AutoConfiguration.imports`
and conditionally activates beans based on the classpath — e.g. because
`spring-cloud-starter-netflix-eureka-client` is present, Eureka client beans
get created), starts the embedded Tomcat server, and finally publishes
`ApplicationReadyEvent`. In `employee-service`, `@EnableDiscoveryClient`
explicitly opts into registering with Eureka once the context is up.

**How does Actuator work?**
Actuator exposes operational endpoints (`/actuator/health`, `/actuator/info`,
`/actuator/prometheus`, …) as regular Spring MVC/WebFlux endpoints, backed by
`HealthIndicator`/`InfoContributor`/`MeterRegistry` beans. Which endpoints are
network-exposed is controlled by `management.endpoints.web.exposure.include`
— in this project set to `health,info,prometheus` (plus `gateway` for the
Gateway), so nothing else (like `/actuator/env`, which can leak secrets) is
reachable.

**How did you expose Prometheus metrics?**
Adding `micrometer-registry-prometheus` to the classpath auto-registers a
`PrometheusMeterRegistry`, which Actuator then serves at
`/actuator/prometheus` in the Prometheus text exposition format. Every
Micrometer meter (HTTP timers, JVM stats, custom counters) is exported there
automatically — no manual wiring.

---

## Eureka

**Why Eureka?**
It gives every service a place to register itself and to look up live
instances of other services **by logical name** instead of a fixed
host:port, which is what makes `lb://EMPLOYEE-SERVICE` in the Gateway work
regardless of how many replicas exist or where the scheduler placed them.

**How does service registration work?**
On startup, the Eureka client sends a REST `POST` with the instance's
metadata (app name, IP, port, health check URL) to
`http://eureka-server:8761/eureka/apps/{APP-NAME}`, then renews that
registration with a heartbeat every `lease-renewal-interval-in-seconds`
(10s here). If heartbeats stop, the server evicts the instance after the
lease duration expires.

**How does service discovery work?**
Clients periodically pull the full registry (or delta updates) from the
Eureka server into a local cache. Spring Cloud LoadBalancer then picks an
instance from that cached list — this is **client-side load balancing**;
the Gateway itself decides which instance to call, no separate load balancer
process is involved.

**What happens if Eureka is unavailable?**
Existing clients keep working using their last cached registry (Eureka
favors availability over consistency — AP in CAP terms). New instances can't
register, and stale/dead instances won't be evicted until Eureka comes back,
so the Gateway could keep routing to an instance that's actually gone
(mitigated by the Gateway's own timeout/retry and by readiness probes
removing dead pods from the K8s Service endpoint list independently).

---

## API Gateway

**Why Gateway?**
A single, well-known entry point for clients: centralizes routing, cuts
cross-cutting concerns (metrics, tracing, and — in a real deployment — auth
and rate limiting) out of every downstream service, and hides internal
service topology (clients never need to know `EMPLOYEE-SERVICE` even
exists as a separate deployable).

**How does Gateway route to Employee Service?**
The route predicate `Path=/api/employees/**` matches the incoming request;
the `StripPrefix=1` filter removes the `/api` segment so the downstream
service sees `/employees/...` (matching its `@RequestMapping("/employees")`);
the URI `lb://EMPLOYEE-SERVICE` tells the reactive load balancer to resolve
a live instance from Eureka and proxy the (now-rewritten) request there.

**What is `lb://`?**
A Spring Cloud Gateway/LoadBalancer virtual URI scheme. Instead of a real
host, the part after `lb://` is treated as a **service ID** to resolve
through the registered `ReactiveLoadBalancer` (backed here by Eureka) at
request time — this is what makes routing independent of pod IPs/replica
count.

**How would you add authentication?**
Add a Gateway-level `GlobalFilter` (or a per-route `filters:` entry) that
validates a JWT/OAuth2 bearer token before the request is proxied downstream
— e.g. Spring Cloud Gateway's `TokenRelay` filter with Spring Security
OAuth2 Resource Server, or a custom filter calling an identity provider's
introspection endpoint. Doing it once at the Gateway means employee-service
and project-service don't each need their own auth logic (though
defense-in-depth would still validate the token again downstream in a real
production system, not just trust the Gateway).

**How would you implement rate limiting?**
Spring Cloud Gateway ships a `RequestRateLimiter` filter backed by Redis
(token-bucket algorithm) — add a Redis Deployment/Service, add
`spring-boot-starter-data-redis-reactive`, and configure the filter with a
`KeyResolver` (e.g. per client IP or per API key) plus replenish
rate/burst capacity.

---

## Kubernetes

**Deployment vs Service**
A `Deployment` manages a set of Pod replicas — desired state, rolling
updates, self-healing restarts. A `Service` is a stable virtual IP + DNS
name that load-balances traffic across whichever Pods currently match its
label selector — Pods are ephemeral (new IP every restart), Services are not.

**ClusterIP vs NodePort**
`ClusterIP` (the default) is reachable only from inside the cluster — used
here for `eureka-server`, `employee-service`, `project-service`,
`otel-collector`, `jaeger` (internal-only). `NodePort` additionally opens a
fixed port (30000-32767 range) on every cluster node's own network
interface, so it's reachable from the host machine — used here for
`api-gateway` (30080), `prometheus` (30090), `grafana` (30030), and
`jaeger-query` (30686), the four things a human needs to reach directly.

**ConfigMap vs Secret**
Both externalize configuration from images; a `Secret` is base64-encoded
(not encrypted by default — access control is what actually protects it)
and intended for credentials/tokens, while a `ConfigMap` is plain text for
non-sensitive config. This project only needed ConfigMaps (Eureka URL, OTel
endpoint, Prometheus scrape rules, Grafana provisioning) — no credentials are
baked into any image or manifest; Grafana's admin password is set via a
plain env var here only because it's a local throwaway dev cluster, and in a
real deployment that value would move into a Secret.

**Readiness vs Liveness**
`readinessProbe` controls whether a Pod receives traffic (removed from the
Service's endpoints when failing, without being restarted) — for "I'm alive
but temporarily busy/still starting". `livenessProbe` controls whether
Kubernetes kills and restarts the container — for "I'm stuck/deadlocked and
restarting is the only recovery". Both are wired here to Spring Boot's own
`/actuator/health/readiness` and `/actuator/health/liveness` groups rather
than the aggregate `/actuator/health`, so a slow-but-recoverable dependency
doesn't get treated the same as a genuinely broken JVM.

**Resource requests vs limits**
`requests` is what the scheduler reserves/guarantees when placing a Pod on a
node (used for bin-packing decisions); `limits` is the hard ceiling — exceed
the memory limit and the container is OOMKilled, exceed the CPU limit and it
is throttled (not killed). This project uses 100m/256Mi requests and
500m/512Mi limits per Pod as a laptop-friendly balance between headroom and
not over-committing an 8-pod stack on one Docker Desktop VM.

**Rolling deployment**
`kubectl rollout restart deployment/<name>` (or any spec change) triggers
Kubernetes to create new Pods with the updated spec and terminate old ones
gradually (controlled by `maxSurge`/`maxUnavailable`, defaults used here),
only progressing once new Pods pass their readiness probe — this is exactly
how the `api-gateway`, `employee-service`, and `project-service` OTLP
endpoint fix was rolled out in this project with zero manual pod deletion.

**Scaling**
`kubectl scale deployment/employee-service --replicas=3 -n microservices`
— Eureka picks up the new instances via their independent registration, the
Gateway's load balancer sees them on its next registry refresh, and
Prometheus's Kubernetes service-discovery picks them up automatically
because discovery is annotation-based, not a static list.

**CrashLoopBackOff troubleshooting**
`kubectl logs -n microservices <pod> --previous` (the crashed container's
own logs, not the fresh restart), then `kubectl describe pod` for the exit
code/reason and recent Events. Common causes seen in practice: bad
config/property typo, missing env var/ConfigMap key, an unreachable
dependency the app fails fast on, or (not encountered here, but common) an
`OOMKilled` from a JVM heap that ignores the container's memory limit —
mitigated in this project with `-XX:MaxRAMPercentage=75.0` on every JVM
entrypoint.

---

## Prometheus

**Pull model**
Prometheus scrapes (pulls) `/metrics`-shaped HTTP endpoints on a timer,
rather than services pushing metrics to it — simpler service-side code (just
expose an endpoint), and Prometheus itself controls scrape cadence/load.

**Scraping**
Configured here via `kubernetes_sd_configs: role: pod` scoped to the
`microservices` namespace: Prometheus lists pods through the Kubernetes API,
keeps only those annotated `prometheus.io/scrape: "true"`, and scrapes
`prometheus.io/path` on `prometheus.io/port` — i.e. `/actuator/prometheus`
on each app's own port, every 15s.

**Labels**
Every scraped series gets automatic labels (`kubernetes_pod_name`,
`kubernetes_namespace`, `application` from the pod's `app` label via
relabeling) plus whatever Micrometer tags the app itself set — this project
tags every meter with `application: <spring.application.name>`, which is
what lets one Grafana panel show all 4 services side-by-side (`by
(application)`).

**Prometheus metrics vs Micrometer**
Micrometer is a vendor-neutral metrics *facade* inside the JVM (counters,
timers, gauges); the `micrometer-registry-prometheus` implementation renders
those meters into Prometheus's specific text exposition format. Swapping to
a different backend (e.g. Datadog) would mean changing the registry
dependency, not the application's metric-recording code.

**Alerting**
Not deployed in this local stack, but in production you'd add Prometheus
`alerting rules` (e.g. `rate(http_server_requests_seconds_count{status=~"5.."}[5m])
> threshold`) plus an Alertmanager Deployment to route firing alerts to
Slack/PagerDuty/email.

---

## Grafana

**Datasource**
Provisioned as code, not clicked in the UI: a ConfigMap
(`grafana-datasources`) mounted at
`/etc/grafana/provisioning/datasources/datasource.yaml` is read on
container start and registers the `Prometheus` datasource (`uid: prometheus`)
automatically.

**Dashboard**
Same pattern — a dashboard-provider ConfigMap tells Grafana to load any
`*.json` dashboard file from `/var/lib/grafana/dashboards`, and a second
ConfigMap supplies the actual dashboard JSON
(`microservices-overview.json`), so the "Microservices Observability
Overview" dashboard exists immediately on first boot.

**Querying Prometheus**
Grafana panels use PromQL through the datasource — e.g. the HTTP request
rate panel runs
`sum(rate(http_server_requests_seconds_count[1m])) by (application)`, and the
p95 latency panel runs
`histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, application))`,
which requires the histogram buckets to exist —
enabled here via `management.metrics.distribution.percentiles-histogram.http.server.requests: true`.

---

## OpenTelemetry

**Metrics vs logs vs traces**
Three complementary telemetry signals: **metrics** are aggregated numeric
time series (good for "how much/how often", cheap to store, great for
dashboards/alerts); **logs** are discrete timestamped events (good for "what
exactly happened", most detail, most storage); **traces** show the causal
path of a single request across services (good for "where did the time go /
which hop failed" — the thing metrics and logs alone can't reconstruct
across a distributed call chain).

**Trace / Span**
A **trace** is the end-to-end record of one logical request, identified by a
single `traceId`. A **span** is one unit of work within it (e.g. "Gateway
handling this HTTP call", "employee-service handling `/employees/1`"), each
with its own `spanId` and a `parentSpanId` linking it back up the call tree
— visible in this project's Jaeger traces as `api-gateway` spans that are
parents of the downstream `employee-service`/`project-service` spans.

**Context propagation**
The trace/span IDs travel between processes as HTTP headers (W3C
`traceparent`/`tracestate`) — Micrometer Observation instruments
Spring MVC/WebFlux and the Gateway's outbound HTTP client automatically, so
a request entering the Gateway and a request leaving it toward
employee-service share the same `traceId` with zero manual header code.

**OTLP**
OpenTelemetry Protocol — the standard wire format/API for exporting traces
(and metrics/logs) either over gRPC or HTTP/protobuf. This project exports
over **OTLP/HTTP** to the Collector (`http://otel-collector:4318/v1/traces`)
— see the note in `ARCHITECTURE.md` §10 on why gRPC wasn't used given the
pinned Spring Boot version.

**Collector**
`otel-collector` sits between the apps and Jaeger: it receives OTLP traces
(both gRPC:4317 and HTTP:4318 receivers open), batches them
(`processors: batch`), and re-exports them onward to Jaeger's own OTLP
receiver.

**Why use a Collector?**
Decouples app instrumentation from the tracing backend: apps only ever know
about "the Collector," so switching backends (Jaeger → Tempo → a vendor SaaS)
or adding processing (sampling, PII scrubbing, batching, fan-out to two
backends at once) never requires touching application code or redeploying
services — only the Collector's config changes.

---

## Jaeger

**Distributed tracing**
Jaeger stores and visualizes traces collected via OTLP — for this project,
it lets you see one client request fan out into
`api-gateway -> employee-service` (or `project-service`) as a single
timeline.

**Trace visualization**
The Jaeger UI's trace view is a waterfall/Gantt-style chart: each span drawn
as a horizontal bar positioned by start time and sized by duration, nested
under its parent — instantly shows which hop dominates total latency.

**Finding slow services**
Sort/filter traces by duration in the Jaeger UI, or drill into a specific
trace's waterfall to see which span (which service/operation) consumed the
most time; the "Compare traces" and per-service latency histograms narrow
down whether slowness is one outlier request or a systemic service-wide
issue.

---

## Production Scenarios

**1. Gateway returning 503**
Almost always "no healthy instance to route to." Check
`kubectl get pods -n microservices -l app=employee-service` (is it
Running/Ready?), then the Eureka registry
(`kubectl exec deploy/api-gateway -- wget -qO- http://eureka-server:8761/eureka/apps`)
to confirm the instance is actually registered and `UP`, not just pod-Running.

**2. Employee service down**
Requests to `/api/employees/**` fail (503 from the Gateway, or a timeout);
`/api/projects/**` keeps working (services are independently deployed/
scaled). Fix: `kubectl describe pod`/`kubectl logs` on the failing pod to
find the root cause, and in the meantime the Gateway could be configured
with a circuit breaker (Resilience4j) to fail fast with a clean error instead
of hanging.

**3. Eureka unavailable**
Already-registered instances keep serving traffic using cached registry data
on both the Gateway and client side; new deployments/scale-ups won't be
discoverable until Eureka recovers. Fix/mitigate: `kubectl describe
pod/eureka-server`, `kubectl logs`, and in production run Eureka as a
multi-node cluster (peer-aware replication) instead of the single-replica
setup used here for a local demo.

**4. High CPU**
Check `kubectl top pod -n microservices` (needs metrics-server) or the
Grafana "JVM CPU Usage" panel; compare against the 500m CPU `limits` — if a
pod is consistently throttled, either the limit needs raising or the code
path causing the spike (e.g. an expensive query, GC thrashing) needs fixing.
`kubectl exec` in and `jcmd`/`jstack` a thread dump if it's CPU pegged with
no obvious external cause.

**5. High memory**
Watch the Grafana "JVM Memory Used" panel against the 512Mi `limits` — if it
climbs and doesn't come back down after GC, that's a leak; if it's
OOMKilled, `kubectl describe pod` shows `Reason: OOMKilled`. The
`-XX:MaxRAMPercentage=75.0` flag on every JVM here keeps the heap itself
under the container limit so the *JVM* triggers GC pressure before the
*kernel* kills the container outright.

**6. High API latency**
Check the Grafana "HTTP Latency p95" panel to see which `application` is
slow, then pull a slow trace from Jaeger for that service to see which
specific span (DB call, downstream call, etc.) is the bottleneck — metrics
tell you *that* it's slow, traces tell you *where*.

**7. Increased 5xx errors**
Grafana's "HTTP 5xx Errors" panel shows which service and roughly when it
started; cross-reference `kubectl logs` for stack traces around that
timestamp, and check recent `kubectl rollout history` in case a bad
deployment is the cause (roll back with `kubectl rollout undo` if so).

**8. Kubernetes pod restarting**
`kubectl get pods -n microservices` shows a rising `RESTARTS` count;
`kubectl describe pod` shows the last termination reason (`OOMKilled`,
`Error`, liveness probe failure) and `kubectl logs --previous` shows what the
crashed instance printed right before dying.

**9. Prometheus not scraping**
Check `http://localhost:30090/targets` for the target's error message
(connection refused = wrong port/path or app not up; `context deadline
exceeded` = slow app or network policy blocking it); confirm the pod has the
`prometheus.io/scrape|port|path` annotations and that the `prometheus`
ServiceAccount's ClusterRole actually has `list/watch` on `pods` (RBAC
misconfiguration is the classic silent failure here — Prometheus simply
never discovers any targets, no obvious error banner).

**10. Missing traces**
This project's own real incident during setup: apps exported over HTTP to a
gRPC-only Collector port, giving "Connection reset". Diagnosis path: `kubectl
logs deploy/<app> | grep -i otlp` for exporter errors → confirm the
`management.otlp.tracing.endpoint` matches the Collector's actual receiver
protocol/port → confirm the Collector's own logs show the receiver started
(`otlpreceiver ... Starting HTTP/GRPC server`) → confirm the Collector's
exporter is actually reaching Jaeger (`kubectl logs deploy/otel-collector`)
→ finally check `curl http://localhost:30686/api/services` includes the
expected service names.
