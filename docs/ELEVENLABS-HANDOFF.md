# ElevenLabs speech slice — implementation handoff

## Current resume point — human phone feedback

Read this section first; historical pending/unverified statements below are superseded only for the checks explicitly confirmed here.

- Martin installed the batch v4 test, confirmed real speech input -> ElevenLabs transcription -> existing Hermes agent -> chosen ElevenLabs voice, and confirmed automatic microphone re-arm for a second turn.
- Martin installed `hermes-vox-elevenlabs-stream1.apk` (versionCode 145 / `0.9.0.1-stream1`) and reported playback was much smoother. This is human confirmation of the streaming update's usefulness, not exhaustive device acceptance.
- OPEN BUG: sometimes audio cuts off after only 3–4 words although the text reply is complete. Martin thinks the app may return to recording but is unsure. No root cause established; debug session deferred. Inspect stream EOF/error/cancellation and speaking/turn-gate transitions. Voice-triggered barge-in is explicitly bypassed while ElevenLabs playback is marked speaking; a premature speaking-state transition remains only a hypothesis. Do not blame VAD without logs.
- Still pending: deliberate manual Stop/cancellation checks, long multi-chunk continuity, locked-screen/background/Bluetooth route acceptance and real error recovery. Speech barge-in remains disabled; no claim of driving-ready full duplex.
- Wake-word preview is enrolment only: local recordings, inert detector, no working wake activation. It is independent of selected ElevenLabs STT/TTS.
- Current user configuration: TTS voice `h2dQOVyUfIDqY2whPOMo`, model `eleven_v4`; batch STT `scribe_v2`. Entity endpoint previously supplied: `https://pc-94.tailfd817.ts.net:8446/p/loveleiviolet`, Model `loveleiviolet`, Entity scope `agent:vox:phone:martin`. Do not include `/v1` in the endpoint. Route is historically supplied, not a fresh network check. No credentials in this document.
- Source branch `feature/elevenlabs-voice`, upstream baseline `v0.9.0.1`. Martin authorized a combined local checkpoint of the current ElevenLabs implementation. Git identity is now `Violet (Hermes agent) <violet@hermes.invalid>` in the VM root user's global Git config. Offline suites were rerun successfully before checkpointing (protocol 48, chunking 1562, PCM 24, drain 7); no new Android build or device/provider test was run for this checkpoint. Verify live Git state/HEAD before continuation. No push performed.
- Host toolchain: standalone JDK `/home/martin/.local/share/jdks/temurin-17`, SDK `/home/martin/Android/Sdk`; default system Java remains 27 at last verification. Launcher is project `03_code/build-vox-test.sh`. Read scripts before execution; build requires hostd, not the VM's SDK. No paid calls needed for ordinary build/tests; phone tests consume ElevenLabs quota.
- Project VM clone: `/mnt/10-19_projects/11_internal_projects/11.16_hermes/03_code/hermes-vox`; host clone: `/home/martin/jdex/10-19_projects/11_internal_projects/11.16_hermes/03_code/hermes-vox`. Phone-share APK: `/mnt/phone-share/hermes-vox-elevenlabs-stream1.apk`. Preserve existing working settings/key and debug signing key on future updates. Source session: current Matrix room `!FAOsA1dp5_Jh46VfysC-nxbgJJmNxDNJBAJ8bqx_sXI`, thread `$wJGzkHLOmAZNS_HEwRcS4FQT7rbxafJtLVi9zVaSg28`.

## Independent microphone mute increment — build and initial phone acceptance

