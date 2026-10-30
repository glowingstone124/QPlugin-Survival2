# QPlugin-Survival2

Minecraft Paper server plugins for Quantum Original.

## CI/CD and hot updates

The `main` branch includes a permanent module host and independently loaded
`sponsor-prefix`, `daily-login`, `combat-feedback`, `text-displays` and `seasonal-events` business modules. Existing login, QMod verification and NMS services
remain in the host. See [hot-update deployment and development](docs/hot-update.md)
and [Orin pipeline templates](ci/orin/README.md).

Real Paper update/rollback checks and performance measurements are recorded in
[local Paper validation](docs/paper-validation.md).

Pushes to `main` automatically build and deploy through Orin. See [automatic deployment](docs/orin-auto-deployment.md).
The [manual recovery runbook](docs/orin-production-deployment.md) remains available for emergencies.

```sh
./gradlew build packageHotRelease -PreleaseId=<commit-or-unique-build-id>
kotlin scripts/deploy-hot.main.kts --self-test
kotlin scripts/orin-trigger.main.kts --self-test
```

Versioned release archives are written to `build/releases/`. The initial installation
of the host requires one backend restart; compatible business-module releases can
then be applied with `/qhot apply <releaseId>` or the Orin deployment client.

## License

Copyright (c) 2026 Quantum Original contributors.

Unless otherwise noted, the original source code in this project is licensed under the **GNU Lesser General Public License v3.0 only** (`LGPL-3.0-only`). See [LICENSE](LICENSE) for the LGPL terms and [COPYING](COPYING) for the incorporated GNU GPL v3.0 terms.

Third-party dependencies and separately licensed assets retain their respective licenses.
