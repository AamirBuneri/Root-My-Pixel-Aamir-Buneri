package com.alex193a.rootmypixel.utils

/**
 * Validates the output of an `id -u` command executed through the exact
 * transport that will later run the unroot script.
 */
object RootShellProbe {
    fun isRoot(exitCode: Int, output: String): Boolean {
        if (exitCode != 0) return false

        val lines = output.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        val remoteExitLines = lines.filter { it.startsWith(REMOTE_EXIT_PREFIX) }
        val remoteExitIsSuccessful = remoteExitLines.isEmpty() || remoteExitLines.all {
            it.removePrefix(REMOTE_EXIT_PREFIX).toIntOrNull() == 0
        }

        return lines.any { it == ROOT_UID } && remoteExitIsSuccessful
    }

    private const val ROOT_UID = "0"
    private const val REMOTE_EXIT_PREFIX = "RMP_EXEC_EXIT:"
}
