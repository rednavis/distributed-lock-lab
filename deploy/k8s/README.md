# deploy/k8s

Kubernetes manifests for GKE Autopilot: one directory per workload, named after its module and running
under its own Kubernetes service account, plus the `dlock-etcd` StatefulSet
([C5 §5.6](../../docs/contracts/C5-config-build-and-naming.md#ct5-naming)). Owned by milestone
**M5 — Cloud infrastructure**: T-055 (the six services), T-056 (etcd) and T-057 (Workload Identity and
secrets); this placeholder from T-001 is replaced by T-055's README.
