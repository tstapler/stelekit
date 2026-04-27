package dev.stapler.stelekit.command

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

/**
 * Interface for commands that can be executed and undone.
 * @param T The return type of the execution result.
 */
interface Command<T> {
    /**
     * Executes the command.
     * @return A Result containing the operation outcome.
     */
    suspend fun execute(): Either<DomainError, T>

    /**
     * Reverses the effects of the command.
     * @return A Result indicating success or failure of the undo operation.
     */
    suspend fun undo(): Either<DomainError, Unit>
    
    /**
     * Optional: A description of the command for UI purposes (e.g. "Undo Create Block")
     */
    val description: String
        get() = "Unknown Command"
}
