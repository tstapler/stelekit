package dev.stapler.stelekit.db.libsql

internal actual fun loadLibsqlNativeLibrary() {
    System.loadLibrary("stelekit_libsql")
}
