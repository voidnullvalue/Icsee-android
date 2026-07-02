package com.voidnullvalue.icseelocal.ptz

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class AuxParameterDto(
    @SerialName("Number") val number: Int,
    @SerialName("Status") val status: String,
)

@Serializable
internal data class OpptzParameterDto(
    @SerialName("AUX") val aux: AuxParameterDto,
    @SerialName("Channel") val channel: Int,
    @SerialName("MenuOpts") val menuOpts: String,
    @SerialName("Pattern") val pattern: String,
    @SerialName("Preset") val preset: Int,
    @SerialName("Step") val step: Int,
    @SerialName("Tour") val tour: Int,
)

@Serializable
internal data class OpptzControlBodyDto(
    @SerialName("Command") val command: String,
    @SerialName("Parameter") val parameter: OpptzParameterDto,
)

@Serializable
internal data class OpptzControlRequestDto(
    @SerialName("Name") val name: String = "OPPTZControl",
    @SerialName("SessionID") val sessionID: String,
    @SerialName("OPPTZControl") val oppTzControl: OpptzControlBodyDto,
)

/**
 * Builds the `OPPTZControl` JSON envelope exactly as specified in the task
 * brief, including the documented compatibility stop
 * (`DirectionUp`, `Preset: -1`, `Step: 5`).
 */
object PtzRequestBuilder {
    private val json = Json { encodeDefaults = true }

    fun sessionIdHex(session: UInt): String = "0x%08X".format(session.toLong())

    /**
     * @param step 0..10 (speed slider range per the task brief)
     * @param preset selected preset (0..100 UI range), or -1 only for the compatibility stop
     */
    fun build(
        command: PtzCommand,
        sessionId: UInt,
        channel: Int = 0,
        step: Int = 0,
        preset: Int = 0,
        tour: Int? = null,
    ): String {
        require(step in 0..10) { "step must be 0..10, was $step" }
        require(preset in -1..100) { "preset must be -1..100, was $preset" }
        val dto = OpptzControlRequestDto(
            sessionID = sessionIdHex(sessionId),
            oppTzControl = OpptzControlBodyDto(
                command = command.wireValue,
                parameter = OpptzParameterDto(
                    aux = AuxParameterDto(number = 0, status = "On"),
                    channel = channel,
                    menuOpts = "Enter",
                    pattern = "Start",
                    preset = preset,
                    step = step,
                    tour = tour ?: if (command.isTour) 1 else 0,
                ),
            ),
        )
        return json.encodeToString(OpptzControlRequestDto.serializer(), dto)
    }

    /** The documented compatibility stop request: no verified universal DirectionStop exists. */
    fun buildCompatibilityStop(sessionId: UInt, channel: Int = 0): String =
        build(PtzCommand.DIRECTION_UP, sessionId, channel = channel, step = 5, preset = -1, tour = 0)
}
