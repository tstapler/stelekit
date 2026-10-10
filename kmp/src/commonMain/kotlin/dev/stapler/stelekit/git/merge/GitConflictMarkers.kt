package dev.stapler.stelekit.git.merge

internal object GitConflictMarkers {
    const val LOCAL_START = "<<<<<<< LOCAL"
    const val BASE_DIVIDER = "||||||| BASE"
    const val DIVIDER = "======="
    const val REMOTE_END = ">>>>>>> REMOTE"
}
