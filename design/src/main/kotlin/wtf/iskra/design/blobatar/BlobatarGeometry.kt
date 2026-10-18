//  Ported from blobatar (https://blobatar.dev), MIT licence,
//  Copyright (c) 2026 Alain. See Licenses/blobatar.txt.
//  Kotlin port of IskraDesign/Blobatar/BlobatarPath.swift and BlobatarGeometry.swift.

package wtf.iskra.design.blobatar

import androidx.compose.ui.graphics.Path
import kotlin.math.abs
import kotlin.math.floor

/** Одна команда рисования в пространстве 100×100, в котором раскладывается каждое существо. */
internal sealed interface BlobatarCommand {
    data class Move(val x: Double, val y: Double) : BlobatarCommand
    data class Cubic(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val x: Double, val y: Double) : BlobatarCommand
    data class Quadratic(val cx: Double, val cy: Double, val x: Double, val y: Double) : BlobatarCommand
    data class Line(val x: Double, val y: Double) : BlobatarCommand
    data class Horizontal(val x: Double) : BlobatarCommand
    data class Vertical(val y: Double) : BlobatarCommand
    data object Close : BlobatarCommand
}

/**
 * Обведённый контур: команды и два выхода из них — SVG (для фикстуры) и `Path` (для экрана), на одних
 * и тех же округлённых числах, чтобы нарисованное и проверенное не разошлись.
 */
internal class BlobatarOutline(val commands: List<BlobatarCommand>) {
    /** `d` — буква команды и её числа через пробел, команды без разделителя, как пишет blobatar. */
    val pathData: String
        get() = buildString {
            for (command in commands) {
                when (command) {
                    is BlobatarCommand.Move -> append("M").append(format(command.x)).append(' ').append(format(command.y))
                    is BlobatarCommand.Cubic -> append("C").append(
                        listOf(command.x1, command.y1, command.x2, command.y2, command.x, command.y).joinToString(" ") { format(it) },
                    )
                    is BlobatarCommand.Quadratic -> append("Q").append(
                        listOf(command.cx, command.cy, command.x, command.y).joinToString(" ") { format(it) },
                    )
                    is BlobatarCommand.Line -> append("L").append(format(command.x)).append(' ').append(format(command.y))
                    is BlobatarCommand.Horizontal -> append("H").append(format(command.x))
                    is BlobatarCommand.Vertical -> append("V").append(format(command.y))
                    BlobatarCommand.Close -> append("Z")
                }
            }
        }

    /** Тот же контур как `Path` Compose, в квадрате `scale × 100`. */
    fun path(scale: Float, into: Path = Path()): Path {
        fun p(v: Double) = (round2(v) * scale).toFloat()
        var penX = 0f
        var penY = 0f
        for (command in commands) {
            when (command) {
                is BlobatarCommand.Move -> {
                    penX = p(command.x); penY = p(command.y)
                    into.moveTo(penX, penY)
                }
                is BlobatarCommand.Cubic -> {
                    penX = p(command.x); penY = p(command.y)
                    into.cubicTo(p(command.x1), p(command.y1), p(command.x2), p(command.y2), penX, penY)
                }
                is BlobatarCommand.Quadratic -> {
                    penX = p(command.x); penY = p(command.y)
                    into.quadraticTo(p(command.cx), p(command.cy), penX, penY)
                }
                is BlobatarCommand.Line -> {
                    penX = p(command.x); penY = p(command.y)
                    into.lineTo(penX, penY)
                }
                is BlobatarCommand.Horizontal -> {
                    penX = p(command.x)
                    into.lineTo(penX, penY)
                }
                is BlobatarCommand.Vertical -> {
                    penY = p(command.y)
                    into.lineTo(penX, penY)
                }
                BlobatarCommand.Close -> into.close()
            }
        }
        return into
    }

