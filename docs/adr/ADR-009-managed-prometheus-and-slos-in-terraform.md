# ADR-009 — Managed Prometheus, and SLOs that live in the infrastructure repo {#adr9}

| Field | Value |
|---|---|
| **Status** | **Accepted**, 2026-08-21 |
| **Decider** | Project owner / architect |
| **Scope** | `deploy/terraform/modules/observability`, `deploy/k8s/*` `PodMonitoring`; consumes but does not define metric names |
| **Contracts** | [C4 §4.2](../contracts/C4-observability.md#ct4-metrics) · [C4 §4.3](../contracts/C4-observability.md#ct4-cardinality) · [C4 §4.4](../contracts/C4-observability.md#ct4-zero) · [C4 §4.7](../contracts/C4-observability.md#ct4-lbm) · [C4 §4.9](../contracts/C4-observability.md#ct4-scrape) · [C4 §4.11](../contracts/C4-observability.md#ct4-nonspec) |
| **Requirements** | [00 §0.2](../00-charter.md#ch-versions) (V1 operational practice imported wholesale) · [00 §0.6](../00-charter.md#ch-learning) (SLI→SLO→error budget→**written policy with consequences**) |
| **Related** | [ADR-008](ADR-008-terraform-layout-state-and-provider-versions.md) (Terraform layout) |

## 9.1 Context {#adr9-context}

Every service claims to have SLOs. The claim usually means a slide with three numbers on it that nobody
can point at from an alert. When the numbers live only in a deck, three things follow: they are never
reviewed against reality, they can be quietly edited after a bad month, and no alert can be traced to
them. **A number in a deck is an aspiration. A number in the repository is a constraint** — it has an
owner, a diff, a review, and a blast radius when it changes.

Concrete inputs already fixed elsewhere: the applications expose `/actuator/prometheus` on a container
port that **must** be named `http-metrics`, scraped by Managed Service for Prometheus via `PodMonitoring`
([C4 §4.9](../contracts/C4-observability.md#ct4-scrape) — the named-port trap fails *silently*, which is
why verification order is pinned there). Metric names, tag value sets and the cardinality budget are
contract ([C4 §4.2](../contracts/C4-observability.md#ct4-metrics), [§4.3](../contracts/C4-observability.md#ct4-cardinality)).
Two counters have a healthy value of exactly zero ([C4 §4.4](../contracts/C4-observability.md#ct4-zero)).
What remains undecided is *where the SLO definitions, alert policies and the error-budget policy live* —
and that is what this ADR settles.

## 9.2 Decision {#adr9-decision}

| # | Rule |
|---|---|
| D1 | **Managed Service for Prometheus** (managed collection) is the metrics pipeline. No self-hosted Prometheus, no Grafana to operate. Rationale: the project is about lock correctness and SRE practice, not about running a TSDB, and an unmanaged Prometheus on Autopilot is one more evictable stateful workload. |
| D2 | **`PodMonitoring` manifests ship in `deploy/k8s/` next to the workload they scrape**, one per workload, referencing the port **by name** (`http-metrics`). Scrape config is part of the deployable, not an operator afterthought. |
| D3 | **Every SLO is a Terraform resource** (`google_monitoring_slo` against a `google_monitoring_custom_service`), in `modules/observability`. Creating, widening or deleting an SLO is a code change with a diff. |
| D4 | **Every alert policy is a Terraform resource** (`google_monitoring_alert_policy`), and each one carries, as required fields we enforce by convention: a **runbook link** in its documentation, a **symptom-or-cause** classification, and a notification channel that matches that classification. No alert exists in the console only. |
| D5 | **Symptom pages, cause tickets** — mechanically, not aspirationally: symptom policies (SLO burn on payout success / latency, `payout.backlog.age.seconds`) route to the paging channel; cause policies (etcd leader-election rate, replica lag, connection-pool saturation) route to a ticket channel. The channel is the enforcement of the doctrine. |
| D6 | The **two must-be-zero counters** get `> 0` alert policies at page severity, with their own runbooks. They are not SLO burn alerts; they are correctness alarms ([C4 §4.4](../contracts/C4-observability.md#ct4-zero)). |
| D7 | **Log-based metrics are Terraform too** (`google_logging_metric`), derived from the pinned log events and their promoted label set ([C4 §4.6](../contracts/C4-observability.md#ct4-promotion), [§4.7](../contracts/C4-observability.md#ct4-lbm)) — never hand-created, because a hand-created log metric silently starts its history at "whenever someone clicked". |
| D8 | **Numbers have exactly one home: `docs/06-observability-and-slo.md` is the authority; one Terraform `locals` block mirrors it.** Targets, windows and burn-rate thresholds appear nowhere else — not in a dashboard JSON, not in an alert body. Any target quoted anywhere in this document set is an explicitly labelled **ASSUMPTION** until the M7 measurements land; none of it is production data. |
| D9 | The **error-budget policy is a versioned markdown file in the repo** with named consequences (what stops shipping when the budget is exhausted), and the burn alert links to it. A policy with no consequence is a comment. |
| D10 | Dashboards are Terraform-managed and **disposable**; if a dashboard disappears nothing breaks. SLOs and alerts are not disposable. |

The chain this makes auditable, end to end:

```
  metric name (C4 contract)
      -> SLI expression        (modules/observability, one locals block)
      -> SLO target + window   (google_monitoring_slo          -- reviewed diff)
      -> burn-rate alert       (google_monitoring_alert_policy -- runbook link required)
      -> notification channel  (page = symptom | ticket = cause)
      -> error-budget policy   (docs/, versioned, with consequences)
```

Break any link and the SLO is decoration again.

## 9.3 Consequences {#adr9-consequences}

**Positive**

- Changing an SLO target requires a visible diff, which is the whole point: loosening a target becomes a decision someone owns rather than an edit nobody sees.
- `terraform destroy` / `apply` rebuilds the entire observability posture with the environment, so a rebuilt project is not silently unmonitored.
- The game day has a fixture: every alert policy is enumerable from code, so "fire every alert on purpose and record detection latency" is a list, not a memory exercise.
- Alert quality is structurally enforced — no runbook link, no merge.

**Negative**

- Managed Prometheus is GCP-specific; the local `docker compose` path ([NFR-15](../01-requirements.md#br-nfr)) has scraping but no SLO evaluation, so SLO work is only exercisable in the cloud environment.
- Alert iteration is slower than clicking in the console, and a bad threshold costs a plan/apply cycle.
- `google_monitoring_slo` is fiddly about SLI shapes; some intuitive SLIs need reformulating to fit, and PromQL-native alerting rules and Cloud Monitoring policies are two dialects to keep straight.
- Terraform now owns objects that on-call may be tempted to hand-edit at 03:00; a console edit will be reverted by the next apply, which must be in the runbook.

**What we accept**

- A managed pipeline we cannot fully introspect, in exchange for not operating a TSDB on a bin-packing cluster.
- Cardinality discipline is a human contract ([C4 §4.3](../contracts/C4-observability.md#ct4-cardinality)), not something Terraform can enforce for us.

## 9.4 Alternatives considered {#adr9-alternatives}

| Alternative | Why rejected |
|---|---|
| **SLOs in a deck / wiki page** | The status quo this ADR exists to reject: no diff, no owner, no link from an alert, editable after the fact. Aspiration, not constraint. |
| SLOs and alerts **created in the console** | Fastest to start, impossible to review, and lost on project rebuild. Also breaks the game-day enumeration. |
| **Self-hosted Prometheus + Alertmanager + Grafana** | Genuinely more portable, and a stateful workload to run on Autopilot plus rules in a second language. Off-topic cost for this project; revisit only if a non-GCP target appears. |
| **Alerting rules in PromQL only** (`Rules` CRD), no Cloud Monitoring SLOs | Loses first-class error-budget accounting and burn-rate windows, which are exactly the artifacts the SRE half of the project must demonstrate. |
| SLOs as code but in a **separate repo** | Splits the diff: a metric rename in the application would land in one repo and break the SLI in another, and no reviewer would see both. |
| Threshold numbers **inline in each alert policy** | Guarantees drift between the doc and three alert bodies; the first inconsistency destroys trust in all of them. |
| **No error-budget policy**, just dashboards | Then the budget has no consequence and the SLO is again an aspiration with better tooling. |

## 9.5 Revisit when {#adr9-revisit}

| Trigger | Action |
|---|---|
| The M7 benchmark produces measured latency/availability distributions | Replace the ASSUMPTION targets in `docs/06` and the mirroring `locals`; record the change in the error-budget policy's history. |
| An SLO target is loosened twice within two quarters | The SLO is wrong or the service is; escalate to a design review, not a third edit. |
| A page fires with no runbook, or a runbook 404s | Treat as a broken alert (D4 violated) and fix or delete the alert — an unactionable page trains people to ignore pages. |
| Measured Autopilot etcd leader elections exceed the budgeted allowance ([00 §0.8 D-05](../00-charter.md#ch-deferred)) | The election-rate *cause* ticket becomes a design decision, not a tuning exercise. |
| A non-GCP deployment target appears | Reconsider D1 only; D3/D4/D8/D9 (code-owned SLOs, runbook-linked alerts, one home for numbers) are portable and stay. |
| Cardinality alerts or billing show metric-volume growth | Audit tag sets against [C4 §4.3](../contracts/C4-observability.md#ct4-cardinality) before adding aggregation; the budget is a contract. |
