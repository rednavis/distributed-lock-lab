// Root build: convention plugins only — no versions, no dependencies, no per-module logic
// (ADR-010 D2, C5 #ct5-layout). Each module applies its own dlock.*-conventions plugin.

plugins {
    id("dlock.root-conventions")
}
