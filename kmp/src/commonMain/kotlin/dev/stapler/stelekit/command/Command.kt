package dev.stapler.stelekit.command

/**
 * Interface for commands that can be executed and undone.
 * @param T The return type of the execution result.
 */
interface Command<T> {
    /**
     * Executes the command.
     * @return A Result containing the operation outcome.
     */
    suspend fun execute(): Result<T>

    /**
     * Reverses the effects of the command.
     * @return A Result indicating success or failure of the undo operation.
     */
    suspend fun undo(): Result<Unit>
    
    /**
     * Optional: A description of the command for UI purposes (e.g. "Undo Create Block")
     */
    val description: String
        get() = "Unknown Command"
}
