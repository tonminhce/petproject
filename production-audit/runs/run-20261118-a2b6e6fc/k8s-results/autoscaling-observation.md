# Autoscaling observation on a real kubernetes cluster

Run: `run-20261118-a2b6e6fc`
Recorded: 2026-11-21T06:10:00Z
Subject: the horizontal pod autoscaler of `shop-system/gateway-service`

## Verdict

**The autoscaler scaled under a real load and scaled back down.**

The gateway deployment ran with 2 replicas at rest, the autoscaler raised it to 10 replicas
while a k6 load of 600 requests per second hit the gateway, and it returned it to 2 replicas
after the load stopped. Every number below was read from the live cluster with `kubectl`.

## Cluster

| Item | Value |
| --- | --- |
| Tool | kind v0.32.0 |
| Cluster name | `shop-audit` |
| Context | `kind-shop-audit` |
| Kubernetes version | v1.36.1 |
| kubectl version | v1.36.2 |
| Node image | `kindest/node:v1.36.1` |
| Node | `shop-audit-control-plane`, control-plane, Ready, 10 cpu / 11975248Ki memory allocatable, containerd 2.3.1 |
| Metrics server | `registry.k8s.io/metrics-server/metrics-server:v0.9.1`, applied from the upstream release manifest `https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml`, with `--kubelet-insecure-tls` added to the deployment arguments because the kind kubelet serves a self signed certificate |

The metrics API answered with `kubectl top nodes` and `kubectl top pods`, so the autoscaler
read real kubelet resource metrics.

## Manifests applied

* `k8s/namespace.yaml`
* `k8s/gateway-service.yaml` (deployment, service and autoscaler)
* `k8s/workload-protection.yaml` (the `shop-system` service account and the `gateway-service-pdb` only)

The secrets were created from `k8s/secrets.template.yaml` with random local only values held
in `/tmp/shop-system-secrets.local.yaml`, outside the repository. The template still carries its
placeholders and no real value was committed.

A single instance of redis was deployed in the namespace as an auxiliary dependency, because the
gateway edge rate limiter calls redis and fails closed with `503` when redis is unreachable. That
manifest was applied from `/tmp` and is not part of the audited `k8s` directory.

## Image

| Item | Value |
| --- | --- |
| Reference | `shop/gateway-service:latest` |
| Digest | `sha256:abec09210b07c368d24eff189575c2bf8c513e90cfc53eeeaafca1de709a4005` |
| Dockerfile | `gateway-service/Dockerfile` (base image `eclipse-temurin:25-jre-alpine`) |
| Artifact | `gateway-service/target/gateway-service-1.0-SNAPSHOT.jar` |
| Maven | `./mvnw -B -ntp -T 1 -pl gateway-service -am package -DskipTests` |
| Loaded into the cluster | `kind load docker-image shop/gateway-service:latest --name shop-audit` |

The image was built before the cluster was created, and the compose stack was stopped before the
cluster was created, so no maven build and no cluster were running at the same time.

## Autoscaler manifest as applied

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: gateway-service-hpa
  namespace: shop-system
  creationTimestamp: 2026-11-21T02:48:52Z
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: gateway-service
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 75
```

The spec in the cluster is identical to `k8s/gateway-service.yaml`.

## Load

| Item | Value |
| --- | --- |
| Generator | k6 v2.4.0, `grafana/k6` image, running as a job inside the cluster |
| Target | `http://gateway-service.shop-system.svc.cluster.local:8080/actuator/prometheus` |
| Shape | constant arrival rate, 600 iterations per second for 6 minutes |
| Started | 2026-11-21T05:06:52Z |
| Finished | 2026-11-21T05:12:52Z |
| Requests served | 197861, 549.6 requests per second achieved |
| Failed requests | 0, error rate 0.00% |
| Latency | p50 2.29 ms, p95 916.34 ms, max 7.6 s |
| Dropped iterations | 18140, the gateway was the bottleneck of the run |

The actuator endpoint was chosen because it needs no token. The rate limit environment of the
repository load test profile (`docker-compose.loadtest.yml`) was applied to the deployment with
`kubectl set env`, because the committed limits of 50 requests per second per client would have
capped the load below the autoscaling threshold. The rolling restart was allowed to settle and
the baseline was measured afterwards.

## Observations

