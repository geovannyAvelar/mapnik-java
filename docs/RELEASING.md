# Releasing

A release is a tag. Central releases cannot be taken back, so the steps below are in the order that
catches problems while they are still free to fix.

1. **Update** `gradle.properties` (`mapnik.version`, `wrapper.revision`) and `CHANGELOG.md`.
2. **Rehearse.** In the Actions tab run **Release rehearsal** (or `gh workflow run release-rehearsal.yml`).
   It builds every natives bundle on its own platform, runs the integration tests against each on a clean
   machine, then builds the signed Central deployment and checks it with `scripts/verify-deployment.py`:
   checksums, signatures, POM fields, jar contents, version, size. It cannot publish: it never reads the
   Sonatype credentials. The deployment is kept for a week as the `deployment-rehearsal` artifact.
3. **Choose how the release finishes.** By default the tag publishes as soon as Central has validated the
   deployment (`AUTOMATIC`). For a first release, or when unsure, set the repository variable
   `CENTRAL_PUBLISHING_TYPE` to `USER_MANAGED`: the workflow then stops after validation and you press
   *Publish* in the [Central Portal](https://central.sonatype.com/publishing/deployments), after looking at
   what is in the deployment. Unset the variable to go back.
4. **Tag.** `git tag v<version>` and push it. The version must equal `mapnik.version.wrapper.revision`
   (the workflow refuses a mismatch). The release workflow runs the integration tests, builds the natives,
   repeats the deployment check, and uploads.

The same check runs inside the release, right before the upload, so a deployment that fails it is never sent.

## What the check cannot know

It cannot know what Central itself will say: its own validation (namespace ownership for `dev.avelar`,
signature key published on a key server, size limits) happens at upload. Before the first release make sure
the signing key's public half is on `keyserver.ubuntu.com` or `keys.openpgp.org`, and the `dev.avelar`
namespace is verified in the portal. The current deployment is about 275 MB.