    /** Все точки команд (с контрольными) — для рамки существа; контрольные точки охватывают кривую. */
    fun points(): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        var penX = 0.0
        var penY = 0.0
        for (command in commands) {
            when (command) {
                is BlobatarCommand.Move -> { penX = command.x; penY = command.y; points += penX to penY }
                is BlobatarCommand.Cubic -> {
                    points += command.x1 to command.y1; points += command.x2 to command.y2
                    penX = command.x; penY = command.y; points += penX to penY
                }
                is BlobatarCommand.Quadratic -> {
                    points += command.cx to command.cy
                    penX = command.x; penY = command.y; points += penX to penY
                }
                is BlobatarCommand.Line -> { penX = command.x; penY = command.y; points += penX to penY }
                is BlobatarCommand.Horizontal -> { penX = command.x; points += penX to penY }
                is BlobatarCommand.Vertical -> { penY = command.y; points += penX to penY }
                BlobatarCommand.Close -> Unit
            }
        }
        return points.map { round2(it.first) to round2(it.second) }
    }

    companion object {
        /** До сотых, как `Math.round` в JavaScript: половины — к +∞, а не от нуля. */
        fun round2(value: Double): Double = hundredths(value).toDouble() / 100

        private fun hundredths(value: Double): Int = floor(value * 100 + 0.5).toInt()

        /**
         * Число, как его пишет `String(n)` в JavaScript, для уже квантованного до сотых: без хвостовых
         * нулей, без экспоненты, `-0` как `0`. Собрано из целых сотых — второго округления нет.
         */
        fun format(value: Double): String {
            val n = hundredths(value)
            if (n == 0) return "0"
            val sign = if (n < 0) "-" else ""
            val magnitude = abs(n)
            val whole = magnitude / 100
            val frac = magnitude % 100
            if (frac == 0) return "$sign$whole"
            if (frac % 10 == 0) return "$sign$whole.${frac / 10}"
            return "$sign$whole." + if (frac < 10) "0$frac" else "$frac"
        }
    }
}

/** Пять примитивов, которыми обведён каждый силуэт. Ничего из этого — не картинка: существо вычисляется. */
internal object BlobatarGeometry {
    /** `|x/a|^n + |y/b|^n = 1` четырьмя кубическими Безье — по одной на четверть. */
    fun superellipse(cx: Double, cy: Double, rx: Double, ry: Double, n: Double = 4.0, rot: Double = 0.0): BlobatarOutline {
        // После n ≈ 5.55 смещение превысило бы радиус и кривая выпятилась бы *за* свою рамку.
        val k = minOf(1.0, (8 * BlobatarMath.pow(2.0, -1 / n) - 4) / 3)
        val ak = rx * k
        val bk = ry * k
        val points = listOf(
            rx to 0.0,
            rx to bk, ak to ry, 0.0 to ry,
            -ak to ry, -rx to bk, -rx to 0.0,
            -rx to -bk, -ak to -ry, 0.0 to -ry,
            ak to -ry, rx to -bk, rx to 0.0,
        )
        val t = rot * Math.PI / 180
        val cosine = BlobatarMath.cos(t)
        val sine = BlobatarMath.sin(t)
        fun at(i: Int): Pair<Double, Double> {
            val (x, y) = points[i]
            return (cx + x * cosine - y * sine) to (cy + x * sine + y * cosine)
        }
        val commands = mutableListOf<BlobatarCommand>(BlobatarCommand.Move(at(0).first, at(0).second))
        for (i in 1 until 13 step 3) {
            val c1 = at(i)
            val c2 = at(i + 1)
            val end = at(i + 2)
            commands += BlobatarCommand.Cubic(c1.first, c1.second, c2.first, c2.second, end.first, end.second)
        }
        commands += BlobatarCommand.Close
        return BlobatarOutline(commands)
    }