- Preserved on local branch `feature/microphone-mute`, stacked on `feature/elevenlabs-voice`; the ElevenLabs branch remains at its original checkpoint. This permits a separate mute PR based on the ElevenLabs branch (or after that branch merges). No push or PR created.
- Delivered checksum-verified APK to `/mnt/phone-share/hermes-vox-elevenlabs-mute1.apk`, owned by `1000:1000`. Martin reports it seems to be working on his phone. This is initial human acceptance, not explicit verification of every capture race, route/focus/rotation case or background behavior. Known intermittent speech cutoff remains deferred.
- Based on clean checkpoint `61796b66801028d17a152639f3e8d7f66796ec22`; no commit, push, Gradle invocation, dependency download, paid call or phone install by the implementation worker.
- Test version is **146 / `0.9.0.1-mute1`**. Parent reran all six offline suites (48 protocol, 1562 chunking, 24 PCM, 7 drain, 40 microphone, 15 rearm checks); separate follow-up source review found no remaining blocker in the two corrected paths. Host canonical Android build/unit/parity gate returned **GATE-GREEN** over 10 fixtures, and APK signature/version verification passed. APK `android/app/build/outputs/apk/debug/hermes-vox-0.9.0.1-mute1.apk`, 179664425 bytes, SHA-256 `e35a18f9bc70afe0c7d08061acf96ba359ea215ec072eab108701ff3db4f3f2c`; debug certificate SHA-256 `fdf4a5d16e3d5899305b4ed4891c701bb858cc9cfd42d62be5203ccff7cd03f8`. Evidence: `/home/martin/.cache/vox-test-build/mute-async.w2yUUX/{build.log,exit-code}`. Completion arrived through one background hostd request with explicit 1200-second command deadline and automatic parent notification; no status polling. At build completion, device behavior was unverified and the assistant had performed no phone installation or paid call; subsequent initial phone feedback is recorded above.
- A dedicated 48dp-high **Mute mic / Unmute mic** button is visible throughout a live call, independent of listening/thinking/speaking and the existing Stop/hang-up controls. Its accessibility description states microphone status and action; status is not color-only.
- Mute closes/releases the owned `AudioRecord` and optional noise suppressor, or cancels/destroys platform `SpeechRecognizer`. It does not call `stop`, `hush`, `silenceAll`, session cancellation, TTS stop, response disconnect or turn-gate release. An already admitted voice turn continues; an utterance only queued on the UI handler is discarded if mute wins admission. No new request may be admitted from pre-mute results after unmute.
- Production `speech/MicrophoneGate.java` serializes capture ownership and result admission with a capture-only epoch. Final/partial STT, queued turn handoffs and barge decisions/verdicts carry that epoch. New capture discards old segments, pre-roll/carry and partial history; stable-partial state is per utterance. Pending STT may finish (and an already uploaded cloud request may still cost quota), but cannot submit old text. An outstanding synchronous STT decode can delay reopening clean input until it returns; it is not forcefully shut down while native decode is in flight.
- The call mute latch lives beside the app-scoped controller, is applied before every open/resume, and persists across route/controller rebuild and focus pause/gain. Only an explicitly started **new call resets unmuted**. Playback completion cannot clear it. Existing focus loss and route-change policy can still cancel playback/agent work on their own; mute itself does not change those policies.
- Explicit unmute restores fresh capture. For offline/remote/batch legs, unmute during a response restores fresh barge-drain capture without opening the next-turn gate; existing per-backend barge rules remain, including ElevenLabs' playback bypass. Platform recognition resumes only when the current response/output completes, preserving its half-duplex path.
- Review follow-up: partial, whole/final and semantic-barge STT now enter the production `MicrophoneGate.invoke` admission seam at invocation time, not only after decoding. Admission and mute serialize against the capture epoch; admission is the invocation's linearization/start point. A worker not yet admitted when mute wins never calls the backend, including after unmute. An invocation admitted first is explicitly in flight and may finish across mute; its blocking supplier runs outside the ownership monitor so hardware release never waits for network/decoding. Its returned text still needs the existing result/agent fence. This is not transport abort or a guarantee of zero post-mute bytes from already admitted work.
- Platform rearm now retries on **both** gate release and the worker's actual busy->idle transition. The shared production `PlatformRearm` main-queue seam checks capture epoch/mute; its controller eligibility also checks controller stopped/listening/focus, response/speech ownership, generation **and voice gate epoch** (platform turns can reuse `turnGen`). Multiple queued retries collapse when the first fresh `listen()` invalidates the capture epoch. No polling, turn cancellation or output changes were introduced.
- Executed `bash scripts/test-elevenlabs-offline.sh`: protocol **48**, chunking **1562**, PCM **24**, drain **7**, microphone gate **40**, platform rearm **15** checks. Follow-up RED evidence: missing `invoke` API and missing `PlatformRearm` class, then an executable assertion failure `production worker finally must retry platform rearm after clearing busy` before adding the controller retry. Invocation regressions count real injected backend invocations at the production seam (stale partial/final/barge are zero); latch-controlled admitted STT stays blocked while mute closes capture. Rearm tests execute the production posted callback with error/empty release-before-idle, opposite/duplicate queue order, still-owned reply gate, mute/unmute, controller retirement and stale turn eligibility. Source-wiring assertions additionally guard the Kotlin call sites/retry; these are static checks, **not Kotlin/Android execution**. Existing late-result and production PCM-continuation tests remain. Tests do **not** execute Android `VoiceController`, SpeechRecognizer/AudioRecord, Hermes transport or UI; active-agent independence remains source-inspected.
- `bash -n scripts/test-elevenlabs-offline.sh` and `git diff --check` passed. No Kotlin compiler available on worker PATH. No host build, Gradle, hostd, paid call, commit or push was performed for this follow-up; an earlier parent build/result gate was not accessed or bypassed. Parent must compile and exercise the real adapters; portable seams are not full Android acceptance.
- Device acceptance: mute mid-utterance (no request), mute during thinking (agent/text/audio continue), mute during speech (audible tail continues), wait through completion (mic remains closed), explicitly unmute and speak new words (only new words submitted), rapid mute/unmute around slow STT, platform STT, rotation/background, focus and headset-route rebuild while muted. Check both visual/TalkBack action/status and actual Android mic indicator/resource release. Existing voice modes/backends remain selectable.
- **Known 3–4-word intermittent audio cutoff remains deferred and unchanged in scope.** This increment neither diagnoses nor claims to fix it.

