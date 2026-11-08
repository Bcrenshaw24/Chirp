# Spatial Memory prototype

**Submission configuration:** AR prisms are hidden. Anchors, tags, selection, distance/direction, and speech remain active. `SHOW_SPATIAL_MARKERS = false` in `SpatialRenderer.kt` skips marker GPU creation/drawing; set it to true to restore them after submission. Earlier marker-alignment sections below document the deferred investigation, not the current visible UI.

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
8. Deny Camera, retry, and test App permission settings after permanent denial. With TalkBack and large text, verify the Tag Object button, object-name field, Find, Delete, and Speak controls. Find announces the selected location once; Speak Location repeats it on demand. Distance updates do not continuously speak.
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
7. Try the largest system font, TalkBack, gesture navigation, and three-button navigation. Ensure the bounded overlay and sheet content can scroll to every action, labels wrap, buttons remain reachable, and the center tag action works without tapping the preview. Find speaks once, and stable tracking loss/recovery is announced with debounce and cooldown.
8. Background / resume, leave / reopen, and verify the same lifecycle behavior documented above. Return to the main screen and check scan, haptics, and audio diagnostics.

The previous S23 Ultra implementation has been reported working for tracking, multiple tags, guidance, hallway return, temporary camera covering, and speech. The new layout, OpenGL markers, touch interception, and large-font behavior require this physical regression pass; desktop unit tests cannot validate their visual alignment or usability.

## Marker alignment investigation

The render-path audit confirms that each saved anchor supplies its own current world-space `Anchor.pose`. Neither the camera-relative guidance translation nor the center reticle is used as a marker position. Android Matrix and ARCore matrices are column-major; the shader receives `projection * view * anchorModel` with transpose=false. View, projection, camera texture UV conversion, and anchor poses are read after the same session update. Display geometry uses the GL surface's pixel dimensions and current display rotation; UV conversion handles camera cropping/orientation. The renderer does not create replacement anchors during drawing.

One concrete rendering hazard was redundant `setCameraTextureName` registration before every session update. ARCore explicitly says texture contents are not guaranteed after that call, and update can return an existing camera frame. The renderer now registers once per session / GL context, resetting registration when a new context creates a texture. This removes an avoidable camera-image invalidation path. It is a candidate explanation for apparent image/marker mismatch, not a physically confirmed root cause of the reported drift. No matrix, anchor, distance, mesh, audio, or lifecycle algorithm was changed. If drift persists, the logs below provide evidence for the next investigation rather than assuming the problem is solved.

### Tabletop alignment test and optional diagnostics

1. Install the rebuilt debug APK. Put two small pieces of tape on a textured, well-lit tabletop, about 30 cm apart. Slowly scan around it until tracking and a horizontal plane are ready.
2. Tag each tape location. Watch the **lower tip** of each diamond, which is the anchor origin. The diamond body extends 12 cm above the table and naturally has parallax relative to the tabletop; its center is not the contact point.
3. Move the phone 30–50 cm sideways in each direction while keeping the tape visible, then pan and tilt the phone without walking. Each lower tip should remain at its own tape mark, rather than move with the screen center. Repeat with one marker selected and after opening / closing Saved Objects.
4. Move closer / farther away, background / resume, and cover / uncover the camera. Markers should hide during tracking loss and recover at their saved locations. Small tracking refinements are possible; obvious systematic sliding during stable tracking means the defect is still present.
5. If sliding remains, enable targeted logs and capture them while repeating the sideways and rotation tests:

```bash
adb shell setprop log.tag.ChirpSpatial DEBUG
adb logcat -v threadtime 'ChirpSpatial:D' '*:S'
```

Logs are opt-in and limited to one set per second: frame timestamp, viewport pixels, display rotation (Surface enum 0–3), camera world translation/quaternion and tracking state, each saved anchor ID/world pose/state, and its projected origin in GL-view-local pixels. Projection uses the exact MVP just uploaded to the marker shader, with screen Y increasing downwards. Hidden anchors and GL errors are identified. Object names are not logged. World coordinates may refine together as ARCore updates its map; raw world-coordinate changes alone do not prove drift.

Disable these logs afterward:

```bash
adb shell setprop log.tag.ChirpSpatial INFO
```

Compare a screen recording with the projected-origin logs: the lower tip should occupy the reported pixel location while remaining over the tape in the image. This helps distinguish tracking changes, a render/projection mismatch, and parallax of the raised diamond body. Physical verification remains necessary even when desktop build/tests pass.

