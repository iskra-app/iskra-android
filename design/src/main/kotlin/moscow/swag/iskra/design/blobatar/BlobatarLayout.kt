//  Ported from blobatar (https://blobatar.dev), MIT licence,
//  Copyright (c) 2026 Alain. See Licenses/blobatar.txt.
//  Kotlin port of IskraDesign/Blobatar/BlobatarShapes.swift and BlobatarLayout.swift.

package moscow.swag.iskra.design.blobatar

import kotlin.math.abs

/** Тело числами, до всякой обводки. */
internal data class BlobatarBody(
    var cx: Double,
    var cy: Double,
    var rx: Double,
    var ry: Double,
    /** Квадратность — для силуэтов-суперэллипсов. */
    var n: Double,
    var rot: Double,
    /** Множители радиуса по вершинам — для сплайнов. */
    var radii: List<Double>,
    var sides: Int = 0,
    var round: Double = 0.0,
)

/** Область, в которую должны поместиться глаза. */
internal data class BlobatarEllipse(val cx: Double, val cy: Double, val rx: Double, val ry: Double)

internal data class BlobatarPetal(val cx: Double, val cy: Double, val r: Double)

/** Что силуэт добавляет вокруг тела: лепестки-круги и (только у капли) заострение. */
internal class BlobatarDecoration {
    val petals = mutableListOf<BlobatarPetal>()
    val extra = mutableListOf<BlobatarOutline>()
}

