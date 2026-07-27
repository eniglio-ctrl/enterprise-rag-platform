# Kubernetes manifests

Base manifests (Kustomize) to run the full platform (Postgres, Ollama, and the four
application services) in a local `kind` cluster. See [ADR 0014](../docs/adr/0014-kubernetes-manifests-kind.md)
for the design decisions behind these files.

## Prerequisites

- `kubectl` (v1.27+; bundled Kustomize is enough, no separate `kustomize` binary needed)
- `kind` (`brew install kind`)
- Docker, already running the same images `docker-compose.yml` builds
- **Don't run the `docker-compose` stack and this `kind` cluster at the same time** on
  a memory-constrained machine. `ollama` running `llama3.1` on CPU needs real headroom
  in Docker Desktop's own VM (not just the pod's `resources.limits`) — a per-container
  memory limit can't save you if the *host* runs out first. Confirmed the hard way: the
  `ollama-0` pod got `OOMKilled` on its first real inference call while an old
  `docker-compose up` stack was still running in the background on a 7.75GiB Docker VM.
  `docker compose down` before `kind create cluster` fixed it. If you see this, also
  check `docker info --format '{{.MemTotal}}'` and consider raising Docker Desktop's
  memory allocation (Settings → Resources).

## 1. Build the images with the tags the manifests expect

```bash
docker build -t rag-platform/ingestion-service:latest -f ingestion-service/Dockerfile .
docker build -t rag-platform/rag-service:latest -f rag-service/Dockerfile .
docker build -t rag-platform/chat-service:latest -f chat-service/Dockerfile .
docker build -t rag-platform/web-ui:latest ./web-ui
```

## 2. Create the cluster and load the images

`kind` runs Kubernetes in Docker but has its own internal image store — a locally
built image isn't visible to the cluster until it's explicitly loaded in.

```bash
kind create cluster --name rag-platform
kind load docker-image rag-platform/ingestion-service:latest \
  rag-platform/rag-service:latest rag-platform/chat-service:latest \
  rag-platform/web-ui:latest --name rag-platform
```

## 3. Provide database credentials

```bash
cp kubernetes/base/.env.secret.example kubernetes/base/.env.secret
# edit kubernetes/base/.env.secret with real values — this file is gitignored
```

## 4. Apply and verify

```bash
kubectl apply -k kubernetes/base
kubectl get pods -n rag-platform -w
```

`postgres-0` and `ollama-0` come up first; the three Java services each run an
initContainer that polls the dependencies they need (Postgres/Ollama, and — for
rag-service/chat-service — the upstream service's `/actuator/health`) before starting,
mirroring docker-compose's `depends_on: condition: service_healthy`. The first boot is
slow: each Java service pulls its Ollama model(s) itself (`pull-model-strategy:
when_missing`), so `nomic-embed-text` and `llama3.1` are downloaded fresh into the
`ollama-0` pod's volume the first time any service needs them.

## 5. Reach the platform

Primary path — works with zero extra setup, matches the plan's own "pronto quando"
criterion:

```bash
kubectl port-forward -n rag-platform svc/web-ui 3000:80
```

Then open http://localhost:3000.

Secondary path — via the bundled `Ingress` (`kubernetes/base/ingress.yaml`), which
requires an ingress controller (not installed by `kind` by default):

```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
kubectl wait --namespace ingress-nginx --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller --timeout=120s
echo "127.0.0.1 rag-platform.local" | sudo tee -a /etc/hosts
kubectl port-forward -n ingress-nginx svc/ingress-nginx-controller 8080:80
```

Then open http://rag-platform.local:8080.

## Cleanup

```bash
kind delete cluster --name rag-platform
```

## Known limitations

- No TLS — local-only, same as `docker-compose.yml`. Real TLS is deferred to the
  public deploy phase (`cert-manager` or the hosting provider's own termination).
- No `HorizontalPodAutoscaler` or `PodDisruptionBudget` — single replica everywhere,
  portfolio/demo scale, not a production sizing exercise.
- Manifests predate `auth-service` (deliberately built out of the plan's original
  order, per explicit direction) — they'll need a second pass once JWT/OAuth2 exists
  ([ADR 0014](../docs/adr/0014-kubernetes-manifests-kind.md)).