## Submission accessibility improvements

Find automatically speaks the saved object's name and current guidance once. The existing Speak Location buttons repeat guidance on demand. A Find request made while TTS initializes is retained until the engine is ready, and cleared on deselection, deletion, pause, or exit. No automatic distance-by-distance narration is added. Camera tracking loss and recovery must remain stable for 1.2 seconds before speech, with at least four seconds between tracking announcements. Initial successful tracking is silent. Visual tracking status remains immediate at the existing UI snapshot cadence, independent of speech debounce.

Spatial Memory reuses the existing DistanceHaptics vibrator access for finite bursts. The simulated scanner's waveform and intensity mapping are unchanged. Current proximity bands are:

| Distance | Burst | Start-to-start interval | Amplitude when supported |
| --- | --- | --- | --- |
| Above 2 m | One 30 ms pulse | 2200 ms | 55 |
| Above 0.5 m through 2 m | Two 30 ms pulses, 110 ms gap | 1200 ms | 65 |
| 0–0.5 m | Three 25 ms pulses, 75 ms gaps | 650 ms | 75 |

Different pulse counts distinguish bands even without amplitude control. Motor on-time stays below 15% in every band. Each waveform uses repeat=-1; the existing foreground coroutine schedules bursts. Invalid, missing, negative, or nonfinite distances disable pulses. Feedback requires both camera and selected-anchor tracking, a fresh snapshot, and a foreground session. Tracking loss, deletion/deselection, pause, and exit cancel pulses; renewed valid tracking can resume proximity feedback without repeating Find speech. No haptic waveform repeats in hardware, and no foreground service or wake lock is added. Speech is stopped on pause/exit and the TTS engine is released on destruction.

Proximity indicates straight-line distance, not an obstacle-free route. The camera overlay states this explicitly. AR marker drift remains deferred; no marker rendering or anchor pose calculation is changed by this accessibility work.

### Quick S23 Ultra accessibility check

1. Tag two objects, select Find on one, and verify one announcement contains its name, approximate meters, and direction. Wait while moving: speech must not repeat continuously. Speak Location should repeat current guidance; Find on the second object should switch both guidance and pulses.
2. Approach a selected anchor slowly and confirm short pulses become faster. Move farther away and confirm they slow down. Distance and direction are relative to the phone; check surroundings independently.
3. Cover the camera for at least two seconds: pulses should stop as soon as the next tracking snapshot arrives, visual status should show tracking loss, and one spoken warning should follow. Uncover and scan slowly: confirm visual recovery, resumed valid proximity pulses, and one recovery announcement. Rapid cover/uncover flickers should not produce speech chatter.
4. Delete the selected object and confirm vibration and any ongoing location speech stop. Background or lock the phone during guidance, then leave Spatial Memory: neither speech nor pulses should continue in the background. Resume requires a fresh tracked snapshot before proximity feedback restarts. Leaving/reopening still clears saved anchors.
5. With TalkBack, confirm Find, Speak Location, Delete, and Leave remain usable. With sound muted, verify visual status and distance remain readable. Return to the main screen to verify the original scanner/haptics and audio diagnostics still work.

Focused JVM tests cover invalid and bounded distance mapping, monotonic pulse timing, initial silence, stable tracking loss/recovery, transient flicker, notification cooldown, and reset behavior. Hardware vibration strength, TTS availability, and TalkBack interaction still require physical-device verification.

## Submission distance/placement audit and final regression check

No concrete distance-math regression was found. Comparing the original working renderer with the current version confirms the same center/tap hit test, upward-facing tracked plane acceptance, hit-created anchor, and `camera.displayOrientedPose.inverse().compose(anchor.pose)` guidance transform. Distance remains the Euclidean norm of that translation, including vertical separation. There is no 1.5 m clamp, offset, or near-distance floor. Reticle center and GL viewport center use the same full-size preview; direct taps are local GL-view pixel coordinates. The anchor is created at the hit before the naming dialog appears, and is not moved when the name is saved. These calculations and hit selection are unchanged.

The first eligible hit may belong to a floor or another horizontal surface if the intended tabletop is not detected at the target pixel. This is a placement possibility to test, not an established diagnosis of the S23 Ultra report. Holding the phone high above a saved surface also contributes to the full 3D distance. No height subtraction, distance scaling, anchor relocation, or speculative tracking correction has been added.

Physical checks for the final demo build:

