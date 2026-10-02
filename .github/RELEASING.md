# Publishing TMark Kotlin

The Maven version is `0.1.0`; the matching Git tag is `v0.1.0`. Publish from a clean `master` checkout. Only `core` and `android` are Maven publications.

1. In [Central Portal → Namespaces](https://central.sonatype.com/publishing/namespaces), request `app.waus.tmark`. Add the verification key as a DNS TXT record on **`tmark.waus.app`**, then select **Verify Namespace**. The Gradle `group` is already `app.waus.tmark`. Do not publish until the namespace shows **Verified**.
2. Generate a [Portal user token](https://central.sonatype.com/usertoken). Its username and password are different from the portal login. Store both outside this repository.
3. Create a PGP key with `gpg --full-generate-key` if needed. Find its ID with `gpg --list-secret-keys --keyid-format LONG`, then publish the public key with `gpg --keyserver keyserver.ubuntu.com --send-keys KEY_ID`. Keep the private key and passphrase secret.
4. Set Gradle credentials in `~/.gradle/gradle.properties`:

   ```properties
   mavenCentralUsername=TOKEN_USERNAME
   mavenCentralPassword=TOKEN_PASSWORD
   ```

   For the current shell, provide the signing key through Gradle project environment variables:

   ```sh
   export ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --armor --export-secret-keys KEY_ID)"
   export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword='KEY_PASSPHRASE'
   ```

5. Run the CI checks locally or wait for a green `master` CI run. Inspect local Maven publications before uploading:

   ```sh
   ./gradlew :core:publishToMavenLocal :android:publishToMavenLocal \
     -Dmaven.repo.local="$PWD/build/verification-m2"
   python3 .github/scripts/check_publications.py build/verification-m2
   ```

6. Commit and push the release state to `master`, then create and push the `v0.1.0` Git tag. Upload the signed artifacts:

   ```sh
   ./gradlew publishToMavenCentral
   ```

7. In [Central Portal → Deployments](https://central.sonatype.com/publishing/deployments), review the validated deployment and select **Publish**. Maven Central versions are immutable; confirm the coordinates and contents before this step. Create the GitHub release from `v0.1.0` after publication.

Never put the token, private key or passphrase in the repository, Gradle project properties, a GitHub release, or an issue.
