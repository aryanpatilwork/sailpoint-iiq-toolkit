# SailPoint IIQ Toolkit

A collection of production-grade SailPoint IdentityIQ (IIQ) rules, workflows, tasks, and custom connector objects — written in Java and Beanshell. Built from real enterprise delivery experience across Fortune 500 environments.

All rules follow a consistent documentation standard: every method includes `@param` and `@return` declarations for clarity and maintainability.

---

## 📁 Structure

```
sailpoint-iiq-toolkit/
│
├── rules/
│   ├── provisioning/
│   │   ├── BeforeProvisioningRule.java
│   │   └── AfterProvisioningRule.java
│   ├── correlation/
│   │   └── AccountCorrelationRule.java
│   ├── joiner-mover-leaver/
│   │   ├── JoinerRule.java
│   │   ├── MoverRule.java
│   │   └── LeaverRule.java
│   └── field-value/
│       └── FieldValueRule.java
│
├── workflows/
│   ├── AccessRequestWorkflow.xml
│   └── LifecycleEventWorkflow.xml
│
├── tasks/
│   └── AccountAggregationTask.xml
│
├── connectors/
│   └── WorkdayConnectorConfig.xml
│
└── README.md
```

---

## 🔑 Rules

### Provisioning Rules
Rules that fire before and after provisioning operations — used to apply custom logic, transform data, and handle edge cases during account create/modify/delete.

### Correlation Rules
Custom account correlation logic for environments where default correlation (e.g. by email or employee ID) is insufficient — handles complex matching scenarios across HR and IT systems.

### Joiner / Mover / Leaver Rules
Lifecycle event rules triggered by HR system changes (typically via Workday or Oracle HCM integration). Controls access provisioning for new hires, role changes, and departures.

### Field Value Rules
Dynamic field population logic — used to derive attribute values during provisioning based on identity data, role, department, or location.

---

## ⚙️ Workflows

- **AccessRequestWorkflow.xml** — End-to-end access request lifecycle with approval routing, escalation, and provisioning steps
- **LifecycleEventWorkflow.xml** — Joiner/Mover/Leaver event handling with parallel provisioning and notification steps

---

## 📋 Requirements

- SailPoint IdentityIQ 8.x (tested on 8.2, 8.3)
- Java 8+
- Beanshell 2.x (bundled with IIQ)

---

## ⚠️ Usage

All rules are sanitised — no client data, no environment-specific configuration. Treat these as reference implementations. You will need to adapt field names, application names, and correlation logic to your environment.

---

## 📫 Questions

Reach out via [LinkedIn](https://linkedin.com/in/aryanpatilwork) or open an issue.
