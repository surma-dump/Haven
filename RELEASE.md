# Release Process

## 1. Bump version

Edit `app/build.gradle.kts`:
```kotlin
versionCode = <increment>
versionName = "<x.y.z>"
```

## 2. Commit, tag, push

```bash
git add app/build.gradle.kts <changed files>
git commit -m "Bump to v<x.y.z>"
git tag v<x.y.z>
git push origin main v<x.y.z>
```

The `v*` tag triggers the **Release** workflow on GitHub Actions which:
- Builds a signed APK and AAB (keystore password from GitHub secrets)
- Creates a GitHub release with the APK attached

## 3. Download Play Store bundle

```bash
mkdir -p releases/v<x.y.z>
gh release download v<x.y.z> --repo GlassOnTin/Haven --pattern '*.aab' -D releases/v<x.y.z>/
```

Upload the AAB to Google Play Console.

## 4. Update F-Droid merge request

The F-Droid metadata lives in a GitLab fork:
- Repo: `ianrosswilliams/fdroiddata`
- Branch: `haven-ssh-client`
- MR: `fdroid/fdroiddata!33920`

Edit `metadata/sh.haven.app.yml`:
- **Replace** the single build entry (only keep the latest version)
- Use the **full commit hash**, not the tag name
- Update `CurrentVersion` and `CurrentVersionCode`

```bash
# Get the commit hash for the tag
git rev-parse v<x.y.z>
```

```yaml
Builds:
  - versionName: <x.y.z>
    versionCode: <code>
    commit: <full commit hash>
    subdir: app
    gradle:
      - yes

CurrentVersion: <x.y.z>
CurrentVersionCode: <code>
```

### Rebase before pushing

The fork's branch must be on top of upstream master or GitLab won't merge it.
Clone upstream, create the branch from its master, and force-push to the fork:

```bash
cd /tmp
git clone --no-checkout --single-branch --branch master \
  "https://oauth2:${GITLAB_TOKEN}@gitlab.com/fdroid/fdroiddata.git" fdroiddata-rebase
cd fdroiddata-rebase
git remote add fork \
  "https://oauth2:${GITLAB_TOKEN}@gitlab.com/ianrosswilliams/fdroiddata.git"
git checkout -b haven-ssh-client master
# create/update metadata/sh.haven.app.yml here
git add metadata/sh.haven.app.yml
git commit -m "Add Haven SSH client v<x.y.z>"
git push fork haven-ssh-client --force
cd / && rm -rf /tmp/fdroiddata-rebase
```

### MR description template

The MR description must follow the F-Droid "App inclusion" template with Required/Strongly Recommended/Suggested checkboxes. See `.gitlab/merge_request_templates/App inclusion.md` in fdroiddata.

## 5. Verify

- [ ] GitHub release page has APK
- [ ] CI workflow passes (lint + tests)
- [ ] F-Droid MR pipeline passes
- [ ] Play Store bundle uploaded (if applicable)

## Signing

The release keystore `haven-release.jks` is in the repo root.
Passwords are stored in GitHub secrets:
- `KEYSTORE_PASSWORD`
- `KEY_PASSWORD`
- `KEY_ALIAS`

Local release builds require these as environment variables:
```bash
export KEYSTORE_PASSWORD=<password>
export KEY_PASSWORD=<password>
./gradlew :app:bundleRelease
```

## F-Droid details

- GitLab username: `ianrosswilliams`
- GitHub username: `GlassOnTin`
- F-Droid builds from source using the tagged commit
- `AutoUpdateMode: Version` + `UpdateCheckMode: Tags` means F-Droid will auto-detect new tags
