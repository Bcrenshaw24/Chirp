# Spatial Memory prototype

Spatial Memory is an optional ARCore mode. The existing simulated scanner, audio diagnostics, and DSP code remain independent. The only new library is `com.google.ar:core:1.48.0`.

`SpatialMemoryActivity` owns camera permission, ARCore availability / installation, session lifecycle, Compose controls, and optional Android text-to-speech. `SpatialRenderer` updates ARCore and owns all hit tests and anchors on the GL thread. `CameraBackground` draws the ARCore external camera texture; no separate CameraX camera or scene framework is used. Guidance uses anchor translation relative to the display-oriented camera pose, with Euclidean distance in meters and coarse horizontal / vertical direction.

Only upward-facing horizontal planes accept hits. These are manually tagged surface locations, not recognized objects. No mesh, cloud anchors, GPS, or persistent mapping is used. There is no virtual marker overlay: the preview, surface status, and saved-object list provide feedback.

Anchors stay in memory within this activity's session. Backgrounding pauses rendering before pausing ARCore and suppresses location guidance; resuming uses the existing session and requires tracking again. Leaving the activity, activity recreation (including rotation), process death, or restarting clears all objects. Deleting or cancelling detaches the corresponding anchor; destruction detaches all remaining anchors and closes the session. Do not promise persistence or accuracy after interruptions. ARCore drift and loss of tracking are expected limitations.

## Physical Samsung Galaxy S23 Ultra test

1. On Arch Linux, enable USB debugging on the phone, connect it, accept the device authorization prompt, and confirm `adb devices` lists it as `device`.
2. Build with `./gradlew assembleDebug testDebugUnitTest`. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. Open Chirp and confirm the simulated scan and haptics still work. Stop it, then open Spatial Memory. Tap **Start / retry camera and AR**, grant Camera, and accept any Google Play Services for AR installation/update prompt. If support checking is still pending, retry after a moment.
4. In a well-lit room, slowly move the camera sideways around a textured table or counter until the status reports a horizontal surface. Aim at the surface under an object and tap the preview, or use **Save location at camera center**. Enter “House keys” and save. Repeat for another object. Cancel a third naming dialog to check cancellation.
5. Keep Spatial Memory open and walk to another location. Select **Find House keys**. Check that distance changes plausibly and direction changes as you rotate the phone. The guidance refers to the phone, not your body. Tap **Speak location**; this requires an installed TTS engine and language voice.
6. Cover the camera or point it at a blank wall. Confirm tracking-unavailable feedback replaces distance guidance. Uncover and slowly return toward the saved area to reacquire tracking. Check that an unavailable individual anchor never gives a stale distance.
7. Delete a saved object and confirm it disappears. Background and resume the activity; verify camera release/resume and tracking feedback. Leave Spatial Memory and reopen; the list must be empty. Rotate the device to verify a fresh session rather than persistent anchors.
8. Deny Camera, retry, and test App permission settings after permanent denial. With TalkBack and large text, verify the center-save button, object-name field, Find, Delete, and Speak controls. Speech is on demand to avoid repeated announcements of changing distances.
9. Return to the main screen and run a simulated scan and an audio diagnostic test to check the existing workflows on hardware.

Desktop unit tests cover direction axes and 3D distance math, alongside existing DSP/audio tests. Camera permission prompts, Play Services installation, plane acquisition, real-world distances, lifecycle recovery, TTS, and TalkBack need physical-device validation. This prototype is not obstacle avoidance or a safe navigation system.
