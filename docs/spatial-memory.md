# Spatial Memory prototype

Spatial Memory is an optional ARCore mode. The existing simulated scanner, audio diagnostics, and DSP code remain independent. The only new library is `com.google.ar:core:1.48.0`.

`SpatialMemoryActivity` owns camera permission, ARCore availability / installation, session lifecycle, and optional Android text-to-speech. `SpatialMemoryScreen` owns the Compose overlays, naming dialog, and Saved Objects modal bottom sheet. `SpatialRenderer` updates ARCore and owns all hit tests and anchors on the GL thread. `CameraBackground` draws the ARCore external camera texture; no separate CameraX camera or scene framework is used. Guidance uses anchor translation relative to the display-oriented camera pose, with Euclidean distance in meters and coarse horizontal / vertical direction.

Only upward-facing horizontal planes accept hits. These are manually tagged surface locations, not recognized objects. No mesh, cloud anchors, GPS, or persistent mapping is used. Each tracked saved anchor has a cyan diamond marker, with its lower tip at the anchor pose. Selecting Find highlights that anchor with a larger yellow diamond. These markers are virtual indicators, not recognized objects.

Anchors stay in memory within this activity's session. Backgrounding pauses rendering before pausing ARCore and suppresses location guidance; resuming uses the existing session and requires tracking again. Leaving the activity, activity recreation (including rotation), process death, or restarting clears all objects. Deleting or cancelling detaches the corresponding anchor; destruction detaches all remaining anchors and closes the session. Do not promise persistence or accuracy after interruptions. ARCore drift and loss of tracking are expected limitations.

## Physical Samsung Galaxy S23 Ultra test

1. On Arch Linux, enable USB debugging on the phone, connect it, accept the device authorization prompt, and confirm `adb devices` lists it as `device`.
2. Build with `./gradlew assembleDebug testDebugUnitTest`. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. Open Chirp and confirm the simulated scan and haptics still work. Stop it, then open Spatial Memory. Tap **Start / retry camera and AR**, grant Camera, and accept any Google Play Services for AR installation/update prompt. If support checking is still pending, retry after a moment.
4. In a well-lit room, slowly move the camera sideways around a textured table or counter until the status reports a horizontal surface. Aim at the surface under an object and tap the preview, or use **Tag Object**. Enter “House keys” and save. Repeat for another object. Cancel a third naming dialog to check cancellation.
5. Keep Spatial Memory open and walk to another location. Open **Saved Objects** and select **Find House keys**. Check that distance changes plausibly and direction changes as you rotate the phone. The guidance refers to the phone, not your body. Tap **Speak Location**; this requires an installed TTS engine and language voice.
6. Cover the camera or point it at a blank wall. Confirm tracking-unavailable feedback replaces distance guidance. Uncover and slowly return toward the saved area to reacquire tracking. Check that an unavailable individual anchor never gives a stale distance.
7. Delete a saved object from **Saved Objects** and confirm both its list entry and marker disappear. Background and resume the activity; verify camera release/resume and tracking feedback. Leave Spatial Memory and reopen; the list must be empty. Rotate the device to verify a fresh session rather than persistent anchors.
8. Deny Camera, retry, and test App permission settings after permanent denial. With TalkBack and large text, verify the Tag Object button, object-name field, Find, Delete, and Speak controls. Speech is on demand to avoid repeated announcements of changing distances.
9. Return to the main screen and run a simulated scan and an audio diagnostic test to check the existing workflows on hardware.

Desktop unit tests cover direction axes and 3D distance math, alongside existing DSP/audio tests. Camera permission prompts, Play Services installation, plane acquisition, real-world distances, lifecycle recovery, TTS, and TalkBack need physical-device validation. This prototype is not obstacle avoidance or a safe navigation system.

## Camera-first layout and markers

The full-size `GLSurfaceView` is composed once behind the controls. The top status / selected-object card and bottom Tag Object / Saved Objects controls respect safe drawing insets; their height is bounded and their content scrolls for large fonts and landscape. The camera remains visible behind them. The object sheet is scrollable with Find, Delete, Speak Location, and Close controls. Opening it does not recreate the GL view or pause the AR session. Find closes the sheet and leaves the selected object's guidance in the top overlay. The center ring is decorative and excluded from TalkBack traversal; Tag Object provides a labeled center-hit-test action.

Touch coordinates remain local to the same GL view passed to ARCore display geometry. Center tagging uses half its width and height, exactly matching the ring's position in the full-screen preview. Insets apply to controls, not the camera or reticle. Compose controls and the modal sheet intercept their own touches; only exposed camera areas should create preview hit tests.

`AnchorMarkerRenderer` adds a small OpenGL ES 2.0 diamond mesh without a rendering framework or new dependency. Each frame it computes `projection * view * anchorModel` using ARCore's camera matrices and the current anchor pose. It does not use the guidance coordinate calculation for rendering and does not apply another display rotation. Drawing occurs every camera frame before the throttled UI snapshot publication, so markers follow motion smoothly. Both camera and individual anchor must be TRACKING to draw. Selection is sent through the existing GL command queue; no anchor is recreated or moved to highlight it.

The depth buffer clears each frame. The camera background draws without depth writes, then markers draw with depth testing so virtual markers correctly overlap each other. There is no real-world depth occlusion: a marker can appear through an intervening physical object. Normal markers are 12 cm high; selection scales the mesh by 1.4 around its anchored lower tip. Perspective controls the apparent size. These indicators do not persist outside the existing activity session.

### S23 Ultra regression checklist for this UI update

1. Install the debug APK as above. Confirm the camera fills the screen, the ring is centered, and status / buttons clear the status bar, camera cutout, and navigation bar in portrait and landscape. Rotation still starts a fresh session as before.
2. Tag an object using the ring and **Tag Object**, then tag a second by tapping an exposed off-center part of the preview. Verify the diamonds' lower tips align with the tagged surface locations. Cancel another naming dialog and confirm no saved marker appears.
3. Walk sideways, closer, farther away, and around each object. Check marker perspective and attachment to the physical location. Open / close **Saved Objects** repeatedly: neither camera framing nor marker positions should jump because of the sheet.
4. Use Find on each object. Check that the sheet closes, the correct diamond becomes larger and yellow, the previous one returns to cyan, and the top overlay shows the selected name and current guidance. Verify Speak Location from both the overlay and the sheet.
5. Cover the camera, then recover tracking; markers must disappear during tracking loss and reappear after recovery. Repeat the hallway-and-return test. Individual nontracking anchors must also be hidden. Delete the selected object and another object: their markers and entries must disappear without affecting remaining anchors.
6. Tap buttons, scroll the status card / sheet, and dismiss the sheet by dragging or Back. Confirm these interactions do not accidentally open a naming dialog or create another anchor. An ordinary Back with no sheet still leaves the activity as before.
7. Try the largest system font, TalkBack, gesture navigation, and three-button navigation. Ensure the bounded overlay and sheet content can scroll to every action, labels wrap, buttons remain reachable, and the center tag action works without tapping the preview. No automatic speech beyond the existing status announcements is introduced.
8. Background / resume, leave / reopen, and verify the same lifecycle behavior documented above. Return to the main screen and check scan, haptics, and audio diagnostics.

The previous S23 Ultra implementation has been reported working for tracking, multiple tags, guidance, hallway return, temporary camera covering, and speech. The new layout, OpenGL markers, touch interception, and large-font behavior require this physical regression pass; desktop unit tests cannot validate their visual alignment or usability.