## HTTP PCM streaming checkpoint (built; initial phone feedback received)

Parent reran all four offline suites successfully (protocol 48, chunking 1562, PCM 24, drain 7 checks). Host canonical full Android build/unit-test/parity gate returned `GATE-GREEN` over 10 stream fixtures; signature verified and matches prior debug certificate. APK SHA-256 `119d8cfa95cf7e043815df7731ff8033bc72783b77bf01360879c28b549459bb`, 179646325 bytes. Delivered to `/mnt/phone-share/hermes-vox-elevenlabs-stream1.apk` with matching checksum. Build log `/home/martin/.cache/vox-test-build/stream1.log`. Supersedes source-only/build-pending wording below. Actual phone streaming, audio continuity, Stop and second-turn tests remain pending; no paid synthesis call performed.

- Baseline working batch source and dirty tree were preserved on `feature/elevenlabs-voice`.
  The user has since confirmed batch speech and the second turn on their phone; the
  older "no phone validation" entries below are historical batch checkpoints.
- `eleven_v4` and `eleven_v4_turbo` now use **POST
  `/v1/text-to-dialogue/stream?output_format=pcm_24000`** with the exact selected
  voice/model. Other models keep the existing MP3 batch path, not an automatic fallback.
- The complete Hermes reply reaches `speak` first (`supportsStreaming=false` intentionally:
  VoxTts uses that flag for incremental *text*). `SpeechChunks` splits losslessly into
  <=2000 UTF-16-unit requests, preferring sentence then word boundaries, otherwise whole
  code points. Bracketed speech cues remain atomic. No 1000-character minimum, trimming,
  voice substitution, retries, or downgrade. Invalid Unicode/unbalanced or oversize atomic
  cues and text that cannot produce nonblank lossless chunks fail before any request.
- One `AudioTrack` (24kHz mono PCM16, MODE_STREAM) spans the entire reply. A producer
  starts feeding PCM before response EOF; one consumer serializes output. A two-slot
  request window permits only current + next chunk; queue cap is 16 x 4096 bytes
  (65536 bytes), plus one producer/consumer packet, HTTP/device buffers. Backpressure
  blocks reads instead of accumulating full audio. Inter-read odd bytes are carried;
  empty or odd-length completed PCM is rejected, never padded with fake audio samples.
- Completion waits for the unsigned playback head to reach all submitted frames; no
  premature stop/flush. Only very short *whole replies* are silence-primed to the actual
  AudioTrack buffer capacity at EOF to meet Android's playback start threshold.
