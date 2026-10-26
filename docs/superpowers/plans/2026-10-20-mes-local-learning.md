# MES Local Learning Implementation Plan

> **For agentic workers:** Execute the authorized environment tasks inline. Teaching follows learn-project; the learner owns later business implementation.

**Goal:** Run this real Java MES locally and verify one material-to-routing flow before guided practice.

**Architecture:** Keep upstream business Java unchanged. Use JDK 8, an isolated MySQL 8 data directory on port 3308, a portable Redis development instance on 6380, and an external local Spring profile on localhost:9090. Existing MySQL services and workspace notes remain intact.

**Tech Stack:** Spring Boot 2.1.7, Java 8, MyBatis-Plus, MySQL 8, Redis, PowerShell.

- [x] Clone and inspect the upstream repository, instructions, manifests and actual controllers.
- [x] Configure and start the isolated dependencies with scripts/local/Start-Local.ps1; private runtime data stays in ignored .local/.
- [x] Verify compilation, real login, material creation/query, route association and persisted rows; run the existing context test against local services.
- [x] Write local run instructions and an evidence-based learning ledger; demonstrate one flow and wait for the learner's attempt.

Verification must not bypass authentication or change business rules. Any discovered upstream defect gets a minimal reproduction and explanation before a fix. No commit, push, branch switch or alteration of the existing MySQL services is required.
