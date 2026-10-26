# Gates: approved-interface package fixture and build

OWNS: routing/src/test/java/org/eurorig/routing/PackageImportTest.java, routing/src/test/resources/region-package.eurorig, routing/src/test/resources/README.md, GATES.md, CONTINUATION.md

Scope: restore the absent package-import fixture using original synthetic assets and the existing packager, then validate the approved-interface branch.

Prerequisites: JDK 21, SDK 36/build-tools 36.0.0, Python 3.12 in .toolchain/qa-python with osmium==4.3.1, and the short socket directory recorded in CONTINUATION.md.

- [x] G1: Java streams every packaged synthetic fixture entry without ZIP import errors
  CHECK: gradlew.bat :routing:test --tests org.eurorig.routing.PackageImportTest --rerun-tasks --console=plain
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: automatic-evidence=v1; definition-sha256=e9f29ed7053ee428a995217677047f92918d63baf4727eee390771d6847b7c08; exit=0; EXPECT=matched; output-sha256=8e2f43b04185b0129a458d49f483268e9914a89aaf8da7c9431a2bb311640023; output-bytes=1649; shell=C:\WINDOWS\system32\cmd.exe; cwd=C:\Users\Administrator\.codex\worktrees\eurorig-approved-interface\eurofig; path=16952c7954e5/29 entries

- [x] G2: the complete routing suite passes on the approved-interface branch
  CHECK: gradlew.bat :routing:test --rerun-tasks --console=plain
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: automatic-evidence=v1; definition-sha256=355e339aaed682461fd50e0416041e40e05ff7342602526f217216f8e9e862be; exit=0; EXPECT=matched; output-sha256=ab9d8c4522f344e36768e2db44ce8412d0eb121500437fe610c170500b471b91; output-bytes=1649; shell=C:\WINDOWS\system32\cmd.exe; cwd=C:\Users\Administrator\.codex\worktrees\eurorig-approved-interface\eurofig; path=16952c7954e5/29 entries

- [x] G3: the compiler and package tooling tests pass
  CHECK: ".toolchain/qa-python/Scripts/python.exe" -m unittest discover -s tools -p test_*.py
  EXPECT: /^OK\r?$/m
  EVIDENCE: automatic-evidence=v1; definition-sha256=3b8e21d44d96fd3edf77d3d9c46048d289e6b2d1f76f616976f4c9db3bd021ae; exit=0; EXPECT=matched; output-sha256=1e727f406b613adea07e6deef7d5bb2d63fbb4be1a56840c2e971354a268f790; output-bytes=525; shell=C:\WINDOWS\system32\cmd.exe; cwd=C:\Users\Administrator\.codex\worktrees\eurorig-approved-interface\eurofig; path=16952c7954e5/29 entries

- [x] G4: approved-interface app and instrumentation APKs build and Android lint passes
  CHECK: gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: automatic-evidence=v1; definition-sha256=74fdc06a0ae0fddf2d437895cd32b71057bb6e25c81c7b599719a0a6a32cb04b; exit=0; EXPECT=matched; output-sha256=b0e13e7767a7251624060736568a65bfefcf3569ff74c2f897836691160d01bb; output-bytes=6064; shell=C:\WINDOWS\system32\cmd.exe; cwd=C:\Users\Administrator\.codex\worktrees\eurorig-approved-interface\eurofig; path=16952c7954e5/29 entries

- [x] G5: the restored archive contains the original synthetic payloads and documented regeneration uses the existing packager
  EVIDENCE: Reviewed README regeneration command and ran tools/package_region.py on the checked-in profile assets. ZIP entries are routing.tar, display.sqlite and manifest.json; format=2, engine=valhalla, date=synthetic. Both payloads byte-match their original assets and manifest SHA-256 values: routing.tar=7d938dc8ca457a2d4e04478c43231aa92aca6c8e6cfd5fc843b4f480a27c0fd1, display.sqlite=90ffb6f6e0775c5c4b3b2f13e43a14c7e6036b70b7be3e79e54641f235bfb976. The 5600-byte fixture is visible to Git without changing ignore rules.
