package com.vtubephone.core.gesture

/**
 * GestureLab recording format, one record per line:
 *   s,<tNs>,<kind>,<x>,<y>,<z>            sensor sample (kind = Accel|Gyro|Gravity|Linear|Rotation)
 *   t,<tNs>,<pointer>,<phase>,<x>,<y>,<onAvatar 0|1>
 *   l,<tNs>,<label wire id or free text>   label set by the user before performing a gesture
 * Lines starting with # are comments.
 */
object MotionCsv {
    sealed interface Record { val tNs: Long }
    data class Sample(val sample: MotionSample) : Record { override val tNs get() = sample.tNs }
    data class Touch(val touch: TouchSample) : Record { override val tNs get() = touch.tNs }
    data class Label(override val tNs: Long, val label: String) : Record

    fun format(r: Record): String = when (r) {
        is Sample -> r.sample.run { "s,$tNs,${kind.name},$x,$y,$z" }
        is Touch -> r.touch.run { "t,$tNs,$pointerId,${phase.name},$x,$y,${if (onAvatar) 1 else 0}" }
        is Label -> "l,${r.tNs},${r.label.replace(',', ' ').replace('\n', ' ')}"
    }

    fun parse(text: String): List<Record> = text.lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapIndexed { index, line ->
            val f = line.split(',')
            try {
                when (f[0]) {
                    "s" -> Sample(MotionSample(f[1].toLong(), SensorKind.valueOf(f[2]), f[3].toFloat(), f[4].toFloat(), f[5].toFloat()))
                    "t" -> Touch(TouchSample(f[1].toLong(), f[2].toInt(), TouchPhase.valueOf(f[3]), f[4].toFloat(), f[5].toFloat(), f[6] == "1"))
                    "l" -> Label(f[1].toLong(), f.drop(2).joinToString(","))
                    else -> error("unknown record type ${f[0]}")
                }
            } catch (e: RuntimeException) { throw IllegalArgumentException("Bad motion CSV line ${index + 1}: $line", e) }
        }.toList()

    /** Feeds a recording through a recognizer in time order and returns every gesture it produced. */
    fun replay(records: List<Record>, recognizer: GestureRecognizer = GestureRecognizer()): List<GestureEvent> {
        val out = ArrayList<GestureEvent>()
        for (r in records.sortedBy { it.tNs }) when (r) {
            is Sample -> out += recognizer.onSample(r.sample)
            is Touch -> out += recognizer.onTouch(r.touch)
            is Label -> Unit
        }
        records.maxOfOrNull { it.tNs }?.let { out += recognizer.tick(it + 2_000 * MS) }
        return out
    }
}
