# Architecture — Enterprise Microservices Observability Platform

## ASCII Architecture Diagram

```
                                   ┌──────────────────────┐
                                   │        Client         │
                                   │ (curl / Postman /     │
                                   │  browser)              │
                                   └───────────┬───────────┘
                                               │  http://localhost:30080
                                               ▼
                          ┌─────────────────────────────────────┐
                          │            API GATEWAY               │
                          │   Spring Cloud Gateway  (8080)        │
                          │   /api/employees/** -> EMPLOYEE-SERVICE│
                          │   /api/projects/**  -> PROJECT-SERVICE│
                          └───────┬───────────────────┬──────────┘
                                  │                   │
                    lb://EMPLOYEE-SERVICE     lb://PROJECT-SERVICE
                                  │                   │
                                  ▼                   ▼
                    ┌───────────────────────┐ ┌───────────────────────┐
                    │  EMPLOYEE SERVICE      │ │  PROJECT SERVICE      │
                    │  Spring Boot (8081)    │ │  Spring Boot (8082)   │
                    └───────────┬───────────┘ └───────────┬───────────┘
                                  \                        /
                                   \                      /
                                    ▼                    ▼
                              ┌────────────────────────────┐
                              │        EUREKA SERVER         │
                              │   Service Discovery (8761)    │
                              └────────────────────────────┘

  All four Spring Boot apps also emit:

    Micrometer  ──► /actuator/prometheus  ──► Prometheus (30090) ──► Grafana (30030)
    OpenTelemetry (OTLP/gRPC, port 4317)  ──► OTel Collector ──► Jaeger (30686)
```

## 1. API Request Flow

1. A client calls `http://localhost:30080/api/employees` (or `/api/projects`).
2. Kubernetes NodePort forwards the request to the `api-gateway` Service, which
   load-balances across `api-gateway` pods.
3. Spring Cloud Gateway matches the `Path=/api/employees/**` (or `/projects/**`)
   predicate, rewrites the path to drop the `/api` prefix, and resolves
   `lb://EMPLOYEE-SERVICE` to a live instance address using the Eureka
   registry + Spring Cloud LoadBalancer (client-side load balancing).
4. The request is proxied to the resolved `employee-service` pod's REST
   controller, which delegates to the service layer, which reads/writes the
   in-memory repository.
5. The response DTO flows back through the Gateway to the client.
6. In parallel, every hop emits a Micrometer-tracked HTTP timer (visible in
   Prometheus/Grafana) and an OpenTelemetry span (visible in Jaeger), and the
   trace/span IDs are attached to the structured logs of both the Gateway and
   the downstream service, so a single request can be correlated across logs
   and traces.

## 2. API Gateway

Spring Cloud Gateway is a reactive, non-blocking edge proxy. It has two
routes defined in `application.yml`:

- `/api/employees/**` → `lb://EMPLOYEE-SERVICE`
- `/api/projects/**` → `lb://PROJECT-SERVICE`

`lb://` is a virtual scheme understood by the `ReactiveLoadBalancerClientFilter`:
instead of a fixed host, the Gateway asks the Spring Cloud LoadBalancer for a
live instance of the named Eureka service (`EMPLOYEE-SERVICE` /
`PROJECT-SERVICE`), so instance IPs are never hardcoded and scaling the
service to N replicas is transparent to the Gateway.

## 3. Eureka Service Discovery

`eureka-server` runs standalone (`register-with-eureka: false`,
`fetch-registry: false` — it is not itself a client). Every other service
(`employee-service`, `project-service`, `api-gateway`) is a Eureka client that:

- **Registers**: on startup, sends its application name, IP, and port to
  `http://eureka-server:8761/eureka/`, then sends a heartbeat/lease renewal
  every `lease-renewal-interval-in-seconds` (10s here) to stay marked "UP".
- **Discovers**: periodically fetches the full registry (list of
  application-name → instance list) so the Gateway's load balancer always has
  a fresh view of who is available.

If Eureka is briefly unavailable, existing registered clients keep working —
they cache the last known registry locally — but new instances can't
register and stale instances may not be evicted promptly (mitigated here by
disabling self-preservation and using a short lease interval, appropriate for
a local dev cluster, not for production).

## 4. Employee Service

Layered `controller → service → repository`, DTOs (`EmployeeRequest` for
input validation, `EmployeeResponse` for output) so the internal `Employee`
model is never serialized directly. Validation errors and "not found" cases
are centralized in a `@RestControllerAdvice` (`GlobalExceptionHandler`) that
returns a consistent JSON error shape with timestamp/status/path/messages.

## 5. Project Service

