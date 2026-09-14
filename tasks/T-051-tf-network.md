# T-051 — Terraform module: network

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M5 (GCP infrastructure) · **Estimate** 30 min (write + `validate` only; no `apply`)

**Preconditions** — T-050. You inherit `deploy/terraform/envs/dev/` with a GCS backend, pinned
`google ~> 7.0`, the CIDR variables (`subnet_cidr`, `pods_cidr`, `services_cidr`, `psa_cidr`), the
`local.labels` map, and a `main.tf` that declares no resources yet. `deploy/terraform/modules/` is empty.

**Goal** — Write the reusable `network` module — VPC, one subnet carrying the `pods` and `services`
secondary ranges, Private Services Access, router and Cloud NAT — and wire it into `envs/dev`.

## 1. Why this task exists

Everything else in M5 hangs off this module's outputs: Autopilot cannot be created without the two
secondary range **names**, and Cloud SQL cannot get a private IP without the PSA peering. Both are
ordering traps rather than syntax traps, which is why the network is built first and alone. Cloud NAT is
not optional decoration — a private cluster with no egress fails every non-Artifact-Registry image pull.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| `dlock-vpc`, `dlock-subnet`, secondary ranges named exactly `pods` and `services` | [C5 §5.6](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| Module named `network`; sole-resource-of-a-type local name is `this` | [C5 §5.6](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| Module inputs and outputs (exact names) | [05 §5.4](../docs/05-infrastructure.md#gcp-tf) |
| PSA-before-SQL ordering, missing-secondary-ranges failure | [05 §5.6](../docs/05-infrastructure.md#gcp-traps) traps 1 and 4 |
| Modules are never applied directly; env root is the only apply point | [ADR-008 D1](../docs/adr/ADR-008-terraform-layout-state-and-provider-versions.md#adr8-decision) |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report**, per
[04 §4.4](../docs/04-contracts.md#c-precedence).

## 3. Deliverables

| Path | What |
|---|---|
| `deploy/terraform/modules/network/versions.tf` | `required_providers` for google only; no `required_version` duplication of the root's pin beyond `~> 1.15` |
| `deploy/terraform/modules/network/variables.tf` | `project_id`, `region`, `subnet_cidr`, `pods_cidr`, `services_cidr`, `psa_cidr`, `labels` |
| `deploy/terraform/modules/network/main.tf` | the six resources listed in §4 |
| `deploy/terraform/modules/network/outputs.tf` | `network_self_link`, `subnet_self_link`, `pods_range_name`, `services_range_name`, `psa_connection_id` |
| `deploy/terraform/modules/network/README.md` | what it creates, why NAT exists, the two traps |
| `deploy/terraform/envs/dev/main.tf` (modify) | add `module "network"` passing the root variables |
| `deploy/terraform/envs/dev/outputs.tf` (modify) | re-export the two range names and the PSA connection id |

## 4. Specification

**Resources**, all in `main.tf`, in this order:

| Resource | Local name | Arguments that matter |
|---|---|---|
| `google_compute_network` | `this` | `name = "dlock-vpc"`, `auto_create_subnetworks = false`, `routing_mode = "REGIONAL"` |
| `google_compute_subnetwork` | `this` | `name = "dlock-subnet"`, primary `ip_cidr_range = var.subnet_cidr`, two `secondary_ip_range` blocks with `range_name` `pods` and `services`, `private_ip_google_access = true` |
| `google_compute_global_address` | `psa` | `purpose = "VPC_PEERING"`, `address_type = "INTERNAL"`, `prefix_length = 16`, `network` = the VPC |
| `google_service_networking_connection` | `psa` | `service = "servicenetworking.googleapis.com"`, `reserved_peering_ranges` = the global address name |
| `google_compute_router` | `this` | `name = "dlock-router"`, region and network |
| `google_compute_router_nat` | `this` | `nat_ip_allocate_option = "AUTO_ONLY"`, all subnets + all IP ranges, log config errors only |

**Range names are literals, not variables.** GKE's `ip_allocation_policy` in T-053 references the strings
`pods` and `services`; expose them as outputs so the value flows rather than being retyped, but do not make
them configurable — that is a C5 §5.6 pin. The **CIDRs** are variables; the **names** are not.

**PSA sizing.** `prefix_length = 16` on the reserved range is deliberate: Cloud SQL allocates from it and a
too-small reservation fails only when the second instance is created, i.e. in T-052, not here. Do not shrink it
to look tidy. `psa_cidr` therefore constrains only the reservation's starting address if set; document that
in the module README rather than silently ignoring the variable.

**Outputs.** `psa_connection_id` exists purely so T-052 can write `depends_on` against a value rather than a
cross-module resource address. Emit the connection's `id`. If google 7.x renames the attribute, report it —
do not substitute `network`.

**Labels.** `google_compute_network` and `google_compute_subnetwork` do not accept `labels`; the root's
`default_labels` covers what can be labelled. Accept `var.labels` for uniformity and use it only where valid.

## 5. Acceptance criteria

1. `terraform -chdir=deploy/terraform/envs/dev fmt -check -recursive` exits 0.
2. `grep -n 'range_name' deploy/terraform/modules/network/main.tf` shows exactly two hits, `pods` and `services`, as string literals.
3. `outputs.tf` declares all five outputs from 05 §5.4 with those exact names.
4. `main.tf` contains no `google_compute_firewall` and no `google_compute_address` other than the PSA global address.
5. `envs/dev/main.tf` contains exactly one `module "network"` block and no network resource of its own.
6. `terraform -chdir=deploy/terraform/envs/dev validate` reports the configuration valid.
7. The module README names trap 1 (PSA before SQL) and trap 4 (missing secondary ranges) and says which later task each bites.

## 6. Verification

`terraform -chdir=deploy/terraform/envs/dev init -backend=false && terraform -chdir=deploy/terraform/envs/dev validate`
— expected `Success! The configuration is valid.`
`terraform -chdir=deploy/terraform/envs/dev fmt -check -recursive` — expected no output, exit 0.
`grep -rn 'secondary_ip_range' deploy/terraform/modules/network` — expected two blocks.
No `plan`/`apply`/`gcloud`: the CIDR arithmetic is reviewed on paper here and proven at the T-054+ apply.

## 7. Out of scope

Firewall rules (Autopilot's defaults plus private cluster suffice for this project; if a rule turns out to be
needed, T-055 owns it). Cloud SQL and its `depends_on` are T-052. `ip_allocation_policy` is T-053.
Artifact Registry, observability, budget: T-054…T-059.

## 8. Hazards

- **Trap 4, the commonest first-run Autopilot failure:** ranges declared on the subnet but never referenced by name, or referenced with a different name, and the cluster creation fails outright with an opaque error. The literal-string rule above exists for this.
- **Trap 1:** the PSA connection is not always inferred as a dependency. This module must make the connection id an output; T-052 must use it.
- Overlapping CIDRs among `subnet_cidr`/`pods_cidr`/`services_cidr`/PSA validate fine and fail at apply. Sanity-check the ranges are disjoint and record them in the module README.
- Destroying PSA while an instance uses it hangs (trap 2) — teardown order is T-059's problem, but do not add `lifecycle` blocks here that would make it worse.
- No git commands ([ADR-011](../docs/adr/ADR-011-no-git-initialisation-yet.md)).

## 9. On completion

Mark T-051 done in `tasks/README.md`, record the four chosen CIDRs in one line, and note any google 7.x
attribute rename you hit.
