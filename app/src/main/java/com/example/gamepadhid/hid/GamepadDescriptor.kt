package com.example.gamepadhid.hid

/**
 * Descriptor HID y constantes del gamepad virtual (estilo Xbox).
 *
 * Reporte de entrada (Report ID 1), 9 bytes sin contar el ID:
 *
 *  byte 0-1 : 16 botones (1 bit cada uno, ver BTN_*)
 *  byte 2   : D-pad como "hat switch" (nibble bajo: 0=N,1=NE,2=E,3=SE,4=S,5=SW,6=W,7=NW, 8=centro)
 *  byte 3   : stick izquierdo X   (-127..127)
 *  byte 4   : stick izquierdo Y   (-127..127, negativo = arriba)
 *  byte 5   : stick derecho X     (Z,  -127..127)
 *  byte 6   : stick derecho Y     (Rz, -127..127)
 *  byte 7   : gatillo izquierdo L2  (0..255)  -> Simulation Controls: Brake
 *  byte 8   : gatillo derecho  R2  (0..255)  -> Simulation Controls: Accelerator
 *
 * Los números de botón siguen el orden de Linux/Android para "Game Pad"
 * (BTN_SOUTH=A, BTN_EAST=B, BTN_NORTH=X, BTN_WEST=Y, BTN_TL, BTN_TR...),
 * así Android TV, Fire TV y PCs lo reconocen con la asignación estándar.
 * Los bits 2 y 5 quedan sin usar a propósito (BTN_C y BTN_Z de Linux).
 *
 * L2/R2 se exponen DOS veces: como eje analógico y como botón digital,
 * porque cada host interpreta una u otra forma.
 */
object GamepadDescriptor {

    const val REPORT_ID = 1
    const val REPORT_SIZE = 9

    // Posición (bit) de cada botón dentro de los 16 bits de botones
    const val BTN_A = 0
    const val BTN_B = 1
    const val BTN_X = 3
    const val BTN_Y = 4
    const val BTN_L1 = 6
    const val BTN_R1 = 7
    const val BTN_L2 = 8
    const val BTN_R2 = 9
    const val BTN_SELECT = 10
    const val BTN_START = 11
    const val BTN_MODE = 12
    const val BTN_L3 = 13
    const val BTN_R3 = 14

    private val RAW = intArrayOf(
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x05,             // Usage (Game Pad)
        0xA1, 0x01,             // Collection (Application)
        0x85, REPORT_ID,        //   Report ID (1)

        // --- 16 botones ---
        0x05, 0x09,             //   Usage Page (Button)
        0x19, 0x01,             //   Usage Minimum (1)
        0x29, 0x10,             //   Usage Maximum (16)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x01,             //   Logical Maximum (1)
        0x75, 0x01,             //   Report Size (1)
        0x95, 0x10,             //   Report Count (16)
        0x81, 0x02,             //   Input (Data, Var, Abs)

        // --- D-pad (hat switch) ---
        0x05, 0x01,             //   Usage Page (Generic Desktop)
        0x09, 0x39,             //   Usage (Hat switch)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x07,             //   Logical Maximum (7)
        0x35, 0x00,             //   Physical Minimum (0)
        0x46, 0x3B, 0x01,       //   Physical Maximum (315)
        0x65, 0x14,             //   Unit (grados)
        0x75, 0x04,             //   Report Size (4)
        0x95, 0x01,             //   Report Count (1)
        0x81, 0x42,             //   Input (Data, Var, Abs, Null state) -> 8 = sin pulsar
        0x75, 0x04,             //   Report Size (4)  relleno
        0x95, 0x01,             //   Report Count (1)
        0x81, 0x03,             //   Input (Const, Var, Abs)

        // --- 4 ejes de sticks ---
        0x65, 0x00,             //   Unit (ninguna)
        0x35, 0x00,             //   Physical Minimum (0)
        0x45, 0x00,             //   Physical Maximum (0)
        0x09, 0x30,             //   Usage (X)   stick izq. horizontal
        0x09, 0x31,             //   Usage (Y)   stick izq. vertical
        0x09, 0x32,             //   Usage (Z)   stick der. horizontal
        0x09, 0x35,             //   Usage (Rz)  stick der. vertical
        0x15, 0x81,             //   Logical Minimum (-127)
        0x25, 0x7F,             //   Logical Maximum (127)
        0x75, 0x08,             //   Report Size (8)
        0x95, 0x04,             //   Report Count (4)
        0x81, 0x02,             //   Input (Data, Var, Abs)

        // --- Gatillos analógicos ---
        0x05, 0x02,             //   Usage Page (Simulation Controls)
        0x09, 0xC5,             //   Usage (Brake)        = L2
        0x09, 0xC4,             //   Usage (Accelerator)  = R2
        0x15, 0x00,             //   Logical Minimum (0)
        0x26, 0xFF, 0x00,       //   Logical Maximum (255)
        0x75, 0x08,             //   Report Size (8)
        0x95, 0x02,             //   Report Count (2)
        0x81, 0x02,             //   Input (Data, Var, Abs)

        0xC0                    // End Collection
    )

    /** Descriptor listo para BluetoothHidDeviceAppSdpSettings. */
    val DESCRIPTOR: ByteArray = ByteArray(RAW.size) { RAW[it].toByte() }
}
