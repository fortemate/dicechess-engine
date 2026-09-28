---
title: Runtime & Hosting Choices
description: Choose local JavaScript, WasmGC or JVM execution based on your application's workload and platform constraints.
---

The engine runs inside its host application. It does not require a particular cloud provider
or an engine server. Browser practice play and Dice Chess TV both use local execution;
a service or remote bot can use the JVM artifacts instead.

This page replaces an early Oracle Cloud hosting proposal. Its URL is retained for existing
links; that proposal did not establish a deployment requirement or a hardware performance guarantee.

## Match execution to the application

| Application | Execution option | What to verify |
| --- | --- | --- |
| Interactive browser game | JavaScript engine, with a Web Worker for costly search | UI responsiveness, worker messages and saved turn state |
| Native app with a compatible JavaScript runtime | Bundled JavaScript package | Runtime compatibility, input handling, lifecycle and on-device search cost |
| WasmGC-capable browser or worker | WebAssembly package and JavaScript loader | WasmGC support, asset loading, startup and workload benchmarks |
| Backend service or JVM bot | Maven rules or engine artifact | Java/Scala compatibility, memory, latency and concurrency under load |
| Android application | Platform-specific source integration | Android toolchain and API compatibility; see the artifact guide |

[Build with the Engine](/dicechess-engine/guides/integrations/) explains these paths and links
to working public applications. [Published Artifacts](/dicechess-engine/architecture/artifacts/)
defines what each package includes.

## Local play

Local rules and bots allow an application to play without a remote engine request.
The host still implements persistence, user input and any offline asset handling.
A browser Web Worker is one way to keep search off the UI thread; native application
runtimes need their own scheduling and lifecycle integration.

Measure on the intended device. A virtual-device launch or desktop benchmark does not
establish a television's memory usage, response time or sustainable search budget.

## Hosted services

Choose a host from measured workload requirements:

- Distinguish short rules queries from expensive bot search.
- Measure latency, memory and throughput at realistic concurrent request counts.
- Bound search time and resource use in the service adapter.
- Include any host-supplied models and ONNX Runtime in your measurements.
- Evaluate the provider's current availability, quotas and costs separately from engine compatibility.

The engine has no built-in HTTP service or deployment topology. Parallel chance-node search
with Ox is still [proposed](https://github.com/fortemate/dicechess-engine/issues/61); extra CPU
cores do not establish that one search will use them automatically.

## Measure before choosing

Use [JVM and JS/Wasm benchmarks](/dicechess-engine/guidelines/js-wasm-benchmarks/) for runtime
comparisons and the [search evaluation protocol](/dicechess-engine/architecture/search/03-search-roadmap/)
for move-quality comparisons. Record the engine version, hardware, runtime and workload
alongside results. Package format, processor architecture and available RAM alone do not
predict playing strength.
