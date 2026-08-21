package com.voidnullvalue.icseelocal.avtalk

/**
 * Exact CXMDevPTL::NewSeq() arithmetic recovered from libFunSDK.so.
 *
 * The stock SDK keeps this as one global process counter. Every outgoing
 * Dev_SendMsg-style command consumes it, and AVTalk additionally uses the low
 * byte of the same generated value to group all 1419 fragments belonging to
 * one source CSTDStream frame.
 */
class FunSdkSequence(initial: Int = INITIAL_STATE) {
    private var value = initial

    init {
        require(initial in 0..MAX) { "FunSDK sequence initial value out of range: $initial" }
    }

    @Synchronized
    fun next(): Int {
        value += STEP
        if (value > MAX) value = INITIAL_STATE
        return value
    }

    fun nextUInt(): UInt = next().toUInt()

    companion object {
        /** Initial value stored in libFunSDK.so .data; NewSeq increments before returning. */
        const val INITIAL_STATE = 1008
        const val STEP = 8
        const val MAX = 10000
    }
}