    /** Замкнутый сплайн Катмулла — Рома через радиусы по кругу: всё, что должно выглядеть рисованным. */
    fun blob(cx: Double, cy: Double, rx: Double, ry: Double, radii: List<Double>, rot: Double = 0.0): BlobatarOutline {
        val n = radii.size
        val t0 = rot * Math.PI / 180
        val points = radii.mapIndexed { index, m ->
            val a = t0 + 2 * Math.PI * index.toDouble() / n.toDouble()
            (cx + rx * m * BlobatarMath.cos(a)) to (cy + ry * m * BlobatarMath.sin(a))
        }
        fun at(i: Int) = points[((i % n) + n) % n]
        val commands = mutableListOf<BlobatarCommand>(BlobatarCommand.Move(at(0).first, at(0).second))
        for (i in 0 until n) {
            val (x0, y0) = at(i - 1)
            val (x1, y1) = at(i)
            val (x2, y2) = at(i + 1)
            val (x3, y3) = at(i + 2)
            commands += BlobatarCommand.Cubic(
                x1 + (x2 - x0) / 6, y1 + (y2 - y0) / 6,
                x2 - (x3 - x1) / 6, y2 - (y3 - y1) / 6,
                x2, y2,
            )
        }
        commands += BlobatarCommand.Close
        return BlobatarOutline(commands)
    }

    /** Правильный многоугольник со скруглёнными углами: угол срезан по обоим рёбрам и соединён квадратичной. */
    fun polygon(cx: Double, cy: Double, rx: Double, ry: Double, sides: Int, round: Double = 0.3, rot: Double = 0.0): BlobatarOutline {
        val k = if (round > 0) (if (round < 1) round / 2 else 0.5) else 0.0
        // −90°, чтобы вершина была сверху: треугольник смотрит вверх и стоит на ребре.
        val t0 = rot * Math.PI / 180 - Math.PI / 2
        val vertices = (0 until sides).map { i ->
            val a = t0 + 2 * Math.PI * i.toDouble() / sides.toDouble()
            (cx + rx * BlobatarMath.cos(a)) to (cy + ry * BlobatarMath.sin(a))
        }
        fun at(i: Int) = vertices[((i % sides) + sides) % sides]
        fun cut(i: Int, j: Int): Pair<Double, Double> {
            val (x0, y0) = at(i)
            val (x1, y1) = at(j)
            return (x0 + (x1 - x0) * k) to (y0 + (y1 - y0) * k)
        }
        val start = cut(0, -1)
        val commands = mutableListOf<BlobatarCommand>(BlobatarCommand.Move(start.first, start.second))
        for (i in 0 until sides) {
            val (x, y) = at(i)
            val out = cut(i, i + 1)
            commands += BlobatarCommand.Quadratic(x, y, out.first, out.second)
            if (k < 0.5) {
                val next = cut(i + 1, i)
                commands += BlobatarCommand.Line(next.first, next.second)
            }
        }
        commands += BlobatarCommand.Close
        return BlobatarOutline(commands)
    }

    /** Прямой отрезок капсулы как простой прямоугольник: с двумя кругами-торцами — точный стадион. */
    fun box(cx: Double, cy: Double, rx: Double, ry: Double) = BlobatarOutline(
        listOf(
            BlobatarCommand.Move(cx - rx, cy - ry),
            BlobatarCommand.Horizontal(cx + rx),
            BlobatarCommand.Vertical(cy + ry),
            BlobatarCommand.Horizontal(cx - rx),
            BlobatarCommand.Close,
        ),
    )

    /** Заострение капли: две касательные из вершины к эллипсу тела — точка растёт из головы. */
    fun taper(cx: Double, cy: Double, rx: Double, ry: Double, tip: Double): BlobatarOutline {
        val t = maxOf(1.05, tip)
        val tx = rx * BlobatarMath.sqrt(1 - 1 / (t * t))
        val ty = cy - ry / t
        val apex = cy - t * ry
        val px = tx * 0.14
        val py = ty + 0.86 * (apex - ty)
        return BlobatarOutline(
            listOf(
                BlobatarCommand.Move(cx - tx, ty),
                BlobatarCommand.Line(cx - px, py),
                BlobatarCommand.Quadratic(cx, apex, cx + px, py),
                BlobatarCommand.Line(cx + tx, ty),
                BlobatarCommand.Close,
            ),
        )
    }
}
