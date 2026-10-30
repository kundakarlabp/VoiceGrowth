# VoiceGrowth Android upgrades: mandatory data-preservation policy

## Release invariant (must pass before any APK is called an "update")

Never ask an existing VoiceGrowth user to uninstall the currently installed
app to install a newer APK. The original app may hold clinical recordings,
upload queue/Room metadata, account/Drive SAF grants, processing history,
transcripts, and private user preferences that are erased by uninstall.

Android's in-place update requires all of the following:
1. **Exact same applicationId** (`com.voicegrowth.app` for production;
   `com.voicegrowth.app.preview` for existing preview installs).
2. **Same APK signing certificate** or a correctly configured Android-supported
   signing-key rotation. Hashes of APK files do not establish signing identity.
3. **Higher versionCode** than the installed app; never reuse the same code
   for distributed updates.
4. Compatible Room migrations and DataStore preferences; avoid clearing
   storage, changing applicationId, uninstall/reinstall migration, or
   `fallbackToDestructiveMigration`.
5. Smoke-test update-over-old-version on a device/emulator with synthetic
   recording data and verify retention and Drive permissions.
6. Confirm the downloaded APK is a *signed production upgrade* or clearly
   label it **side-by-side experimental preview** with a different applicationId.

**Never describe an ephemeral GitHub Actions debug APK as an upgrade.** A
fresh GitHub Actions runner can create its own debug signing key, which
will not match a prior runner's debug certificate even if the package
ID and version match. The current `debug` build uses
`applicationIdSuffix = ".preview"`. It may not update an older debug
preview installed from a different runner.

The repository has `.github/workflows/release-apk.yml` for signed
production builds using encrypted GitHub secrets; its signing key must be
verified as the same key that signed the user's existing production app.
The fact that `assembleRelease` completed alone is insufficient proof
of update compatibility.

## Required release checks

Before creating the next APK for a specific installed app, obtain the
**original, unmodified installed APK or its verified signing certificate
fingerprint** and current versionCode (do not ask for signing private
keys/passwords in chat). Verify:
- `aapt dump badging existing.apk` and `aapt dump badging candidate.apk`:
  same `package name`, strictly increasing `versionCode`.
- `apksigner verify --print-certs existing.apk` and the candidate: exact
  matching `Signer #1 certificate SHA-256 digest` (unless a valid supported
  key-rotation lineage has been independently verified).
- Test the update by installing candidate APK **over** existing APK with
  original data populated. The data, audio, transcript paths, queued
  WorkManager jobs and persisted Drive tree URI should remain readable.
- Validate original recordings against independently confirmed remote
  Google Drive copies before recommending *any* risky operation.

If the original signing key is unavailable or mismatched, there is **no
general safe in-place upgrade**. Do not work around this by changing
versionCode alone or telling the user to uninstall. Preserve the old
install. If required, develop an explicit user-authorized export/import
migration and verify its completeness before decommissioning the old app.


### Signed-release CI safety gate

The `Signed Android Release` workflow now **fails closed** unless the
repository administrator configures these values, verified against the actual
installed package:

- GitHub Actions secret `VOICEGROWTH_BASELINE_SIGNING_SHA256`: SHA-256 digest
  of the certificate which signed the current installed production APK.
  This fingerprint is public metadata, **not** the private signing key.
- GitHub Actions repository variable `VOICEGROWTH_BASELINE_VERSION_CODE`:
  numeric `versionCode` of the current installed production APK.

The workflow checks the new package is `com.voicegrowth.app`, its certificate
matches the baseline exactly, and its `versionCode` increases. A build without
the baseline is deliberately **not** published as an upgrade. The workflow
does not configure credentials on your behalf. Never paste signing private
keys, keystores or passwords into an issue, chat or public repository.

## Gemma model retention

- A Gemma model downloaded inside **Google AI Edge Gallery** resides in
  Gallery's own app storage, independent of VoiceGrowth. Updating or
  uninstalling VoiceGrowth alone does not delete Gallery's private model.
- Uninstalling Gallery or clearing its app storage generally deletes
  Gallery's downloaded model. A normal Gallery update typically preserves
  it, subject to the app's migration behavior.
- A Gemma model imported **directly into the VoiceGrowth Gemma benchmark
  app** resides in that benchmark app's private storage and is at risk
  when that benchmark package is uninstalled/cleared. It is not reused
  from Gallery's private model storage.
- Do not confuse Google AI Edge Gallery, VoiceGrowth preview, the separate
  Gemma benchmark, and the production VoiceGrowth package.

## Required release response language

Distinguish clearly among:
- **Production in-place update**: old package, original signing identity,
  higher versionCode, migration/device-tested.
- **Side-by-side preview**: distinct package; cannot see production
  app-private data. May require reconfiguring Google Drive SAF access.
- **Unverified APK**: do not claim it can update anything yet.

References: https://developer.android.com/studio/publish/app-signing ;
https://developer.android.com/google/play/app-updates .
