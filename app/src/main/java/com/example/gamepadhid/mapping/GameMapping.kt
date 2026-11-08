package com.example.gamepadhid.mapping

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class ControlType { BUTTON, STICK }

/**
 * Un control del mando mapeado a un punto de la PANTALLA VIRTUAL del juego.
 *  tx, ty : posición en FRACCIÓN del ancho/alto de la pantalla virtual (0..1).
 *  radius : solo sticks; recorrido del dedo virtual, en fracción del lado corto de la pantalla virtual.
 */
data class ControlSpec(
    val id: String,
    val type: ControlType,
    var tx: Float,
    var ty: Float,
    var radius: Float
)

class GameMapping(
    val packageName: String,
    var label: String,
    val controls: MutableList<ControlSpec>
) {
    fun find(id: String): ControlSpec? = controls.firstOrNull { it.id == id }

    fun toJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("package", packageName)
        root.put("label", label)
        val array = JSONArray()
        for (c in controls) {
            array.put(
                JSONObject()
                    .put("id", c.id)
                    .put("type", if (c.type == ControlType.STICK) "stick" else "button")
                    .put("tx", c.tx.toDouble())
                    .put("ty", c.ty.toDouble())
                    .put("radius", c.radius.toDouble())
            )
        }
        root.put("controls", array)
        return root.toString()
    }

    companion object {
        fun fromJson(json: String): GameMapping? = try {
            val root = JSONObject(json)
            val array = root.getJSONArray("controls")
            val list = ArrayList<ControlSpec>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                list.add(
                    ControlSpec(
                        id = o.getString("id"),
                        type = if (o.getString("type") == "stick") ControlType.STICK else ControlType.BUTTON,
                        tx = o.getDouble("tx").toFloat(),
                        ty = o.getDouble("ty").toFloat(),
                        radius = o.optDouble("radius", 0.10).toFloat()
                    )
                )
            }
            GameMapping(root.getString("package"), root.optString("label", ""), list)
        } catch (e: JSONException) {
            null
        }

        /** Distribución típica de un juego horizontal; el usuario la ajusta sobre los botones reales. */
        fun template(pkg: String, label: String): GameMapping {
            fun button(id: String, x: Float, y: Float) = ControlSpec(id, ControlType.BUTTON, x, y, 0.10f)
            fun stick(id: String, x: Float, y: Float) = ControlSpec(id, ControlType.STICK, x, y, 0.10f)
            return GameMapping(
                pkg, label,
                mutableListOf(
                    button(ControlIds.L2, 0.05f, 0.10f),
                    button(ControlIds.L1, 0.05f, 0.24f),
                    stick(ControlIds.L_STICK, 0.14f, 0.72f),
                    button(ControlIds.SELECT, 0.40f, 0.08f),
                    button(ControlIds.START, 0.60f, 0.08f),
                    button(ControlIds.R2, 0.95f, 0.10f),
                    button(ControlIds.R1, 0.95f, 0.24f),
                    button(ControlIds.Y, 0.88f, 0.48f),
                    button(ControlIds.X, 0.80f, 0.60f),
                    button(ControlIds.B, 0.96f, 0.60f),
                    button(ControlIds.A, 0.88f, 0.72f),
                    stick(ControlIds.R_STICK, 0.68f, 0.72f)
                )
            )
        }
    }
}