- Stop epoch-fences new writes/completions, immediately pauses/flushes output, wakes queue
  waiters, interrupts the producer, and aborts HTTP/stream close off the UI thread.
  The playback worker alone releases its track. On failure it cancels output/network;
  completion is settled once by the existing main-thread epoch logic. No paid retry.
- Streaming policy: 10s connect, 30s idle read, 10s output-write/drain stall, 20min total
  reply watchdog including playback/backpressure, <=28,800,000 PCM bytes per request
  (10min at 24kHz mono16). Redirects disabled; unexpected non-PCM Content-Type rejected;
  no response bodies/text/audio/keys logged. No credential access/provider calls performed.
- STT, stored settings keys, session/response identity and Hermes transport are unchanged.
  Voice barge-in remains disabled during ElevenLabs reply/glue playback; manual Stop stays.
- Test update version: **versionCode 145 / versionName `0.9.0.1-stream1`**. Application ID
  and signing config/key unchanged. Parent should use the existing host launcher/full
  canonical `scripts/gate.sh`, verify signing + APK version, then copy/read back for delivery.
  Expected APK: `android/app/build/outputs/apk/debug/hermes-vox-0.9.0.1-stream1.apk`.

### Files touched in this streaming increment

- Added `android/app/src/main/java/com/hermesvox/ElevenLabsPcmPlayback.kt`.
- Added production portable `speech/{SpeechChunks,PcmStream,PcmDrain}.java` and
  `tools/elevenlabs-test/{SpeechChunksTest,PcmStreamTest,PcmDrainTest}.java`.
- Extended existing `speech/ElevenLabsProtocol.java`, `ElevenLabsProtocolTest.java`,
  `scripts/test-elevenlabs-offline.sh`, `ElevenLabsSpeech.kt`.
- Updated `SettingsActivity.kt` explanatory labels, one `VoiceController.kt` comment,
  `android/app/build.gradle` test version, and this document. Prior MainActivity/WarmTts
  dirty changes are retained untouched.

### Verification and remaining evidence

Tests were written/run RED before each new portable production slice: stream route,
lossless splitting, PCM producer/consumer, nonblocking Stop, late-open cleanup, drain and
short-output priming. Executed real production Java with `javac --release 17 -Xlint:all
-Werror` and `java`; no stand-in implementation. Runner also adds seeded lossless fuzz
cases and exercises prefetch bound, bounded queue, odd reads, ordered output, empty/odd
PCM failure, before-EOF consumption, blocked-read cancellation and hardware-head drain.
Final test counts are recorded by the runner, not this historical log.

**Android/Kotlin compilation and real AudioTrack/HTTP/device execution remain unverified
for this increment; no full host build was launched by the editing subagent.** No Android
adapter runtime tests/dependencies were added; portable fakes exercise the actual Java
pipeline but not Android APIs. Source review covered ownership, Stop races, endpoint,
format, config preservation and failure paths. Parent must run the full build gate before
shipping. Device/provider validation later must check first audio before EOF, long >2000
reply boundaries, gap/underrun behavior, tail drain, Stop in network/write/drain, replacement,
and second turn, including Bluetooth. Prefetch reduces latency but cannot guarantee
zero audible gaps if provider generation/network falls behind playback. Playback head
is the Android completion reference, not calibrated acoustic/route latency evidence.
Long replies retain the existing activity-based turn gate (`speaking` keeps it alive);
completion is not released until the PCM drain returns.

Official contract re-read: https://elevenlabs.io/docs/api-reference/text-to-dialogue/stream
(2000-character total input reliability limit, PCM24000 output, explicit model/voice).


## Latest v4 correction

Models `eleven_v4` and `eleven_v4_turbo` now use `/v1/text-to-dialogue` with `inputs: [{text, voice_id}]` and the selected `model_id`; other models retain the text-to-speech route. v4 requests reject text beyond 2000 characters rather than truncating/downgrading (automatic splitting remains outstanding). Regression test first failed on the old endpoint, then 39 offline checks passed. Host full debug build/unit-test/10-fixture parity gate returned GATE-GREEN; APK signature verified. New APK SHA-256: `c5a53432990420b1e65a598f0ab225d78ad4ccd8b36d49f5b9c23af7577d4a16`. Copied and checksum-verified at `/mnt/phone-share/hermes-vox-elevenlabs-v4-fix-0.9.0.1.apk`; this supersedes the earlier test APK. No paid provider request or phone validation performed. Host build log: `/home/martin/.cache/vox-test-build/v4-fix.log`.