### Before the load, 2026-11-21T05:04:01Z

| Item | Value |
| --- | --- |
| Replicas | **2** (deployment `2/2`) |
| Autoscaler | `cpu: 3%/75%`, replicas 2 |
| Pod cpu | `gateway-service-bc885c79-nb2sg` 20m, `gateway-service-bc885c79-p2z9q` 17m |

The autoscaler had already moved the deployment from the 3 replicas declared in the manifest down
to its minimum of 2.

### Under the load, 2026-11-21T05:06:52Z to 2026-11-21T05:12:53Z

| Item | Value |
| --- | --- |
| Replicas | **10** (deployment `10/10`, the maximum of the autoscaler) |
| Autoscaler at the peak | `cpu: 206%/75%` at 2026-11-21T05:07:47Z |
| Autoscaler when the ten pods were all ready | `cpu: 68%/75%` at 2026-11-21T05:08:33Z |
| Highest pod cpu | `gateway-service-bc885c79-nb2sg` 1970m against the 2000m limit, `gateway-service-bc885c79-p2z9q` 1336m, `gateway-service-bc885c79-29hb8` 1073m, `gateway-service-bc885c79-j77rq` 1009m |

The first sample of the load, 26 seconds after the generator started, already showed 1970m of cpu
in one pod against a 500m request, which is 394% of the request of that pod.

The load stayed concentrated on the pods that already held the client connections, so the average
utilisation that the autoscaler consumes fell while the deployment was at ten replicas. The
autoscaler reacted to the average of the deployment, which is the correct behaviour for a resource
based autoscaler without a traffic distribution guarantee.

### After the load, from 2026-11-21T05:13:39Z

| Item | Value |
| --- | --- |
| Scale down started | 2026-11-21T05:13:39Z, deployment `6/6` |
| Path | 10, 6, 5, 4, 2 |
| Replicas at rest | **2** (deployment `2/2`) from 2026-11-21T05:18:14Z, last scale time 2026-11-21T05:17:55Z |
| Autoscaler | `cpu: 1%/75%`, replicas 2, confirmed again at 2026-11-21T06:04:06Z |
| Pod cpu | `gateway-service-bc885c79-chnl5` 9m, `gateway-service-bc885c79-j77rq` 8m |

The scale down ran through the default 300 second stabilisation window of the autoscaler and
stopped at the minimum of 2 replicas.

### Autoscaler events

```
Normal  SuccessfulRescale  New size: 4; reason: cpu resource utilization (percentage of request) above target
Normal  SuccessfulRescale  New size: 8; reason: cpu resource utilization (percentage of request) above target
Normal  SuccessfulRescale  New size: 10; reason:
Normal  SuccessfulRescale  New size: 6; reason: All metrics below target
Normal  SuccessfulRescale  New size: 5; reason: All metrics below target
Normal  SuccessfulRescale  New size: 4; reason: All metrics below target
Normal  SuccessfulRescale  New size: 2; reason: All metrics below target
```

## Raw evidence

| File | Content |
| --- | --- |
| `autoscaling.json` | the same numbers in machine readable form |
| `hpa-timeline.log` | the autoscaler and the pod cpu sampled every 15 seconds across the whole window |
| `baseline.txt` | the cluster state before the load |
| `under-load.txt` | the cluster state at the end of the load |
| `after-load.txt` | the cluster state after the scale down |
| `hpa-applied.yaml` | the autoscaler object read back from the cluster |
| `hpa-events.txt` | the autoscaler events read back from the cluster |
| `k6-load.log` | the full output of the load generator |

## Limits of this observation

* One kind node with ten cpu was used, so the ten replicas of the gateway share one node. On a
  multi node cluster the autoscaler would spread them over several nodes.
* The load hit a single endpoint of the gateway, so it proves the horizontal scaling of the
  gateway, not the behaviour of every route of the platform.
* The event driven part of the task was not exercised. The manifests declare no event driven
  autoscaler at all: the only autoscaler in the `k8s` directory is the horizontal pod autoscaler of the
  gateway in `k8s/gateway-service.yaml`, and there is no KEDA `ScaledObject` or equivalent. The evidence
  of this file therefore covers the horizontal pod autoscaler of the gateway only.
* The cluster was deleted with `kind delete cluster` at the end of the run.
