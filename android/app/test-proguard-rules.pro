# AndroidX Test references these CLASS-retained static-analysis annotations.
# They are not runtime dependencies; scope this suppression to the test APK.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