1. Install the debug APK. Tag a tape mark on a well-lit, textured tabletop and confirm no AR prism appears. Find, Speak Location, and multiple saved-object controls should still work. The reticle remains visible.
2. Tag with the phone camera lens approximately 30–50 cm from the tape, ideally aimed nearly straight down. Immediately save and Find while holding that pose; compare the displayed distance with the measured lens-to-tape distance. Repeat with a tap on an exposed part of the preview. Avoid comparing horizontal walking distance with the reported 3D distance.
3. Move the camera lens nearer the same mark, keeping camera tracking valid and avoiding contact with the table. At approximately 20–40 cm separation, the value should approach that range if the saved anchor is on the intended tabletop. If it stays near 1.5 m, cancel/delete that tag, rescan around the table, and retag once. Do not force a calibration offset.
4. If placement is still wrong, enable the existing `ChirpSpatial` DEBUG logs (commands above). The added tag-time line records requested pixel coordinates, viewport, hit distance, hit world translation, and detected plane center. Subsequent logs retain camera/anchor world poses even while markers are disabled. Compare hit distance with measured separation at tagging; markers-disabled logs intentionally omit projected screen positions.
5. With a verified tag, test above 2 m (slow single), around 1 m (double), and below 0.5 m (fast triple). Allow up to one previous burst interval when crossing bands. Confirm rhythms are distinct but gentle on the S23 Ultra; intensity perception depends on hardware and system vibration settings.
6. Cover the camera during a burst. Tracking transitions now bypass the normal 350 ms UI snapshot throttle so cancellation reaches the foreground feedback handler on the next observed transition. Camera or selected-anchor tracking loss cancels the current finite burst and coroutine without waiting for speech debounce. Pause/lock, delete or deselect the target, and Leave must also cancel immediately. Resume feedback only with valid fresh tracking.
7. Confirm spoken Find, repeat speech, tracking announcements, original scanner haptics, audio capture, and acoustic diagnostics remain functional. Proximity never promises an obstacle-free route.

JVM tests cover invalid inputs, exact 0.5 m / 2 m band boundaries, distinct pulse counts, faster nearby intervals, bounded motor duty cycle, and the existing speech debounce/cooldown policy. Physical placement accuracy, band feel, and cancellation timing require the phone checks above. Occasional visual marker drift remains deferred.

## Live S23 Ultra placement investigation

A connected SM-S918U reproduction provided actual evidence of low-surface placement. In the existing installed build, a center tag at 09:11:28 used pixel `(540,1158)` in a `1080x2316` viewport and returned a 2.426 m hit at world Y `-1.317 m`. Adjacent camera samples were around Y `+0.09 m`, so the surface was about 1.4 m below the phone. Another reproduced center tag at 09:15:36 returned 1.820 m at Y `-1.335 m` with adjacent camera samples near `-0.110 m`, about 1.225 m above that surface. These earlier logs sample camera poses once a second, so those vertical comparisons are approximate, not exact same-frame measurements. Both are consistent with a floor-level plane rather than a physically close tabletop; ARCore Plane.Type itself does not semantically classify floor versus table.

Android's live view hierarchy showed the Compose host, AndroidView holder, and GLSurfaceView all at `(0,0)-(1080,2316)`, matching the renderer's viewport. Center requests used exactly half those dimensions. Insets were on the control overlay, not the GL view. No code-level viewport mismatch was established. Comparison against `b2eb765` confirms unchanged hit eligibility/order, hit-created anchor retention, and camera-relative 3D distance formula. Camera background UV mapping is also unchanged from that commit. No artificial distance offset, new plane bias, guessed floor rejection, or distance-formula change was applied.

Expanded opt-in `ChirpSpatial` diagnostics now record:

- UI request: reticle/touch source, view-local pixels, measured Android view size, window origin, actual surface buffer rectangle, display rotation and root system/cutout insets.
- Compose reticle's measured center in window coordinates. Subtract the request's view window origin and compare with requested local coordinates; this measures alignment rather than assuming it.
- GL request: actual viewport, rotation, frame timestamp, camera state/world pose and queue age.
- Every returned hit, including rejected candidates: plane/type, tracking state, polygon containment, hit distance/world XYZ, camera-minus-hit Y, plane identifier/center Y and extents. Selection uses the same first eligible hit as before.
- All currently detected tracked upward-facing planes and their heights, so a closer tabletop candidate can be distinguished from a scene containing only a low horizontal plane.
- Exact same-frame camera-versus-anchor vertical and horizontal separation and Euclidean distance at creation and naming/save. Subsequent selected-anchor comparisons are throttled to once a second.
- Projection of the selected hit back to VIEW pixels using the frame's camera matrices, compared with the requested VIEW pixels, plus camera texture UVs. This checks internal ray/display geometry consistency; it cannot identify the real object by itself.

