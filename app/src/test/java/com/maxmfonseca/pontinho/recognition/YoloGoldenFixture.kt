package com.maxmfonseca.pontinho.recognition

import org.json.JSONArray
import org.json.JSONObject

internal object YoloGoldenFixture {
    val root: JSONObject by lazy {
        JSONObject(
            checkNotNull(javaClass.classLoader!!.getResourceAsStream("recognition/android-yolo-golden.json"))
                .bufferedReader()
                .use { it.readText() },
        )
    }

    fun cases(): JSONArray = root.getJSONArray("cases")

    fun settings(case: JSONObject? = null): YoloPostprocessSettings = root.getJSONObject("settings").let {
        val override = case?.optJSONObject("settings")
        YoloPostprocessSettings(
            confidenceThreshold = override?.takeIf { it.has("confidence") }?.getDouble("confidence")
                ?: it.getDouble("confidence"),
            iouThreshold = override?.takeIf { it.has("iou") }?.getDouble("iou")
                ?: it.getDouble("iou"),
            maxCards = override?.takeIf { it.has("maxCards") }?.getInt("maxCards") ?: it.getInt("maxCards"),
        )
    }

    fun geometry(case: JSONObject): LetterboxGeometry = case.getJSONObject("geometry").let {
        LetterboxGeometry(
            sourceWidth = it.getInt("sourceWidth"),
            sourceHeight = it.getInt("sourceHeight"),
            inputWidth = it.getInt("inputWidth"),
            inputHeight = it.getInt("inputHeight"),
            resizedWidth = it.getInt("resizedWidth"),
            resizedHeight = it.getInt("resizedHeight"),
            scale = it.getDouble("scale"),
            padX = it.getDouble("padX"),
            padY = it.getDouble("padY"),
            padRight = it.getInt("padRight"),
            padBottom = it.getInt("padBottom"),
        )
    }

    fun output(case: JSONObject): FloatArray {
        val shape = root.getJSONArray("tensorShape")
        val channels = shape.getInt(1)
        val anchors = shape.getInt(2)
        return FloatArray(channels * anchors).also { output ->
            val rows = case.getJSONArray("anchors")
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val anchorIndex = row.getInt("anchor")
                val xywh = row.getJSONArray("xywh")
                for (channel in 0 until 4) output[channel * anchors + anchorIndex] = xywh.getDouble(channel).toFloat()
                val scores = row.getJSONObject("scores")
                for (key in scores.keys()) output[(4 + key.toInt()) * anchors + anchorIndex] = scores.getDouble(key).toFloat()
            }
        }
    }
}
