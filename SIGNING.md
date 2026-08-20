# Release signing

The release build automatically loads `release-signing/keystore.properties` when that file exists.
The whole `release-signing/` directory is excluded by `.gitignore` because it contains private material.

To produce the signed APK:

```powershell
.\gradlew.bat assembleRelease
```

Back up both the JKS file and the credentials file in a secure location. Android only permits an
installed release to be upgraded by an APK signed with the same key. Losing the key permanently
prevents future in-place updates; exposing it lets somebody else impersonate the app.

Do not commit, email, or publish the contents of `release-signing/`.
