# T-056 — Kubernetes: etcd StatefulSet on Autopilot

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M5 · **Estimate** 30 min to write and bring a 3-member cluster to a healthy quorum. The
leader-change measurement of §4 is a further 20–30 min of waiting; run it as a second sitting rather
than truncating the observation window.

**Preconditions** — T-051 (Cloud NAT exists, so a pod can pull a non-Artifact-Registry image), T-053
(cluster `dlock-gke`), T-055 (`deploy/k8s/` exists with namespace `dlock`, ConfigMap, `apply.sh`, and the
five Spring workloads applied). No stateful workload and no `premium-rwo` PVC exists in the cluster yet.

**Goal** — Run a 3-member etcd 3.6 cluster on SSD PVCs spread across zones, with a headless Service, a
PodDisruptionBudget, eviction protection, and a recorded baseline for leader changes.

## 1. Why this task exists

M3's lock backend needs a real Raft group, not a single-node etcd, because the whole point of capturing
`ModRevision` at grant time is that it survives leader change ([ADR-001](../docs/adr/ADR-001-etcd-as-the-consensus-store.md),
[ADR-002](../docs/adr/ADR-002-fencing-token-source.md)). Autopilot bin-packs and evicts, so a naive
StatefulSet loses quorum on a routine node event. ADR-005 does not claim the mitigations eliminate
elections — it says the residual rate is a **budgeted line item that gets measured**
([ADR-005 §A5.3](../docs/adr/ADR-005-gke-autopilot-eviction-as-budgeted-unavailability.md#adr5-reframe)),
and this task produces the first number.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| StatefulSet `dlock-etcd` ×3, `serviceName: dlock-etcd-headless`, PVC 10 GB `premium-rwo`, requests 500m/1Gi, anti-affinity + zone spread, `safe-to-evict: "false"`, probes on `/health` | [05 §5.7](../docs/05-infrastructure.md#gcp-k8s) |
| `dlock-etcd-pdb` with `maxUnavailable: 1` | [05 §5.7](../docs/05-infrastructure.md#gcp-k8s) |
| `clusterIP: None` on the headless Service — Raft peers need identities, not a load balancer | [05 §5.7](../docs/05-infrastructure.md#gcp-k8s) |
| Only Google StorageClasses; PVCs are zonal, so a pod is pinned to its disk's zone | [05 §5.8](../docs/05-infrastructure.md#gcp-autopilot) |
| Names `dlock-etcd`, `dlock-etcd-headless`; labels `app.kubernetes.io/*` | [C5 §5.6](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| etcd 3.6 | [C5 §5.3](../docs/contracts/C5-config-build-and-naming.md#ct5-catalog) |
| Elections are budgeted and measured, not assumed away | [ADR-005](../docs/adr/ADR-005-gke-autopilot-eviction-as-budgeted-unavailability.md) |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report**
([04 §4.4](../docs/04-contracts.md#c-precedence)).

## 3. Deliverables

| Path | What |
|---|---|
| `deploy/k8s/etcd/statefulset.yaml` | `dlock-etcd`, 3 replicas, `volumeClaimTemplates` |
| `deploy/k8s/etcd/service-headless.yaml` | `dlock-etcd-headless`, `clusterIP: None` |
| `deploy/k8s/etcd/pdb.yaml` | `dlock-etcd-pdb`, `maxUnavailable: 1` |
| `deploy/k8s/apply.sh` *(modify)* | apply etcd before the workloads, wait for 3/3 and a healthy endpoint list |
| `deploy/k8s/etcd/README.md` | member DNS names, the client URL string for `LOCK_BACKEND=etcd`, the leader-change measurement procedure and the recorded baseline |

## 4. Specification

**StatefulSet.** `podManagementPolicy: Parallel`; `replicas: 3`; `updateStrategy: RollingUpdate`. Each
member gets its name from the pod name via a `fieldRef`, advertises peer and client URLs built from
`<pod>.dlock-etcd-headless.dlock.svc.cluster.local`, and receives an `initial-cluster` string naming all
three members with `initial-cluster-state: new`. Data directory is a **subdirectory** of the mounted
volume, not the mount point. Two ports: client `2379` named `etcd-client`, peer `2380` named `etcd-peer`.
Readiness and liveness both `GET /health` on 2379, with liveness given a period and failure threshold
generous enough that a leader election never restarts a healthy member. Requests `cpu: 500m`,
`memory: 1Gi` (inside the 1:1–1:6.5 ratio), non-root with a `fsGroup` that can write the PVC, no limits.
`terminationGracePeriodSeconds: 30`. Annotation `cluster-autoscaler.kubernetes.io/safe-to-evict: "false"`.

**Placement.** `podAntiAffinity` `requiredDuringSchedulingIgnoredDuringExecution` on
`app.kubernetes.io/name=dlock-etcd` with `topologyKey: kubernetes.io/hostname`, **plus**
`topologySpreadConstraints` on `topology.kubernetes.io/zone`, `maxSkew: 1`. Use
`whenUnsatisfiable: DoNotSchedule` for the zone constraint and say why in a comment: two members in one
zone means a zonal incident can take the quorum, so a `Pending` pod is the correct louder failure.

**Volumes.** `volumeClaimTemplates` with `storageClassName: premium-rwo`, 10 GB, `ReadWriteOnce`.
`standard-rwo` is forbidden here — its fsync latency inflates Raft commit time and *causes* elections
([05 §5.8](../docs/05-infrastructure.md#gcp-autopilot)). PVCs are **not** deleted with the StatefulSet;
the README must say so and give the explicit delete command, or the teardown task leaves paid-for disks.

**Measurement (the ADR-005 deliverable).** Record in the README: the value of
`etcd_server_leader_changes_seen_total` summed across members at rest after ≥20 minutes idle; then the
value after one deliberate `kubectl drain`-equivalent eviction of a single member; then the time from
member loss to a healthy 3-member endpoint list. State the observation window with each number, and label
them ASSUMPTION-free measurements — these are the inputs to the M6 alert threshold and the M7 write-up.

**No mirror.** The upstream etcd 3.6 image is pulled through Cloud NAT; do not add it to the `dlock` repo
in this task, and do not rebuild etcd from source.

## 5. Acceptance criteria

1. `kubectl apply --dry-run=server -f deploy/k8s/etcd/ -R` succeeds.
2. `kubectl -n dlock get pods -l app.kubernetes.io/name=dlock-etcd -o wide` shows 3/3 Running on three
   distinct nodes in three distinct zones.
3. `kubectl -n dlock get pvc` shows three PVCs, each `premium-rwo`, 10Gi, `Bound`.
4. `etcdctl endpoint status` inside a member lists three endpoints with exactly one leader.
5. `kubectl -n dlock get pdb dlock-etcd-pdb` reports `ALLOWED DISRUPTIONS: 1`.
6. Deleting one member pod returns the cluster to a healthy 3-endpoint list without manual intervention.
7. `grep -n 'standard-rwo' deploy/k8s/etcd/*` returns nothing.
8. `deploy/k8s/etcd/README.md` contains a leader-change count with its observation window and the PVC
   deletion command.

## 6. Verification

```
kubectl apply -f deploy/k8s/etcd/ -R && kubectl -n dlock rollout status sts/dlock-etcd --timeout=5m
kubectl -n dlock exec dlock-etcd-0 -- etcdctl --endpoints=http://localhost:2379 endpoint status -w table
kubectl -n dlock exec dlock-etcd-0 -- etcdctl member list -w table
kubectl -n dlock exec dlock-etcd-0 -- sh -c 'wget -qO- localhost:2379/metrics | grep leader_changes'
kubectl -n dlock delete pod dlock-etcd-1      # then re-run endpoint status
kubectl -n dlock get pods -o custom-columns=NAME:.metadata.name,ZONE:.spec.nodeName
```

Expected: one leader across three endpoints; after the delete, the member rejoins in the **same zone** as
its PVC and `leader_changes_seen_total` increases by at most one per lost leader.

## 7. Out of scope

Pointing `lock-server` at etcd (`LOCK_BACKEND=etcd` is an M3 rollout decision), etcd TLS and RBAC, backups
and snapshot restore, the etcd `PodMonitoring` and the leader-change alert (M6), Workload Identity and
secrets (T-057), and any autoscaling of the member count.

## 8. Hazards

- **`podManagementPolicy: OrderedReady` deadlocks the bootstrap:** member-0 cannot become ready before a
  quorum exists, and a quorum needs member-1. `Parallel` is not an optimisation here, it is required.
- **Do not name the client port `http-metrics`.** etcd serves `/metrics`, not `/actuator/prometheus`; the
  M6 `PodMonitoring` selects that port name and would scrape 404s. etcd gets its own scrape config.
- A PVC pins its pod to one zone; capacity pressure there shows up as a long `Pending`, not an error
  ([05 §5.8](../docs/05-infrastructure.md#gcp-autopilot)) — check events before assuming a manifest bug.
- Autopilot honours the PDB for *voluntary* eviction only; node failure and preemption ignore it. Say this
  in the README rather than presenting the PDB as a quorum guarantee.

## 9. On completion

Mark the T-056 row done in `tasks/README.md`, and quote the measured leader-change baseline in the note so
M6 and M7 can cite it without re-running the experiment.
