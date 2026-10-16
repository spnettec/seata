<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License. You may obtain a copy of the License at
https://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-->

# Coverage reports

Run `./mvnw clean prepare-package` from the repository root with the same test
properties used by CI. This lifecycle runs tests, then generates and validates
JaCoCo reports without packaging or publishing artifacts. `verify` also includes
these steps, followed by the project's existing packaging/verification work.
`test` alone collects execution data but no longer generates the final report.

The existing JaCoCo 0.8.15 supports JDK 25. Both the ordinary Server tests and its
separate `raft-tests` execution finish before the `prepare-package` reports.
For a tested module containing production classes, Maven fails if execution data,
HTML or XML reports are missing or empty. Test failures remain build failures.

- Module HTML: `<module>/target/site/jacoco/index.html`
- Module XML: `<module>/target/site/jacoco/jacoco.xml`
- Dependency aggregate: `<module>/target/site/jacoco-aggregate/index.html`

Aggregates contain the current project plus its reactor dependencies; they overlap
and must not be added together as a whole-repository total. The root POM's module
list alone does not produce a complete dependency aggregate. Module reports are
the authoritative per-module baseline, including Server and separate test modules.

The JDK 25 CI job validates XML and checks required Common/Core/Server reports,
execution data and completed Raft tests. It creates `target/coverage-index.html`
and a module line/branch coverage table in the job summary. Download the
`seata-jacoco-jdk25-<run-id>-<attempt>` artifact from the workflow run, extract it,
and open `target/coverage-index.html`. HTML resources, XML and CSV are preserved
with module paths. Artifacts expire after 14 days. Missing reports fail validation
or upload; an artifact is not evidence that a failed test run passed.

No numerical JaCoCo coverage threshold existed in the POM, and none is introduced.
The former Codecov 1% project-change tolerance was a historical comparison, not an
absolute minimum. Review a successful full JDK 25 run's module baselines before
agreeing line/branch limits for `jacoco:check`; do not use partial local test runs
or overlapping aggregates to set them. There is no dummy 0% gate.

Codecov upload/configuration and token references have been removed. No secrets,
repository permissions, artifact versions or publishing settings are changed.