Reproduce in a new session with diagnostic logging enabled:

```bash
adb shell setprop log.tag.ChirpSpatial DEBUG
adb logcat -v threadtime 'ChirpSpatial:D' '*:S'
```

Aim at a tape mark on a textured tabletop 30–50 cm from the camera lens. Slowly scan the table from several angles, then tag once with Tag Object and once by tapping the exposed camera preview. Record which input was used. The selected hit distance and camera-minus-hit Y should match the known lens-to-table geometry. If only a roughly 1.4 m-below-camera plane appears, the ray is hitting a low detected surface without a valid tabletop candidate. If the logs contain a closer tabletop hit rejected by type/state/polygon, inspect that rejection rather than changing the distance. If measured reticle coordinates or projected VIEW pixels disagree with the request, investigate that coordinate discrepancy. Compare the created and saved anchor logs to detect any movement during naming. Keep the phone steady for the first readout, then approach the same tape mark and select Find.

The new APK must be installed for the expanded diagnostics; installation/restarting clears current-session anchors. Existing UI, speech, haptics, audio/DSP, marker visibility setting, and placement/distance algorithms are preserved. A floor-selection interpretation remains an evidence-based hypothesis until candidate logs and known tabletop height establish it. Successful unit tests do not confirm correct physical plane placement.

### Diagnostic APK: same-frame center-tag evidence

The diagnostic APK was built, tested, and installed with permission on the connected S23 Ultra. At 09:20:06, the measured reticle window center, Android view-local request, actual GL viewport center, and selected hit's back-projection matched:

| Measurement | Result |
| --- | --- |
| Reticle center / requested VIEW pixel | `(540,1158)` |
| View origin in window | `(0,0)` |
| View dimensions / surface buffer / ARCore viewport | `1080x2316` |
| Rotation | `0` |
| Root insets (controls only) | top 94 px, bottom 42 px |
| Selected hit projected back to VIEW | `(540.0001,1158)` |
| Camera texture UV for center request | `(0.5,0.5)` |
| Request queue age | 15 ms |

Only one hit candidate was returned: an upward-facing Plane, TRACKING, inside its polygon. Camera world XYZ was `[0.11323531,-0.02158171,0.07627538]`; the selected hit and created anchor XYZ were `[0.57055163,-1.2016213,-1.3841835]`. The same-frame vertical separation was 1.180 m, horizontal separation 1.530 m, and computed Euclidean separation 1.933 m. ARCore's hit-distance field was 1.833 m; both raw values are retained in diagnostics without applying a correction. At save time the anchor remained on that low plane, with vertical separation 1.197 m. All three listed tracked horizontal Plane entries had the same Y of -1.2016 m; there was no distinct tabletop-height plane or closer hit to select.

This reproduction confirms internally consistent center mapping and an anchor far below the camera. It strongly supports selection of a floor-level detected surface when the user intends a nearby object. It does not prove a specific physical plane's semantic identity or why the tabletop was not acquired. No closer tabletop hit was present and wrongly skipped in this event. Consequently no selection heuristic or distance correction is justified by this evidence; the expanded logs remain available to capture a preview-tap comparison after scanning the tabletop.

## Submission rollback: unrestricted tagging restored

The experimental nearby-distance guard and Nearby / Floor-longer-range selector have been removed. Tag Object and preview taps again select the first tracked upward-facing horizontal plane whose polygon contains the hit, with no maximum placement distance, matching the original `b2eb765` eligibility rules. A valid hit immediately creates the pending anchor and opens the existing naming dialog; absent hits and unavailable tracking retain their original feedback. Guard-only rejection speech, state, policy code, and tests were removed.

Camera-first layout, hidden prisms, anchors, pose/distance calculations, Find/Speak, proximity haptics, tracking announcements, and audio/DSP remain unchanged. The previous floor-hit accuracy limitation remains; this rollback restores usability rather than claiming to resolve surface selection.

On the S23 Ultra, verify both center tagging and preview taps open the naming dialog for a valid detected plane even beyond 1 m, save multiple objects, and confirm Find/Speak, haptics, and tracking-loss/recovery still work. There should be no Placement mode selector or nearby-limit warning. Desktop build/tests verify compilation and existing feedback policies; physical tagging still needs phone confirmation.
