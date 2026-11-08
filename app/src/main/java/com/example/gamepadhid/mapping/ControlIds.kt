package com.example.gamepadhid.mapping

object ControlIds {
    const val A = "A"
    const val B = "B"
    const val X = "X"
    const val Y = "Y"
    const val L1 = "L1"
    const val R1 = "R1"
    const val L2 = "L2"
    const val R2 = "R2"
    const val START = "START"
    const val SELECT = "SELECT"
    const val L_STICK = "L-stick"
    const val R_STICK = "R-stick"

    val BUTTONS = listOf(A, B, X, Y, L1, R1, L2, R2, START, SELECT)
    val STICKS = listOf(L_STICK, R_STICK)

    fun label(id: String): String = when (id) {
        SELECT -> "SEL"
        L_STICK -> "L"
        R_STICK -> "R"
        else -> id
    }
}
