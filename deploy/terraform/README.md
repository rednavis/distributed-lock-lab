# deploy/terraform

Terraform root and environment directories for the Google Cloud deployment ([C5 §5.5](../../docs/contracts/C5-config-build-and-naming.md#ct5-layout)):
`envs/dev` plus one module per concern, with state in `gs://dlock-tfstate`. Owned by milestone
**M5 — Cloud infrastructure**, starting with T-050 (root, dev environment and the budget alert that must
exist before the first apply) and T-051…T-054; this placeholder from T-001 is replaced by T-050's README.
Applying anything here bills real money — read [05-infrastructure](../../docs/05-infrastructure.md) first.
