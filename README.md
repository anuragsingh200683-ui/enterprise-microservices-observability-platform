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
│   ├── k8deployment/       Its own Deployment + Service manifest
│   └── Jenkinsfile         Its own build-and-deploy pipeline
├── api-gateway/            Spring Cloud Gateway (8080)          (same layout)
├── employee-service/       Employee CRUD microservice (8081)    (same layout)
├── project-service/        Project CRUD microservice (8082)     (same layout)
├── platform/               Shared/cluster-wide manifests: namespace, the
│                           microservices-config ConfigMap, and the
│                           Prometheus/Grafana/OTel Collector/Jaeger stack
├── jenkins/                Custom Jenkins image (Dockerfile, plugins, CasC)
├── ARCHITECTURE.md         Deep-dive architecture explanation + diagram
├── INTERVIEW-NOTES.md      Senior-level interview Q&A for this project
└── README.md               This file
```

Each service directory follows:
```
src/main/java/.../{controller,service,repository,dto,exception,model,config}
src/main/resources/application.yml
k8deployment/deployment.yaml   Deployment + Service for just this service
Jenkinsfile                    Build image -> push to registry -> deploy
Dockerfile
pom.xml
```

Manifests live next to the service they deploy (not in one shared
`kubernetes/` folder) so each service's Jenkins pipeline only ever touches
its own file — building/redeploying `employee-service` can never
accidentally change `project-service`'s Deployment.

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

Images are distributed through a **local Docker registry** running as a
container on `localhost:5000`, instead of Kubernetes reading straight from
Docker Desktop's shared image store. Start it once:

```bash
docker run -d --restart=always -p 5000:5000 --name registry registry:2
```

Docker Desktop's Kubernetes node can pull from `localhost:5000` with no
extra insecure-registry configuration — this is supported out of the box.

Build and push each image:

```bash
docker build -t localhost:5000/eureka-server:latest    ./eureka-server
docker build -t localhost:5000/employee-service:latest ./employee-service
docker build -t localhost:5000/project-service:latest  ./project-service
docker build -t localhost:5000/api-gateway:latest       ./api-gateway

docker push localhost:5000/eureka-server:latest
docker push localhost:5000/employee-service:latest
docker push localhost:5000/project-service:latest
docker push localhost:5000/api-gateway:latest
```

Every Deployment uses `imagePullPolicy: Always` against these `:latest`
tags, so **pushing a new image alone does not redeploy it** — Kubernetes
only pulls fresh on pod creation. After pushing, force a rollout so the
running pods actually pick up the new image:

```bash
kubectl rollout restart deployment/<service-name> -n microservices
```

(Each service's Jenkins pipeline does exactly this build → push → rollout
restart sequence automatically — see §18.)

## 9. Kubernetes Deployment Commands

Apply the shared/platform manifests first, then each service's own
manifest (dependency-sensible: discovery first, then the apps that depend
on it, then the gateway, then observability):

```bash
kubectl apply -f platform/namespace.yaml
kubectl apply -f platform/microservices-configmap.yaml

kubectl apply -f eureka-server/k8deployment/deployment.yaml

kubectl apply -f employee-service/k8deployment/deployment.yaml
kubectl apply -f project-service/k8deployment/deployment.yaml
kubectl apply -f api-gateway/k8deployment/deployment.yaml

kubectl apply -f platform/prometheus-configmap.yaml -f platform/prometheus-deployment.yaml -f platform/prometheus-service.yaml
kubectl apply -f platform/grafana-configmap.yaml -f platform/grafana-dashboard-configmap.yaml -f platform/grafana-deployment.yaml -f platform/grafana-service.yaml
kubectl apply -f platform/otel-collector-configmap.yaml -f platform/otel-collector-deployment.yaml -f platform/otel-collector-service.yaml
kubectl apply -f platform/jaeger-deployment.yaml -f platform/jaeger-service.yaml
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
| `ImagePullBackOff`                | Confirm the `registry` container is running and `docker push`ed (`curl http://localhost:5000/v2/_catalog`) — `imagePullPolicy: Always` means a pod always re-pulls, so a dead/missing registry breaks every future rollout |
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
built images remain in the local registry and on disk; remove those too for
a full teardown:

```bash
docker rmi localhost:5000/eureka-server:latest localhost:5000/employee-service:latest localhost:5000/project-service:latest localhost:5000/api-gateway:latest
docker rm -f registry
```

## 18. Jenkins CI/CD Pipelines

Each service owns its **own** `Jenkinsfile` (`eureka-server/Jenkinsfile`,
`employee-service/Jenkinsfile`, `project-service/Jenkinsfile`,
`api-gateway/Jenkinsfile`) and its own Jenkins pipeline job, so any one
service can be rebuilt and redeployed independently without touching the
other three.

**Build and run the Jenkins container** (runs alongside the app stack, with
the Docker socket and your kubeconfig mounted so its pipelines can build
images visible to the same Docker Desktop daemon and `kubectl apply`
directly to the cluster):

