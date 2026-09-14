# T-061 — PodMonitoring and metric-arrival verification

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M6 — Observability and SRE · **Estimate** 25 min (one CRD per namespace plus a manifest
audit; the wait for the first data point is idle time, not work)

**Preconditions** — T-060 (every C4 §4.2 meter now exists behind one owning class and is served at
`/actuator/prometheus`) and M5, which put the workload manifests under `deploy/k8s/` and the Autopilot
cluster `dlock-gke` in project `dlock-lab`. You inherit pods that expose metrics that nothing collects.

**Goal** — Make Google Managed Service for Prometheus actually scrape all five services, and leave a
written verification procedure that distinguishes "the app is not emitting" from "the wiring is wrong".

## 1. Why this task exists

Managed Prometheus collects nothing until a `PodMonitoring` selects the pods, and the single most common
failure is a `spec.endpoints[].port` naming a port the container does not declare — which produces **no
error at all**, just an empty query result forever (C4 §4.9). Every SLO, dashboard and alert built in
T-064 onwards is worthless if this task is only half done, and a `< threshold` alert on an empty series
looks exactly like health. So the deliverable is not the CRD; it is the CRD plus a proof of arrival.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Path `/actuator/prometheus`, container port `8080`, **port name `http-metrics`**, interval `30s`, one `PodMonitoring` per namespace | [C4 §4.9](../docs/contracts/C4-observability.md#ct4-scrape) |
| Actuator exposure must include `prometheus` and `health` | [C4 §4.9](../docs/contracts/C4-observability.md#ct4-scrape) |
| Exported metric names to query (`lock_acquire_seconds_count`, …) | [C4 §4.2](../docs/contracts/C4-observability.md#ct4-metrics) |
| Health/readiness endpoints and the fail-closed rule | [C4 §4.10](../docs/contracts/C4-observability.md#ct4-health) |
| Cluster `dlock-gke`, project `dlock-lab`, region `europe-central2`; K8s object name = module name; manifests live in `deploy/k8s/`, one dir per workload/KSA | [C5 §5.5](../docs/contracts/C5-config-build-and-naming.md#ct5-layout), [§5.6](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |

**Precedence:** if this spec and a contract disagree, the **contract wins** — stop, quote both, report.
If the namespace name is not pinned in C5, use the value already in the M5 manifests; do not invent one.

## 3. Deliverables

| Path | What |
|---|---|
| `deploy/k8s/monitoring/podmonitoring.yaml` | One `monitoring.googleapis.com/v1` `PodMonitoring` per namespace, label-selecting the five project services, one endpoint entry |
| `deploy/k8s/<workload>/deployment.yaml` × 5 (modify) | Ensure `ports: [{containerPort: 8080, name: http-metrics}]` and the common selector label on the **pod template**, not only the Deployment |
| `deploy/k8s/<workload>/configmap` or `application-gke.yaml` (modify) | `management.endpoints.web.exposure.include` lists `prometheus,health`; `management.endpoint.health.probes.enabled` on; liveness/readiness groups per C4 §4.10 |
| `deploy/k8s/monitoring/README.md` | The four-step arrival check of §4, the named-port trap, and the one-line triage table |
| `docs/06-observability-and-slo.md` (modify) | A "how scraping works here" subsection: CRD → port name → exported name → `prometheus.googleapis.com/<name>/counter` |

## 4. Specification

**The CRD.** `PodMonitoring` is namespace-scoped and only ever selects pods in its own namespace, so one
object covers all five workloads via a shared label (e.g. the label M5 already stamps on every project pod —
reuse it, do not add a second convention). `spec.endpoints` has exactly one entry: `port: http-metrics`
(**the name string, never `8080`**), `path: /actuator/prometheus`, `interval: 30s`. Set no
`metricRelabeling` that drops or renames a project metric; adding a static label here is a cardinality
decision and belongs in C4, not in a manifest.

**The label selector must match the pod template's labels.** A selector matching the Deployment's own
labels but not `spec.template.metadata.labels` selects zero pods, with the same silent-empty symptom.

**Named ports on all five containers.** Audit every Deployment: an unnamed `containerPort`, or one named
`http`, is the bug. The name is what the CRD resolves. Record in the README that `kubectl get pod -o
jsonpath` over `spec.containers[*].ports[*].name` is the fastest way to see all five at once.

**Probes.** Wire `livenessProbe` → `/actuator/health/liveness` and `readinessProbe` →
`/actuator/health/readiness` while you are in these files, because C4 §4.10 makes readiness fail when the
lock backend is unreachable and that is the behaviour the eviction budget of ADR-005 assumes. Liveness
must not point at the aggregate `/actuator/health`, or a backend outage restart-loops the fleet.

**The triage table** in the README, three rows, ordered as C4 §4.9 orders them: `curl` inside the pod
succeeds but no target listed → wiring (port name, selector, namespace); target listed but no data point
→ metric name or Actuator exposure; neither → the app is not serving Actuator at all.

## 5. Acceptance criteria

1. `grep -c 'kind: PodMonitoring' deploy/k8s/monitoring/podmonitoring.yaml` equals the number of project namespaces, and `grep -n 'port:' ` on that file shows `http-metrics` and no numeric value.
2. `grep -rn 'containerPort' deploy/k8s` shows `8080` for all five workloads, each on a port entry that also carries `name: http-metrics`.
3. The CRD's `selector.matchLabels` key/value pair appears in every workload's `spec.template.metadata.labels` (checkable with one grep per workload).
4. `management.endpoints.web.exposure.include` includes both `prometheus` and `health` for all five services.
5. Liveness and readiness paths are the two group endpoints of C4 §4.10; `/actuator/health` is used by neither probe.
6. `deploy/k8s/monitoring/README.md` contains the four-step check and the three-row triage table.
7. A data point for `lock_acquire_seconds_count` is visible in Metrics Explorer within two scrape intervals of a grant (evidence recorded in the ledger note).

## 6. Verification

`kubectl apply --dry-run=server -f deploy/k8s/monitoring/podmonitoring.yaml` — accepted by the API server
(this is also what catches a wrong `apiVersion`). Then, in order:
`kubectl get pods -l <lab-label> -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.spec.containers[*].ports[*].name}{"\n"}{end}'`
— expect `http-metrics` on all five;
`kubectl exec deploy/lock-server -- curl -s localhost:8080/actuator/prometheus | grep -c lock_acquire_seconds_count`
— expect ≥ 1;
`kubectl describe podmonitoring <name>` — expect the endpoint status to list up targets, count = pod count;
`gcloud monitoring time-series list --project dlock-lab --filter='metric.type="prometheus.googleapis.com/lock_acquire_seconds_count/counter"'`
(or Metrics Explorer) — expect a point inside 60 s of a grant.

## 7. Out of scope

Dashboards, SLO definitions and alert policies (T-064+). Log pipeline (T-062, T-063). Trace export.
Terraform-managed monitoring resources — the `PodMonitoring` is a K8s object applied with the workloads,
**not** a `google_monitoring_*` resource; T-063 owns the Terraform side of observability.

## 8. Hazards

- **The named-port trap** (C4 §4.9): a name that does not exist matches nothing, silently. Check the port
  **name** before suspecting the app, the CRD schema, or IAM.
- A `PodMonitoring` in the wrong namespace matches nothing and looks identical to the above.
- Autopilot may reject a Deployment edit that omits resource requests; re-apply with the M5 values intact.
- Do not "fix" an empty query by renaming a metric; T-060 froze those names against C4.

## 9. On completion

Mark the T-061 row done in `tasks/README.md`. Record which label selector was reused, the namespace(s)
covered, and the observed lag between the grant and the first data point.
