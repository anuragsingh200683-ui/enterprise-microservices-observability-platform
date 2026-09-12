# Enterprise Microservices Observability Platform

A complete, locally runnable Spring Boot microservices platform with service
discovery, an API gateway, and full observability (metrics + traces),
deployed on Docker Desktop's built-in Kubernetes cluster.

## 1. Architecture

```
Client -> API Gateway (Spring Cloud Gateway, :8080)
              |-> lb://EMPLOYEE-SERVICE -> employee-service (:8081)
              |-> lb://PROJECT-SERVICE  -> project-service  (:8082)

All 3 services register with:
  eureka-server (:8761)  — Service Discovery

All 4 Spring Boot apps emit:
  Micrometer     -> /actuator/prometheus -> Prometheus (:9090) -> Grafana (:3000)
  OpenTelemetry  -> OTLP/HTTP -> OTel Collector (:4318) -> Jaeger (:16686)
```

See `ARCHITECTURE.md` for the full breakdown and ASCII diagram, and
`INTERVIEW-NOTES.md` for a senior-level Q&A walkthrough of every component.

## 2. Technology Stack

| Concern            | Technology                                         |
|---------------------|-----------------------------------------------------|
| Language / runtime  | Java 21 (Eclipse Temurin)                          |
| Framework           | Spring Boot 3.3.5, Spring Cloud 2023.0.3 (Leyton)  |
| Build               | Maven 3.9 (multi-stage Docker build, no local install needed) |
| Service discovery   | Spring Cloud Netflix Eureka                        |
| Gateway             | Spring Cloud Gateway (reactive)                    |
| Metrics             | Micrometer + micrometer-registry-prometheus        |
| Tracing             | Micrometer Tracing (OpenTelemetry bridge) + OTLP/HTTP |
| Metrics backend     | Prometheus                                         |
| Dashboards          | Grafana (auto-provisioned datasource + dashboard)  |
| Trace collection    | OpenTelemetry Collector (contrib)                  |
| Trace backend/UI    | Jaeger (all-in-one)                                |
| Container runtime   | Docker (Docker Desktop)                            |
| Orchestration       | Kubernetes (Docker Desktop's built-in cluster)      |

## 3. Project Structure

```
.
├── eureka-server/          Eureka service discovery server (8761)
├── api-gateway/            Spring Cloud Gateway (8080)
├── employee-service/       Employee CRUD microservice (8081)
├── project-service/        Project CRUD microservice (8082)
├── kubernetes/             All Kubernetes manifests (flat directory)
├── ARCHITECTURE.md         Deep-dive architecture explanation + diagram
├── INTERVIEW-NOTES.md      Senior-level interview Q&A for this project
└── README.md               This file
```

Each service directory follows:
```
src/main/java/.../{controller,service,repository,dto,exception,model,config}
src/main/resources/application.yml
Dockerfile
pom.xml
```

## 4. Prerequisites

- Windows 10/11 with **Docker Desktop** installed
- Docker Desktop's **Kubernetes** feature enabled (Settings → Kubernetes →
  Enable Kubernetes)