/** Один силуэт: всё, чтобы его нарисовать, и ничего о том, как часто он выпадает. */
internal class BlobatarSilhouette(
    val name: String,
    /** Какую часть кадра занимает тело. */
    val core: Double,
    val body: ((BlobatarTraits, BlobatarBody) -> Unit)? = null,
    /** Область глаз; без неё — само тело. */
    val face: ((BlobatarBody) -> BlobatarEllipse)? = null,
    val decorate: ((BlobatarTraits, BlobatarBody, BlobatarDecoration) -> Unit)? = null,
    /** Контур тела; без него — суперэллипс. */
    val path: ((BlobatarBody) -> BlobatarOutline)? = null,
) {
    companion object {
        private val spline: (BlobatarBody) -> BlobatarOutline = { b ->
            BlobatarGeometry.blob(b.cx, b.cy, b.rx, b.ry, b.radii, b.rot)
        }
        private val poly: (BlobatarBody) -> BlobatarOutline = { b ->
            BlobatarGeometry.polygon(b.cx, b.cy, b.rx, b.ry, b.sides, b.round, b.rot)
        }

        private fun shrunk(k: Double): (BlobatarBody) -> BlobatarEllipse = { b -> BlobatarEllipse(b.cx, b.cy, b.rx * k, b.ry * k) }

        private val splineFace: (BlobatarBody) -> BlobatarEllipse = { b -> shrunk((b.radii.minOrNull() ?: 1.0) * 0.95)(b) }

        private val roundShape = BlobatarSilhouette("round", 1.0)

        private val organic = BlobatarSilhouette("organic", 0.98, face = splineFace, path = spline)

        /** `round`, скошенный и повёрнутый. Тот же путь, другие числа. */
        private val boxy = BlobatarSilhouette(
            "boxy", 0.86,
            body = { t, b ->
                b.n = t.num("body.n", 3.4, 6.0)
                b.rot = t.num("body.rot", -20.0, 20.0)
            },
        )

        private val capsule = BlobatarSilhouette(
            "capsule", 1.02,
            body = { t, b -> b.ry *= t.num("capsule.squat", 0.55, 0.68) },
            face = shrunk(0.94),
            decorate = { _, b, out ->
                for (side in listOf(-1.0, 1.0)) out.petals += BlobatarPetal(b.cx + side * (b.rx - b.ry), b.cy, b.ry)
            },
            path = { b -> BlobatarGeometry.box(b.cx, b.cy, b.rx - b.ry, b.ry) },
        )

        private val nub = BlobatarSilhouette(
            "nub", 0.88,
            decorate = { t, b, out ->
                for (i in 0 until t.int("nub.n", 1, 2)) {
                    val a = t.num("nub.a$i", 0.0, 2 * Math.PI)
                    out.petals += BlobatarPetal(
                        b.cx + BlobatarMath.cos(a) * b.rx * 0.88,
                        b.cy + BlobatarMath.sin(a) * b.rx * 0.88,
                        b.rx * t.num("nub.r$i", 0.24, 0.4),
                    )
                }
            },
        )

        /** `organic` с лопастями сверху. */
        private val cloud = BlobatarSilhouette(
            "cloud", 0.78,
            face = splineFace,
            decorate = { t, b, out ->
                val count = t.int("cloud.n", 4, 6)
                for (i in 0 until count) {
                    val a = Math.PI + Math.PI * (i.toDouble() + 0.5) / count.toDouble()
                    out.petals += BlobatarPetal(
                        b.cx + BlobatarMath.cos(a) * b.rx * 0.8,
                        b.cy + BlobatarMath.sin(a) * b.rx * 0.5,
                        b.rx * t.num("cloud.r$i", 0.44, 0.62),
                    )
                }
            },
            path = spline,
        )

        private val droplet = BlobatarSilhouette(
            "droplet", 0.78,
            // Сдвинута вниз на то, что заострение добавляет сверху; n — настоящий эллипс, к нему касательные.
            body = { _, b ->
                b.cy += 0.22 * b.ry
                b.n = 2.0
            },
            face = { b -> BlobatarEllipse(b.cx, b.cy + b.ry * 0.05, b.rx * 0.88, b.ry * 0.88) },
            decorate = { t, b, out ->
                out.extra += BlobatarGeometry.taper(b.cx, b.cy, b.rx, b.ry, t.num("droplet.tip", 1.4, 1.65))
            },
        )

        private val hexagon = BlobatarSilhouette(
            "hexagon", 1.05,
            body = { t, b ->
                b.sides = 6
                b.rot = t.num("body.rot", -12.0, 12.0)
                b.round = t.num("poly.round", 0.24, 0.5)
            },
            face = shrunk(0.84),
            path = poly,
        )

        private val sun = BlobatarSilhouette(
            "sun", 0.7,
            decorate = { t, b, out ->
                val count = t.int("sun.n", 6, 9)
                val dist = b.rx * t.num("sun.dist", 1.0, 1.08)
                val r = b.rx * t.num("sun.r", 0.2, 0.26)
                val off = t.num("sun.rot", 0.0, 2 * Math.PI)
                for (i in 0 until count) {
                    val a = off + 2 * Math.PI * i.toDouble() / count.toDouble()
                    out.petals += BlobatarPetal(b.cx + BlobatarMath.cos(a) * dist, b.cy + BlobatarMath.sin(a) * dist, r)
                }
            },
        )

        /** `hexagon` с тремя сторонами и меньшим наклоном, чтобы стоял на основании. */
        private val triangle = BlobatarSilhouette(
            "triangle", 1.15,
            body = { t, b ->
                b.sides = 3
                b.rot = t.num("body.rot", -5.0, 5.0)
                b.round = t.num("poly.round", 0.24, 0.5)
            },
            face = { b -> BlobatarEllipse(b.cx, b.cy + b.ry * 0.1, b.rx * 0.54, b.ry * 0.36) },
            path = poly,
        )

        /**
         * Десять силуэтов с верхними краями полос. Веса — суть: круглые и органические — будничные,
         * треугольник — находка (2%). Эти края, диапазоны раскладки и тона палитры — одно замороженное
         * отображение сида в облик: сдвинь любое — и каждое существующее лицо сменит личность.
         */
        val bands: List<Pair<BlobatarSilhouette, Double>> = listOf(
            roundShape to 0.22, organic to 0.48, boxy to 0.6, capsule to 0.7, nub to 0.79,
            cloud to 0.86, droplet to 0.915, hexagon to 0.95, sun to 0.98, triangle to 1.0,
        )
    }
}

internal data class BlobatarEye(val cx: Double, val cy: Double, val rx: Double, val ry: Double, val n: Double, val rot: Double)

