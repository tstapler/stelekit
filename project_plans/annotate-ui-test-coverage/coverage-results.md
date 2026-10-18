# Coverage results — ui.annotate (HEAD 7ca22090)

JaCoCo does not merge the JVM and Android reports (see the comment above `jacocoAndroidTestReport`
in `kmp/build.gradle.kts`), so the criterion-10 figure is a line-level union computed by
`union_coverage.py` (a line is covered if `ci > 0` in either report, keyed by file + line).

```
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :kmp:jvmTest --tests 'dev.stapler.stelekit.*annotate*' :kmp:jacocoTestReport \
          :kmp:testDebugUnitTest --tests 'dev.stapler.stelekit.*annotate*' :kmp:jacocoAndroidTestReport
python3 -I project_plans/annotate-ui-test-coverage/union_coverage.py
```

| Report  | Covered | Total | % |
|---------|--------:|------:|--:|
| JVM     | 1334 | 2649 | 50.4 |
| Android | 1970 | 2631 | 74.9 |
| Union   | 2202 | 2657 | 82.9 |

Branch targets (JVM report, same run): `AnnotationEditorViewModel.kt` 168/198 = 84.8%,
`DepthEstimationCoordinator.kt` 27/28 = 96.4%.

Tests: 264 annotate tests (jvmTest + testDebugUnitTest), 0 failures. No display needed: commonTest
logic tests plus Robolectric (`androidUnitTest`) Compose tests.