- `kubectl` (ships with Docker Desktop's Kubernetes integration)
- Internet access for the first build (Maven Central + Docker Hub image pulls)
- No local Java/Maven installation is required — all builds run inside
  Docker via multi-stage Dockerfiles.

## 5. Docker Desktop Setup

1. Install Docker Desktop from docker.com and start it.
2. Confirm it's running: `docker version`.
3. Allocate at least **4 CPUs / 6 GB RAM** to the Docker Desktop VM
   (Settings → Resources) — this stack runs 8 pods (4 apps + Prometheus +
   Grafana + OTel Collector + Jaeger).

## 6. Enable Kubernetes

Docker Desktop → Settings → Kubernetes → check **Enable Kubernetes** → Apply
& Restart. Verify:

```bash
kubectl get nodes
# NAME             STATUS   ROLES           AGE   VERSION
# docker-desktop   Ready    control-plane   ...   v1.34.1
```

## 7. Build Commands

Each service builds inside Docker (multi-stage: `maven:3.9.9-eclipse-temurin-21`
→ `eclipse-temurin:21-jre-alpine`), so there is nothing to build locally —
skip straight to the Docker build commands in section 8.

## 8. Docker Commands

```bash
docker build -t local/eureka-server:1.0    ./eureka-server
docker build -t local/employee-service:1.0 ./employee-service
docker build -t local/project-service:1.0  ./project-service
docker build -t local/api-gateway:1.0      ./api-gateway

docker images | grep local/
```

`imagePullPolicy: IfNotPresent` is set on every Deployment, so Kubernetes
uses these local images directly — no registry/Docker Hub push required.

## 9. Kubernetes Deployment Commands

Apply in this order (dependency-sensible: discovery first, then the apps
that depend on it, then the gateway, then observability):

```bash
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/microservices-configmap.yaml

kubectl apply -f kubernetes/eureka-deployment.yaml -f kubernetes/eureka-service.yaml

kubectl apply -f kubernetes/employee-deployment.yaml -f kubernetes/employee-service.yaml
kubectl apply -f kubernetes/project-deployment.yaml  -f kubernetes/project-service.yaml
kubectl apply -f kubernetes/gateway-deployment.yaml  -f kubernetes/gateway-service.yaml

kubectl apply -f kubernetes/prometheus-configmap.yaml -f kubernetes/prometheus-deployment.yaml -f kubernetes/prometheus-service.yaml
kubectl apply -f kubernetes/grafana-configmap.yaml -f kubernetes/grafana-dashboard-configmap.yaml -f kubernetes/grafana-deployment.yaml -f kubernetes/grafana-service.yaml
kubectl apply -f kubernetes/otel-collector-configmap.yaml -f kubernetes/otel-collector-deployment.yaml -f kubernetes/otel-collector-service.yaml
kubectl apply -f kubernetes/jaeger-deployment.yaml -f kubernetes/jaeger-service.yaml
```

## 10. Verification Commands

```bash
kubectl get pods -n microservices
kubectl get svc -n microservices
kubectl get deployments -n microservices
kubectl get endpoints -n microservices
```

All 8 pods should reach `1/1 Running`:
`eureka-server`, `employee-service`, `project-service`, `api-gateway`,
`prometheus`, `grafana`, `otel-collector`, `jaeger`.

## 11. API Testing

Through the Gateway (`http://localhost:30080`):

```bash
# Employees
curl http://localhost:30080/api/employees
curl http://localhost:30080/api/employees/1

curl -X POST http://localhost:30080/api/employees \
  -H "Content-Type: application/json" \
  -d '{"name":"Dave Lee","email":"dave.lee@example.com","department":"Engineering","salary":102000}'

curl -X PUT http://localhost:30080/api/employees/1 \
  -H "Content-Type: application/json" \
  -d '{"name":"Alice Johnson","email":"alice.j@example.com","department":"Engineering","salary":99000}'

curl -X DELETE http://localhost:30080/api/employees/2

# Projects
curl http://localhost:30080/api/projects
curl http://localhost:30080/api/projects/1

curl -X POST http://localhost:30080/api/projects \
  -H "Content-Type: application/json" \
  -d '{"name":"Trace Correlation Demo","description":"Interview demo project","owner":"Dave Lee"}'

# Validation error example (400)
curl -X POST http://localhost:30080/api/employees \
  -H "Content-Type: application/json" \
  -d '{"name":"","email":"not-an-email","department":"","salary":-5}'

# Not-found example (404)
curl http://localhost:30080/api/employees/9999
```

## 12. Eureka Verification

```bash
kubectl exec -n microservices deploy/api-gateway -- \
  wget -qO- http://eureka-server:8761/eureka/apps
```

Or check the Gateway's own discovery-client health view:
```bash
curl -s http://localhost:30080/actuator/health | python3 -m json.tool
```
Expect `EMPLOYEE-SERVICE`, `PROJECT-SERVICE`, and `API-GATEWAY` all `UP`.

## 13. Prometheus Verification

Open **http://localhost:30090/targets** — all 4 app targets
(`eureka-server`, `employee-service`, `project-service`, `api-gateway`)
should show `State: UP`. Prometheus discovers them via the Kubernetes API
(`role: pod` service discovery in the `microservices` namespace), filtered
by the `prometheus.io/scrape=true` pod annotation — no static target list.

## 14. Grafana Verification

Open **http://localhost:30030**.

- **Login**: `admin` / `admin` (local development default — change this for
  anything beyond a laptop demo; see `grafana-deployment.yaml` env vars).
- The **Prometheus** datasource is already configured (Settings → Data
  sources) — nothing to add manually.
- Open the **"Microservices Observability Overview"** dashboard (already
  provisioned) — it shows service availability, HTTP request rate/count,
  4xx/5xx error rates, p95 latency, JVM memory/CPU/threads, and GC activity.

## 15. Jaeger Verification

Open **http://localhost:30686**. In the service dropdown you should see
`api-gateway`, `employee-service`, `project-service`, and
`jaeger-all-in-one`. Search traces for `api-gateway` and you'll see spans
flowing `api-gateway -> employee-service` and `api-gateway -> project-service`
for every request made in section 11 — the trace ID in each span also
appears in that request's application logs (see `kubectl logs`), so logs and
traces can be correlated by ID.

## 16. Troubleshooting

| Symptom                          | Where to look                                                                 |
|-----------------------------------|--------------------------------------------------------------------------------|
| Pod stuck `Pending`               | `kubectl describe pod -n microservices <pod>` → Events (usually resources)     |
| `CrashLoopBackOff`                | `kubectl logs -n microservices <pod> --previous`                              |
| `ImagePullBackOff`                | Confirm `docker images` has the tag and `imagePullPolicy: IfNotPresent` is set |
| Readiness never turns `1/1`       | `kubectl exec -n microservices <pod> -- wget -qO- localhost:<port>/actuator/health/readiness` |
| Gateway returns 503               | Check target service is `Running`+`Ready` and registered in Eureka (`/eureka/apps`) |
| Prometheus target `DOWN`          | `kubectl get pods -n microservices -o wide` (confirm pod IP), check `prometheus.io/*` annotations on the Deployment |
| Grafana panel empty               | Confirm Prometheus target is `UP` and has scraped at least once (wait 15s)     |
| No traces in Jaeger                | `kubectl logs -n microservices deploy/<app>` grep `otlp`/`exporter`; confirm `management.otlp.tracing.endpoint` matches the collector's actual receiver (HTTP :4318 path `/v1/traces` in this project — see note below) |
| `kubectl get events -n microservices --sort-by=.lastTimestamp` | General cluster-level troubleshooting for any of the above |

**Known gotcha fixed in this project**: Spring Boot 3.3.x's built-in OTLP
tracing auto-configuration only reliably supports the **HTTP/protobuf**
transport (the `management.otlp.tracing.transport=grpc` property is only
honored starting Spring Boot 3.4). All three apps are therefore configured
to export traces over **OTLP/HTTP** to `http://otel-collector:4318/v1/traces`,
even though the OTel Collector also has a gRPC receiver open on 4317 (kept
for compatibility with other tools). If you upgrade to Spring Boot 3.4+,
switching back to gRPC on 4317 is a one-line property change.

## 17. Cleanup Commands

```bash
kubectl delete namespace microservices
```

This removes every Deployment/Service/ConfigMap/RBAC object created for this
project in one shot (the namespace itself, plus everything inside it). The
`local/*:1.0` Docker images remain on disk; remove them too if you want a
full teardown:

```bash
docker rmi local/eureka-server:1.0 local/employee-service:1.0 local/project-service:1.0 local/api-gateway:1.0
```