Mirrors Employee Service's structure with a `Project{id, name, description,
owner}` domain, its own repository/service/controller/DTOs/exception handler.

## 6. Docker

Each service has a multi-stage Dockerfile:
- **Build stage**: `maven:3.9.9-eclipse-temurin-21` compiles the jar
  (dependencies are pre-fetched with `dependency:go-offline` for better layer
  caching).
- **Runtime stage**: `eclipse-temurin:21-jre-alpine` — a minimal JRE-only
  image — copies just the built jar and runs it as a non-root `spring` user.

This keeps final images small and avoids shipping the JDK/Maven toolchain.

Images are distributed via a **local registry** (`registry:2`, published on
`localhost:5000`) rather than Kubernetes reading straight from Docker
Desktop's shared image store. Every Deployment pulls
`localhost:5000/<service>:latest` with `imagePullPolicy: Always`, so a pod
always gets whatever was most recently pushed — the pattern each service's
Jenkins pipeline uses (build → push → `kubectl rollout restart`). Docker
Desktop's Kubernetes node pulls from `localhost:5000` with no extra
insecure-registry configuration.

## 7. Kubernetes

Everything runs in the `microservices` namespace on Docker Desktop's built-in
single-node cluster. Deployments manage pod lifecycle/restarts; Services give
each Deployment a stable DNS name and load-balanced virtual IP; ConfigMaps
externalize configuration (Eureka URL, OTel endpoint, Prometheus scrape
config, Grafana provisioning, OTel Collector pipeline) so nothing is baked
into the images.

## 8. Prometheus

Prometheus uses Kubernetes service discovery (`role: pod`, scoped to the
`microservices` namespace) instead of a static target list — it lists all
pods via the Kubernetes API (using a ServiceAccount + ClusterRole granting
read-only access to pods/services/endpoints), keeps only pods annotated
`prometheus.io/scrape: "true"`, and scrapes `prometheus.io/path` on
`prometheus.io/port` (i.e. `/actuator/prometheus` on each app's own port).
This means a newly scaled-up replica is auto-discovered with no config change.

## 9. Grafana

Grafana is provisioned entirely from ConfigMaps mounted as files — a
datasource provisioning file (points at `http://prometheus:9090`, `uid:
prometheus`) and a dashboard-provider file pointing at
`/var/lib/grafana/dashboards`, where the dashboard JSON ConfigMap is also
mounted. Both are read on container start, so the datasource and dashboard
exist the moment Grafana becomes ready — no manual UI steps.

## 10. OpenTelemetry

Traces are produced using **Micrometer Tracing's OpenTelemetry bridge**
(`micrometer-tracing-bridge-otel`) plus the OTLP exporter
(`opentelemetry-exporter-otlp`), configured entirely via Spring Boot
properties (`management.otlp.tracing.endpoint`) — no Java agent is baked
into the image. Each service exports spans over **OTLP/HTTP** to
`http://otel-collector:4318/v1/traces`.

> Note: Spring Boot 3.3.x's built-in OTLP tracing auto-configuration only
> reliably honors the `management.otlp.tracing.transport=grpc` switch
> starting in **Spring Boot 3.4** (see `spring-projects/spring-boot#41213`);
> on 3.3.5 it silently keeps using the HTTP exporter regardless of that
> property, which — pointed at a gRPC-only port — produced "Connection
> reset" errors during initial verification. The fix was to export over
> OTLP/HTTP against the Collector's HTTP receiver (4318) instead of forcing
> gRPC on 4317. The Collector still exposes both receivers, so switching
> back to gRPC is a one-line change if the stack is upgraded to Boot 3.4+.

Because Spring Cloud Gateway and the HTTP clients used for the `lb://` calls
are auto-instrumented by Micrometer Observation, context (trace ID/span ID)
propagates automatically from the Gateway into the downstream service's
incoming request headers (W3C `traceparent` header), producing one
continuous trace per client request.

## 11. Jaeger

The OTel Collector receives OTLP traces from all four apps (gRPC on 4317 and
HTTP on 4318 receivers, both open), batches them, and forwards them via its
own OTLP/gRPC exporter to `jaeger:4317` (Jaeger's all-in-one image has a
native OTLP receiver, no separate collector component needed on the Jaeger
side). The Jaeger UI (query service) is exposed via a NodePort Service on
`30686`.

## 12. Health Checks

Every Spring Boot app enables `management.endpoint.health.probes.enabled:
true` plus `management.health.livenessstate/readinessstate.enabled: true`,
which auto-creates two health groups:
- `/actuator/health/liveness` — is the JVM/app in a state where it should be
  restarted if broken (deadlock, unrecoverable state)?
- `/actuator/health/readiness` — is the app ready to accept traffic (finished
  starting, dependencies reachable)?

Kubernetes `livenessProbe` restarts a container that fails liveness;
`readinessProbe` removes a pod from a Service's endpoint list (no traffic
routed to it) until it passes again — this is why a pod can be `Running` but
not yet receiving traffic.

## 13. Kubernetes Networking

All inter-service calls use **Kubernetes Service DNS names**, never pod IPs
or `localhost`:
- `api-gateway → employee-service` / `project-service`: indirectly, via
  Eureka-registered addresses resolved by the load balancer (the pod IP
  Eureka stores is on the pod network, directly routable inside the cluster).
- `services → eureka-server`: `http://eureka-server:8761/eureka/`
  (ClusterIP Service, stable DNS name backed by CoreDNS).
- `services → otel-collector`: `http://otel-collector:4318/v1/traces` (OTLP/HTTP).
- `prometheus → actuator endpoints`: Prometheus doesn't use a Service name at
  all for scraping — it talks directly to pod IPs discovered via the
  Kubernetes API (`kubernetes_sd_configs: role: pod`), which is why the RBAC
  ClusterRole is required.
- `otel-collector → jaeger`: `jaeger:4317` ClusterIP Service.

## 14. Failure Scenarios

See `INTERVIEW-NOTES.md` → "Production Scenarios" for a full walkthrough of
Gateway 503s, a downstream service outage, Eureka unavailability, high
CPU/memory/latency, 5xx spikes, pod restarts, scrape failures, and missing
traces, including the diagnostic commands used for each.