## Status / baseline

**Updated build checkpoint: Android debug APK compiled successfully on the physical host; canonical `scripts/gate.sh` returned `GATE-GREEN`, including unit tests and stream parity over 10 fixtures. No phone/provider/audio validation yet.**

Host build used Temurin 17.0.21 from `/home/martin/.local/share/jdks/temurin-17` and SDK `/home/martin/Android/Sdk`. Gradle automatically installed SDK Platform-Tools 37.0.1. Build emitted a nonfatal SDK XML version warning. APK: `android/app/build/outputs/apk/debug/hermes-vox-0.9.0.1.apk`, 179646309 bytes; SHA-256 `fdacab057a1694071d830d997365aad1a0edea06ac80ab8e66b59020865642f5`. `apksigner verify --print-certs` passed with Android Debug certificate. This uses the upstream application ID, so it cannot update the upstream release-signed install; do not uninstall that app or install this APK without a separate decision. Host log: `/home/martin/.cache/vox-test-build/build.log`, exit-code 0. `git diff --check` passed. Source changes remain uncommitted.

The initial implementation record below is historical: statements that Kotlin was uncompiled or no APK existed are superseded by this checkpoint. Runtime/device limitations remain in force.

- Repository: `hermes-vox` under project `03_code`.
- Branch: `feature/elevenlabs-voice`.
- Baseline: upstream `v0.9.0.1`, `49ab1e85ba0e90dcf3c0894f37a3981cc63508f7` (initially clean).
- JDK: OpenJDK / javac `21.0.12.1`; portable tests target Java 17, matching app compile options.
- `kotlinc`, `kotlin`, `adb`, and `sdkmanager` absent on PATH. No Android SDK/toolchain installs, Gradle invocation, dependencies/runtime/model downloads, device installation, provider calls, credential reads, production changes, push, or GitHub fork performed.
- No Git author configured (`git config user.name` / `user.email` unset); no commit created or global configuration changed.
- Ownership restored recursively within this clone to project owner UID:GID `1000:1000`. Subsequent root Git reads need per-command `git -c safe.directory=/mnt/10-19_projects/11_internal_projects/11.16_hermes/03_code/hermes-vox ...`; no global safe-directory/config change made. Final diff check and status were re-run successfully with that override.
- Existing source identity/license, application ID, version, signing configuration, agent transport and session chain are unchanged.

## Exact source changes

Added:

- `android/app/src/main/java/com/hermesvox/speech/ElevenLabsProtocol.java`: dependency-free production request/config helper used directly from Kotlin. Batch STT `POST https://api.elevenlabs.io/v1/speech-to-text`, multipart `model_id`, `tag_audio_events=false`, WAV file; TTS `POST /v1/text-to-speech/{voice_id}?output_format=mp3_44100_128` with escaped JSON `text` and explicit `model_id`. Uses `xi-api-key`, not Bearer. Fixed provider host; no configurable credential destination. Guards header/path/multipart inputs and bounds audio upload to 1 MiB and reply text to 5000 UTF-16 code units. Invalid/oversize input is rejected, never truncated or silently assigned another model. A fail-closed storage function permits encryption failure tests independent of Android.
- `android/app/src/main/java/com/hermesvox/ElevenLabsSpeech.kt`: separate opt-in batch VoxStt/VoxTts adapters plus private preference configuration. Uses existing WAV encoder and Android top-level JSON parser. Network requests disable redirects, use 10s connect / 60s per-read timeouts, bound responses (STT 1 MiB, TTS 10 MiB), and do not retry. TTS buffers complete MP3 then plays with MediaPlayer; completion/error releases playback and temporary file. Epoch fencing is intended to suppress late audio/completions after Stop or replacement. Shutdown disconnects owned network request. Availability/init is local configuration validation only, NOT API/auth/model/voice validation. No `/models` probe or paid initialization request.
- `tools/elevenlabs-test/ElevenLabsProtocolTest.java`: executable Java tests of that exact production Java, not a surrogate implementation.
- `scripts/test-elevenlabs-offline.sh`: javac/java runner with no dependencies/network/Gradle. Compiles to `$TMPDIR`, cleans class output on exit. Invoke with `bash`.

