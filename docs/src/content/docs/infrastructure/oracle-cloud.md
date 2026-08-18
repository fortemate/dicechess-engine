---
title: Infrastructure Analysis
description: Comparative assessment of compute environments and the strategic decision to target Oracle Cloud Free Tier.
---

Deploying a **Scala 3 Chess Engine** introduces highly specific compute requirements. Unlike typical CRUD apps, a chess engine is fundamentally **CPU-bound** (move search, bitwise ops) and **RAM-hungry** (Transposition Tables for caching evaluations). 

Here is an objective evaluation of our hosting options.

---

## 📊 Comparative Assessment Matrix

| Feature | 🥧 Raspberry Pi 4 (4GB) | 💻 Asus FX570UD (8GB) | ☁️ Oracle Cloud (Ampere) |
| :--- | :--- | :--- | :--- |
| **CPU Performance** | Low (ARMv8 A72) | High (Intel Core i7/i5 Mobile) | Medium-High (Ampere A1 ARM) |
| **RAM Availability** | 4 GB (Dedicated) | 8 GB (Shared / Congested) | **Up to 24 GB (Free Tier)** |
| **Max Search Depth** | Low (Slow single-thread) | High (Fast clock speed) | High (4 True Cores) |
| **Transposition Cache**| Small (<500 MB) | Moderate (<1 GB due to load) | **Huge (4 GB to 16 GB)** |
| **Network Reliability** | Dependent on Home ISP | Dependent on Home ISP | **Excellent (Datacenter)** |
| **Cost** | $0 (Owned) | $0 (Owned) | **$0 (Forever Free)** |
| **Architecture** | ARM64 | x86_64 | ARM64 |

---

## 🔍 Detailed Node Evaluation

### 1. Raspberry Pi 4 (4GB RAM)
* **The Verdict:** **Too weak for Production Search.**
* **Why:** While it runs 24/7, its Cortex-A72 cores have extremely weak floating-point performance and low clock speed compared to modern CPUs. Using an Expectimax search algorithm parallelized with Virtual Threads on this node would yield a very weak engine that takes too long to evaluate moves. 
* **Best Use:** A lightweight gateway, dev mock, or telemetry receiver.

### 2. Asus FX570UD (Ubuntu Server, 8GB RAM)
* **The Verdict:** **Excellent CPU, but severe RAM congestion.**
* **Why:** The Intel CPU has fantastic single-core turbo speeds, meaning its move generation and evaluation loop would be blazing fast. However, **8GB RAM is already a bottleneck** if it hosts heavy stacks like Immich (Postgres + machine learning background workers for face recognition), AdGuard, Vaultwarden, and an Nginx proxy. 
* Adding a Java Virtual Machine (JVM) or running heavy compilation cycles could trigger Linux Out-Of-Memory (OOM) killer events, destabilizing your existing home services.
* **Best Use:** Staging environment, local quick benchmarks, or build node.

### 3. Oracle Cloud Free Tier (Ampere A1)
* **The Verdict:** 🏆 **The Ultimate Champion.**
* **Why:** Oracle's "Always Free" tier offers up to **4 ARM64 Ampere Cores** and up to **24 GB of RAM**. 
  * **Transposition Tables (TT):** In chess engines, doubling the TT size often results in a massive boost in ELO. Having access to 24GB RAM lets you dedicate 4-8GB strictly to the engine cache!
  * **Parallelism:** 4 dedicated cores perfectly complement the **Virtual Threads (Ox)** parallelized Expectimax search (implemented in v0.6).
  * **Architecture Harmony:** Your dev machine is a Mac (Apple Silicon ARM64), and Oracle's Ampere is ARM64. This means your Docker containers will run natively on both without cross-compilation overhead.
  * **Public Access:** It gives you a public IP and stable egress so anyone can play against it without you exposing your home lab to the internet.
* **Best Use:** Production engine hosting.

---

## 🎯 Strategic Recommendation: The Hybrid Approach

Instead of picking only one, we utilize a highly cost-effective and robust **Multi-Tier Strategy**:

### Tier 1: Production (The Brain) 🧠 -> Oracle Cloud (ARM64)
Configure a single Oracle Free Tier VM with:
* 2 to 4 Ampere OCPUs
* 12 to 24 GB RAM
* Public IP with Oracle Security Lists for external access.
* *Result:* A massive transposition table, enterprise network uptime, and zero interference with home Immich/AdGuard setups.

### Tier 2: Delivery & Gateway 🚪 -> Asus Server (Home Lab)
Use your Asus Laptop to:
* Act as a **Docker Registry** or **CI Runner** to build the GraalVM images.
* Serve the static Frontend PWA and use Nginx Proxy Manager to securely proxy traffic to the Oracle Cloud backend if necessary.

### Tier 3: Development & Sandbox 💻 -> Apple Silicon Mac
Build and run local benchmarks natively in the `mise` environment. 

---

### 🚀 Next Steps
1. **Sign up for Oracle Cloud Free Tier** and provision an `Ubuntu VM.Standard.A1.Flex` shape with 4 OCPUs and 24GB RAM.
2. Upon reaching **Milestone v1.0 (Production & Native Image)**, we will tailor the GraalVM native image target explicitly for **ARM64 Linux**, making deployment a single `docker compose up -d` command with sub-millisecond startup times.
