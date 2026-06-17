# Lattice Unified — Phase 1 Notes

## What is built
- Single Gradle project at `android-unified/`
- namespace `com.lattice.unified`, applicationId `com.lattice.bridge`
  (preserves DJI API key validity)
- One launcher icon, one MainActivity
- BridgeService as ForegroundService with persistent notification — **stub**
  (no MSDK logic yet; returns BridgeState.STUB)
- Full Overlay UI (camera, sensors, GPS, SocketIO, projection, markers,
  drone-tap calibration, IP edit dialog, low-pass heading filter) preserved
- Bridge support files (Yolo.kt, Telemetry.kt, UdpSender.kt) copied with
  package rename to `com.lattice.unified.bridge.*`
- Overlay support files copied with package rename to
  `com.lattice.unified.overlay.*`

## What is NOT yet built (Phase 2)
- MSDK callbacks moved from old Bridge MainActivity into BridgeService
- USB accessory intent forwarding to service
- DJI frame grab (`DJICodecManager.getBitmap`) inside service (need to test
  whether surface-less grab works on Mavic 2 Zoom)
- YOLO inference loop driven from service
- UDP send wiring (uses existing UdpSender.kt from Bridge)
- BridgeState transitions wired to real MSDK callbacks

## To continue Phase 2
Send Claude:
- `android/app/src/main/java/com/lattice/bridge/MainActivity.kt` (full source)
- `android/app/src/main/java/com/lattice/bridge/LatticeApp.kt` (full source)
- Confirm the original Bridge still builds (so we have a working reference)

Claude will then write the MSDK refactor into BridgeService.