Modified:

- `SettingsActivity.kt`: experimental ElevenLabs entries in both speech pickers; shared configuration dialog reopened by re-selecting either option. STT defaults to `scribe_v2`; voice ID and TTS model are intentionally blank and must be explicitly supplied. Exact model text is preserved; no faster-model substitution. Separate encrypted key preference uses existing Android Keystore SecureStore, with **no plaintext-on-encryption-error fallback** and no legacy plaintext reads. Blank key preserves existing key; Forget key deletes it. New key field is masked, no autofill, and dialog uses FLAG_SECURE. No automatic cloud voice preview, including delivery-control changes. Configuration saves do not submit provider requests. Cloud privacy/cost and no streaming/barge-in caveat displayed.
- `WarmTts.kt`: explicit `elevenlabs` factory selection. Existing on-device/system options/defaults remain.
- `VoiceController.kt`: explicit ElevenLabs STT construction (no silent platform fallback on cloud misconfiguration); partial/speculative transcription disabled for this batch cloud leg to avoid repeat uploads/billing. Speech barge-in is deliberately drain-only during ElevenLabs TTS reply/glue playback because MediaPlayer provides no calibrated playback RMS reference. Generation-time interruption and manual Stop retain existing paths. No edits to HermesSession, response chaining, tools, memory, approval, persona or Gemma implementations.
- `MainActivity.kt`: selected ElevenLabs STT no longer requires default offline Whisper merely to open the voice line; ordinary Realtime with BOTH ElevenLabs speech legs bypasses the offline model-download empty-state gate. Other model-gate behavior retained. No automatic mode change; choose ordinary Realtime for this slice.

## Real offline execution evidence

New behavior was added incrementally after failing Java runs: missing protocol class, missing speech method, invalid-input assertion, missing secure storage method, and oversized-input assertion; each then went green.

Final commands run from repository root:

```text
$ bash scripts/test-elevenlabs-offline.sh
PASS ElevenLabsProtocolTest: 29 checks (offline)
$ bash -n scripts/test-elevenlabs-offline.sh
(exit 0; no output)
$ git diff --check
(exit 0; no output)
```

Runner uses `javac --release 17 -Xlint:all -Werror` then real `java`. Checks cover exact endpoints/auth, exact multipart wire bytes, binary audio preservation, Unicode/control/quote/backslash JSON encoding, explicitly selected TTS model, invalid key/ID/model/boundary rejection, independent STT configuration, encryption failure/null/plaintext handling and deletion, upload/text limits. Tests use synthetic strings/bytes and injected encryption/storage functions: they do **not** validate Android Keystore cryptography, actual WAV/audio, HTTP transport, media playback, cancellation races or provider compatibility. No full test/build gate was run: `scripts/gate.sh` requires unavailable Android tools and fetches dependencies, outside authorization.

## Unverified / limitations (do not represent as complete)