```bash
docker build -t local/jenkins-microservices:1.0 ./jenkins

docker volume create jenkins_home

docker run -d --name jenkins \
  --user root \
  -p 8090:8080 -p 50000:50000 \
  -v jenkins_home:/var/jenkins_home \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v <path-to-your-kubeconfig>:/var/jenkins_home/.kube/config:ro \
  -e KUBECONFIG=/var/jenkins_home/.kube/config \
  local/jenkins-microservices:1.0
```

> On Windows with Git Bash, prefix the `docker run` with `MSYS_NO_PATHCONV=1`
> and pass the kubeconfig source as a plain Windows path (e.g.
> `C:/Users/<you>/.kube/config`) — otherwise Git Bash silently rewrites the
> Unix-style container paths (including inside `-e KUBECONFIG=...`) into
> nonsense Windows paths.

**Open Jenkins**: http://localhost:8090 — login `admin` / `admin123` (set in
`jenkins/casc.yaml`; this container skips the setup wizard entirely via
Jenkins Configuration-as-Code — nothing to click through).

**Create one pipeline job per service** (one-time each; a classic Pipeline
job, "Pipeline script from SCM" → Git → this repo's URL → branch `main` →
script path `<service>/Jenkinsfile`, e.g. `employee-service/Jenkinsfile`),
then click **Build Now** on whichever one you want to run.

Each pipeline declares a `tools {}` block — `maven 'Maven_3'`, `jdk 'JDK21'`,
`dockerTool 'Docker'` — resolved from Tool installations baked into the
Jenkins image (`/opt/maven`, `/opt/jdk21`, `/usr/local`) and configured in
`jenkins/casc.yaml`; no manual "Global Tool Configuration" clicking needed.

Each service's pipeline (also requires the `registry:2` container from §8
to be running, since `docker push` needs somewhere to push to):
1. **Checkout** — explicit `git branch: 'main', url: '...'` step.
2. **Build JAR** — `mvn clean package -DskipTests`, using the `Maven_3` /
   `JDK21` tools (a real, fail-fast compile check, separate from the
   Dockerfile's own internal Maven build stage — see note below).
3. **Build Docker Image** — `docker build` tagged
   `localhost:5000/<service>:latest`, then `docker push` to the local
   registry, both in one stage.
4. **Deploy to Kubernetes** — `kubectl apply`s the namespace, the shared
   ConfigMap, and only that service's own `k8deployment/deployment.yaml`,
   then `kubectl rollout restart`s the Deployment (forcing a fresh
   `imagePullPolicy: Always` pull of the image just pushed).
5. **Verify Deployment** — waits for `kubectl rollout status`, prints
   `kubectl get pods -o wide` and `kubectl get svc`, then self-checks by
   `kubectl exec`-ing into the deployment's own pod and hitting its actuator
   health endpoint directly — doesn't depend on any other service, so one
   pipeline's verification never fails because of another service's state.

> **Why "Build JAR" and the Dockerfile both run Maven**: the Dockerfile is a
> self-contained multi-stage build (`mvn package` happens again inside it),
> kept that way so `docker build ./<service>` still works standalone with no
> Jenkins involved. The pipeline's own `Build JAR` stage is intentionally
> redundant — it fails fast on a compile error using the Jenkins agent's own
> Maven/JDK21 tools, before spending time on a Docker build at all.

Trigger is manual ("Build Now") by design, to avoid exposing a local Jenkins
to the internet for a GitHub webhook.

### Graphical stage-by-stage view (Blue Ocean)

The Jenkins image bundles the **Blue Ocean** plugin, which renders each
pipeline run as a horizontal graph of stages (Checkout → Build JAR → Build
Docker Image → Deploy to Kubernetes → Verify Deployment), colored by status
and clickable per stage for that stage's own log — the graphical view most
people mean by "Jenkins pipeline stages."

Open it at **http://localhost:8090/blue/** — pick a pipeline (e.g.
`employee-service-deploy`) to see its run history and stage graph, or jump
straight to a specific job's view at
`http://localhost:8090/blue/organizations/jenkins/<job-name>/activity`.

The classic Jenkins UI also shows a simpler colored "Stage View" table
directly on each job's own page
(`http://localhost:8090/job/<job-name>/`) if you don't want to switch UIs.

> A rolling redeploy of `eureka-server` briefly resets its registry to
> empty; already-running services re-register within ~10s, but the
> **Gateway's own cached copy** of the registry only refreshes on its
> `registry-fetch-interval` (default 30s) — so `/api/employees` and
> `/api/projects` can return `503` for up to ~30s after an
> `eureka-server-deploy` build, even though every pod is healthy. This is
> expected convergence lag, not a failure — see `INTERVIEW-NOTES.md`'s
> "What happens if Eureka is unavailable?" for the underlying mechanism.