/** Существо числами. Ничего ещё не обведено и ничему не дан цвет. Порт `BlobatarLayout.swift`. */
internal class BlobatarLayout(
    val shape: String,
    val body: BlobatarBody,
    val face: BlobatarEllipse,
    val petals: List<BlobatarPetal>,
    val extra: List<BlobatarOutline>,
    val eyes: List<BlobatarEye>,
    val outline: BlobatarOutline,
) {
    companion object {
        fun make(t: BlobatarTraits): BlobatarLayout {
            val shape = silhouette(t("shape"))
            val r = t.num("body.r", 31.0, 38.0) * shape.core
            val body = BlobatarBody(
                cx = 50 + t.jitter("body.x", 1.5),
                cy = 50 + t.jitter("body.y", 1.5),
                rx = r,
                ry = r * t.num("body.ratio", 0.92, 1.08),
                n = t.num("body.n", 1.9, 2.5),
                rot = 0.0,
                radii = (0 until t.int("body.pts", 6, 8)).map { 1 + t.jitter("body.r$it", 0.16) },
            )
            shape.body?.invoke(t, body)

            val face = shape.face?.invoke(body) ?: BlobatarEllipse(body.cx, body.cy, body.rx, body.ry)
            val decoration = BlobatarDecoration()
            shape.decorate?.invoke(t, body, decoration)
            val outline = shape.path?.invoke(body)
                ?: BlobatarGeometry.superellipse(body.cx, body.cy, body.rx, body.ry, body.n, body.rot)

            return BlobatarLayout(shape.name, body, face, decoration.petals, decoration.extra, eyes(t, body, face), outline)
        }

        private fun silhouette(value: Double): BlobatarSilhouette =
            BlobatarSilhouette.bands.firstOrNull { value < it.second }?.first ?: BlobatarSilhouette.bands.last().first

        /**
         * Пара глаз, вписанная в лицо по обеим осям: досягаемость кластера мерится по лицу, и всё —
         * зазор, сдвиг взгляда, наклон — уменьшается одним множителем, когда не помещается. Поэтому
         * глаз никогда не висит за краем головы.
         */
        private fun eyes(t: BlobatarTraits, b: BlobatarBody, face: BlobatarEllipse): List<BlobatarEye> {
            val rx = b.rx
            val baseRx = t.num("eye.rx", 0.075, 0.105) * rx
            val ratio = t.num("eye.ratio", 1.9, 3.2)
            val scale = t.num("eye.scale", 0.78, 1.24)
            val stretch = t.num("eye.stretch", 0.85, 1.18)
            val clearance = t.num("eye.gap", 0.1, 0.24) * rx
            val wide = baseRx * maxOf(1.0, scale)
            val tall = baseRx * ratio * maxOf(1.0, scale * stretch)
            val baseGap = wide + rx * 0.03 + clearance

            val gx = t.jitter("gaze.x", 0.09) * face.rx
            val gy = t.num("gaze.y", -0.2, 0.08) * face.ry
            val dy = t.jitter("eye.dy", 0.04) * face.ry
            val reach = BlobatarMath.hypot(wide, tall)
            val need = BlobatarMath.hypot(
                (abs(gx) + baseGap + reach) / face.rx,
                (abs(gy) + abs(dy) + reach) / face.ry,
            )
            val fit = if (need > 0.9) 0.9 / need else 1.0

            val er = baseRx * fit
            val eyeRy = er * ratio
            val gap = baseGap * fit
            val room = maxOf(0.0, minOf(1.0, clearance / tall))
            val bound = minOf(12.0, BlobatarMath.asin(room) * 180 / Math.PI)
            val lean = t.num("eye.lean", -1.0, 1.0) * bound
            val lean2 = maxOf(-12.0, minOf(12.0, lean + t.jitter("eye.lean2", 3.5)))

            val cx = face.cx + gx * fit
            val cy = face.cy + gy * fit
            return listOf(
                BlobatarEye(cx - gap, cy, er, eyeRy, t.num("eye.n", 3.5, 6.0), lean),
                BlobatarEye(cx + gap, cy + dy * fit, er * scale, eyeRy * scale * stretch, t.num("eye.n", 3.5, 6.0), lean2),
            )
        }
    }
}