1. ALL new/modified Kotlin code, Java/Kotlin interop, Android resources/types and full app compilation are unverified. Portable checks prove only the Java contract/helper. Android UI/lifecycle/network/cancellation behavior has source review, not runtime tests. This is not a TDD-verified Android adapter implementation.
2. Full-file utterance STT and whole-reply TTS only. No ElevenLabs realtime STT WebSocket, streamed MP3/PCM, speech-synchronized transcript, playback RMS, AEC calibration or speech barge-in. `supportsStreaming=false`, `isWarm=false` for the TTS adapter; init may report configured readiness separately. Mic automatic re-arm, background/lock-screen/headset routes and manual Stop still require device verification.
3. Current speech path can send an extra short progress/glue utterance to selected TTS; no new retry is added. Every actual request may incur cost. Input caps are conservative local limits, not claims about provider model limits. Model/voice entitlement and API support (including chosen newer TTS models) are not checked and never trigger downgrade.
4. 60s timeout is a per-read timeout, not a total wall-clock budget. No retry on timeout/auth/quota/network failure. TTS failure settles completion without speaking; no typed error UX was added beyond existing text reply. Invalid config leaves that leg unready; generic warm-up messaging is inherited and may be poor.
5. The existing MainActivity/Settings model-recommendation badges and coach marks are still local-model-oriented for mixed speech selections; only the blocking all-cloud ordinary Realtime path was narrowly changed. Settings changes apply on pipeline reconstruction, not hot reload. Test by ending/reopening a call or restarting the app.
6. Existing transcript validator/cleaning remains in the speech-to-agent path. Existing opt-in transcript/debug logs can retain transcript text; new adapter logs do not include provider bodies, keys, audio or text. No speech-only expression/tagging stage added; reply text is submitted directly, no second agent/persona.
7. Temporary synthesized MP3 is stored in app-private no-backup files and deleted after completion/error/stop. Process death can leave a residual file; crash-recovery cleanup is deferred. Raw captured audio is kept in memory by this adapter, not written to a new file.
8. Selecting a picker option persists that backend before the configuration dialog; Cancel does not revert selection (existing picker semantics). Missing config must not cause uploads. Forget key is shared by both speech legs; models/voice settings remain. Existing reset groups disable the selected backend but do not wipe shared ElevenLabs config/key; use Forget key explicitly.

## Cloud privacy and credential boundary

Selecting the cloud transcription leg explicitly permits captured utterance upload; cloud TTS uploads reply text, potentially including sensitive results returned by Hermes. Audio/text leave the phone for `api.elevenlabs.io`; provider charges, storage/retention, jurisdiction and account/model/voice permissions must be accepted before real use. No zero-retention claim: documented `enable_logging=false` may require enterprise entitlement and is NOT forced here. Default provider host only; residency routing is deferred.

BYOK here is for a private personal client: entered credentials are Keystore-encrypted in app-private preferences; app backups are already disabled in the existing manifest. No profile secrets copied/bundled. A compromised/unlocked client can still access a usable key. Official provider docs discourage distributing long-lived API keys in client apps; a shared/public app should use a separately authorized server-side relay or suitable short-lived token flow. Prefer a scoped, quota-limited key. No relay/server or account changes made.

## Next authorized build/device work

1. Obtain separate permission for Android toolchain/runtime/dependency acquisition. On a prepared build machine use the canonical `bash scripts/gate.sh` (or `make test`) from AGENTS.md; inspect its requirements first. Compile all Kotlin/Java, run full upstream tests and stream parity harness, add proper Android adapter tests (injectable network/player and deterministic late-success/error/stop/replacement cases). Fix any compiler/UI regressions before release claims.
2. Test opt-in/config validation, missing key, Keystore failure, Save/Cancel/Forget, no request on initialization/settings/preview controls, and local speech regressions. Confirm plaintext key never appears in preferences or diagnostics. Explicitly choose ordinary Realtime, not Enhanced/Gemma. Verify no offline model requirement blocks the all-cloud path.
3. Only with separate permission for real provider usage, enter a NEW user-provided scoped key privately on the device; explicitly select intended voice/model (do not downgrade on failure). Test one known utterance, transcript, Hermes reply, MP3 speech and next mic turn. Confirm the same configured loveleiviolet Hermes instance and response/session chain remains authoritative, including a real approved tool action.
4. Exercise Stop during network transfer, prepare/playback and immediately before late completion; replacement utterance must never replay old audio or release a newer turn. Test HTTP 401/403/429/5xx, redirect refusal, offline mode, empty/malformed responses, oversized speech and process death cleanup. Confirm no automatic paid retry.
5. Verify target phone audio usage/focus, mic re-arm, screen lock/background, Bluetooth route changes and volume. Manual Stop is required during ElevenLabs speech for this slice. Implement actual streaming/playback reference and AEC/barge-in evidence as a separate milestone; do not call this driving-ready.

## Primary API references inspected

- https://elevenlabs.io/docs/api-reference/speech-to-text/convert
- https://elevenlabs.io/docs/api-reference/text-to-speech/convert
- https://elevenlabs.io/docs/api-reference/authentication

These establish endpoint/auth/payload contracts, not success for any specific account or selected voice/model.
