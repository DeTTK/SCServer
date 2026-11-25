---
title: "Native launcher updates"
---

# Native launcher updates

The launtest launcher checks this repository's GitHub Releases at startup, separately from game delivery. Successful checks are cached for one hour. Only `launcher-vX.Y.Z-launtest` releases with an exact `BSCPCRAFT.exe` asset, a GitHub SHA-256 digest and an approved repository download URL qualify. Game releases and old Setup executables are ignored.

The executable is downloaded beside the portable profile, verified, and queued for the next launcher start. The interface shows the pending version. A temporary copy of the same executable performs replacement after the original exits, retains `BSCPCRAFT.previous.exe`, then starts the updated executable at the original path with the original `--root`. A running game/server defers replacement. A busy executable is retained for a later attempt and the old launcher starts again.

`Publish native launcher` runs after successful `Source checks` on launtest. It builds and checks the standalone Windows executable and publishes a prerelease. Bump the workspace version and the three launcher entries in Cargo.lock for a new launcher release; an existing version is never overwritten. Game delivery continues through build-launtest.

The transfer card keeps the white network bar separate from the thin disk bar. Disk phases include cache checking, file hashing, writing, unpacking and applying files. Known byte counts have a measured fraction; unknown work displays its stage without an invented percentage. After two seconds without a response, the network stage shows a waiting timer. Interrupted bodies resume using HTTP Range. Failed attempts retain partial files; retries stop after four attempts without progress or twenty minutes for an object, and cancellation is checked between reads and retries.
