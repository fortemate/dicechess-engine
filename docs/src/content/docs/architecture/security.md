---
title: Security Policy
description: Security architecture, active scanners, and private vulnerability reporting guidelines for the Dice Chess Engine.
---

To ensure the integrity, safety, and reliability of the Dice Chess Engine, the repository employs a multi-tiered security and analysis strategy. This page documents our active security measures and outlines how to report vulnerabilities.

---

## Active Security Measures

Our codebase is continuously monitored using industry-standard static analysis and security tools:

| Layer | Tool / Service | Security Focus |
| :--- | :--- | :--- |
| **Local Pre-commit** | `betterleaks` | Prevents API keys, credentials, and secrets from entering git history. |
| **CI Workflow Analysis** | `actionlint` + **ShellCheck** | Validates workflow structure and checks embedded shell plus repository release/build scripts. |
| **CI Static Analysis** | **CodeQL** | Analyzes GitHub Actions and JavaScript/TypeScript sources. Scala is outside CodeQL's supported languages. |
| **CI Secret Scan** | `betterleaks` (via CodeRabbit) | Double-checks all PR changes for secrets before merging to `main`. |
| **Vulnerability Scanning** | **SonarCloud** | Automatically flags code smells, logic errors, and security issues. |
| **Dependency Graph** | Sbt Dependency Submission | Submits the resolved Scala/JVM graph so GitHub can monitor transitive dependencies that are not statically visible in `build.sbt`. |
| **Dependency Audits** | **Dependabot** + Dependency Review | Opens version/security updates and blocks known high or critical dependency vulnerabilities in PR dependency changes GitHub can derive safely. See [Dependency Updates](/dicechess-engine/architecture/dependency-updates/). |
| **Push Protection** | GitHub Secret Scanning | Rejects push events containing detected credentials. |

---

## Private Vulnerability Reporting

If you discover a security vulnerability in the engine, please **do not create a public issue or public pull request**. Instead, submit a private report so we can resolve the issue before public disclosure.

### Preferred Method: GitHub Private Vulnerability Report

GitHub provides a native, secure channel for vulnerability disclosure:
1. Go to the main page of the [dicechess-engine](https://github.com/fortemate/dicechess-engine) repository.
2. Under the repository name, click **Security**.
3. In the left sidebar under *Vulnerability reporting*, click **Advisories**.
4. Click **Report a vulnerability** to fill out a secure form.

### Alternative Method: Direct Contact

You can also contact the maintainer directly via email: **jegors.cemisovs@gmail.com**. Please use a descriptive subject line (e.g., `[Security Vulnerability] Dice Chess Engine`).

### Response and Disclosure Timeline

1. **Acknowledgment**: We will acknowledge receipt of your report within **48 hours**.
2. **Status Update**: We will provide a fix timeline or status update within **7 days**.
3. **Disclosure**: A patched release and public security advisory will be published once the fix is deployed.
