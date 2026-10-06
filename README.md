# eternax-platform

Spring starter used by every servlet-based eternaSync service, plus `eternax-test-support`.

```kotlin
implementation("com.eternax:eternax-platform:0.1.0")
testImplementation("com.eternax:eternax-test-support:0.1.0")
```

It pins `com.eternax:eternax-core` through `eternaxCoreVersion` in `gradle.properties`.
Release: push a tag `vX.Y.Z`; CI publishes both modules to GitHub Packages.
