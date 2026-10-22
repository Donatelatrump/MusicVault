# Android performance infrastructure

This module is excluded unless `-Pmusicvault.benchmark=true` is supplied. It targets
`:app:performance` (`com.musicvault.app.debug`), using the existing non-debuggable,
minified, debug-signed variant and a performance-only profileable manifest.
No benchmark or profile generation was executed during this implementation session.

Use a dedicated ARM device (API28+ for profile collection), completed onboarding,
a seeded local library and explicit offline mode. Keep device, battery/thermal
state, Android version and seed library fixed across comparisons. The current
normal APK ABI configuration intentionally excludes emulator architectures.

From `android/`, when measurements are explicitly desired:

```bash
./gradlew :benchmark:connectedPerformanceAndroidTest -Pmusicvault.benchmark=true -Pandroid.testInstrumentationRunnerArguments.class=com.musicvault.benchmark.StartupBenchmark
./gradlew :benchmark:connectedPerformanceAndroidTest -Pmusicvault.benchmark=true -Pandroid.testInstrumentationRunnerArguments.class=com.musicvault.benchmark.BaselineProfileGenerator
```

The startup cases compare cold startup with no AOT compilation and with the
bundled Baseline Profile required. `FrameTimingMetric` captures startup and home
scroll frames. The profile generator captures actual ART startup/home-scroll
rules and outputs profile artifacts; it does not silently replace the existing
hand-authored `app/src/main/baseline-prof.txt`. Review the generated artifact,
copy the applicable profile into that file and rerun the comparison before
claiming a performance improvement. Benchmark reports/traces are under this
module's `build/outputs/` directories. No numbers or profile efficacy are claimed.

Home scroll requires enough local/recommendation items to scroll. This suite
does not establish full-screen navigation, playback, network-provider throughput,
font scaling or TalkBack correctness. Add specific measured journeys as those
requirements become repeatable on a stable fixture.

References: [Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview),
[Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile).
