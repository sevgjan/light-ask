package io.github.sevgjan.ask

import com.thelightphone.lp3Keyboard.ui.Lp3KeyboardSwipeCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

/**
 * The words swipe typing can produce: the user's own vocabulary, learnt from what they've asked (in every
 * language they write), plus common English words to start with.
 */
object Vocabulary {
    /** One word as it's typed (accents, usual capitalisation) and how often it's used. */
    class Entry(val word: String, val keys: String, var count: Double)

    /** By first and last key letter ("ab" = starts with a, ends with b). */
    @Volatile private var index: Map<String, List<Entry>> = emptyMap()
    private val byWord = HashMap<String, Entry>()
    private val forms = HashMap<String, HashMap<String, Double>>() // lowercase word -> surface form counts
    @Volatile var ready = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tokens = Regex("""\p{L}+(?:['’]\p{L}+)?""")

    /** Letters a-z a swipe can pass over for this word, or null if it has letters off the keyboard. */
    fun keysOf(word: String): String? {
        val base = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace("'", "").replace("’", "").replace("ß", "ss").replace("æ", "ae").replace("ø", "o").replace("đ", "d").replace("ł", "l")
        return if (base.isNotEmpty() && base.all { it in 'a'..'z' }) base else null
    }

    fun load() {
        if (ready) return
        scope.launch {
            COMMON_ENGLISH.split(' ').forEachIndexed { i, w -> add(w, 2.0 / (1 + i / 50)) }
            runCatching {
                // Everything the user has asked counts, as the words they use most.
                for (text in Ask.db.userTexts()) {
                    if (text.contains("://")) continue
                    for (m in tokens.findAll(text)) add(m.value, 3.0)
                }
            }
            rebuild()
            ready = true
        }
    }

    /** New words the user just sent count right away. */
    fun learn(text: String) {
        scope.launch {
            for (m in tokens.findAll(text)) add(m.value, 3.0)
            rebuild()
        }
    }

    @Synchronized
    private fun add(raw: String, weight: Double) {
        if (raw.length < 2 || raw.length > 20) return
        val lower = raw.lowercase()
        val keys = keysOf(lower) ?: return
        val f = forms.getOrPut(lower) { HashMap() }
        f[raw] = (f[raw] ?: 0.0) + weight
        val e = byWord.getOrPut(lower) { Entry(lower, keys, 0.0) }
        e.count += weight
    }

    @Synchronized
    private fun rebuild() {
        index = byWord.values.groupBy { "${it.keys.first()}${it.keys.last()}" }
    }

    /** The form to type: the user's usual capitalisation, lowercase unless it's mostly written otherwise. */
    @Synchronized
    fun surface(lower: String): String {
        val f = forms[lower] ?: return lower
        val best = f.maxByOrNull { it.value }?.key ?: return lower
        return if (best.first().isUpperCase() && (f[lower] ?: 0.0) < f.getValue(best)) best else lower
    }

    /**
     * Up to [n] words for the row over the keys, each in the form to type: fixes for a typo (a letter
     * missed, added, swapped or hit next to the right one) and words that finish what's typed, most used
     * first. A word you typed that's already known isn't offered back.
     */
    @Synchronized
    fun suggest(prefix: String, n: Int = 3): List<String> {
        val keys = keysOf(prefix) ?: return emptyList()
        val lower = prefix.lowercase()
        val known = byWord.containsKey(lower)
        // How many slips to allow: none in very short words (too many near misses), more in long ones.
        val slips = when { keys.length <= 2 -> 0.0; keys.length <= 4 -> 1.0; else -> 2.0 }
        val scored = HashMap<String, Double>()
        for (e in byWord.values) {
            if (e.word == lower) continue
            val weight = ln(1 + e.count)
            if (e.keys.length > keys.length && e.keys.startsWith(keys)) {
                scored[e.word] = weight
            } else if (slips > 0 && abs(e.keys.length - keys.length) <= slips) {
                val d = typoDistance(keys, e.keys, slips)
                if (d <= slips) {
                    // A fix beats a completion when the typed letters aren't a word of their own.
                    val fix = weight - 1.6 * d + if (known) -1.0 else 2.0
                    if (fix > (scored[e.word] ?: Double.NEGATIVE_INFINITY)) scored[e.word] = fix
                }
            }
        }
        return scored.entries.sortedByDescending { it.value }.take(n).map { surface(it.key) }
    }

    /** Letters next to each other on the keyboard: hitting a neighbour is the likeliest slip. */
    private val NEIGHBOURS: Map<Char, String> = run {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val m = HashMap<Char, StringBuilder>()
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { i, c ->
                val sb = m.getOrPut(c) { StringBuilder() }
                row.getOrNull(i - 1)?.let { sb.append(it) }
                row.getOrNull(i + 1)?.let { sb.append(it) }
                // The rows are offset by half a key: the two keys above and below touch this one.
                rows.getOrNull(r - 1)?.let { up -> up.getOrNull(i)?.let(sb::append); up.getOrNull(i + 1)?.let(sb::append) }
                rows.getOrNull(r + 1)?.let { dn -> dn.getOrNull(i - 1)?.let(sb::append); dn.getOrNull(i)?.let(sb::append) }
            }
        }
        m.mapValues { it.value.toString() }
    }

    /**
     * Edit distance between two key strings, where a neighbouring key counts half, and a swap of two
     * letters counts one. Gives up (returns more than [max]) as soon as it can't stay within [max].
     */
    internal fun typoDistance(a: String, b: String, max: Double): Double {
        val prev2 = DoubleArray(b.length + 1)
        var prev = DoubleArray(b.length + 1) { it.toDouble() }
        var cur = DoubleArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i.toDouble()
            var best = cur[0]
            for (j in 1..b.length) {
                val ca = a[i - 1]; val cb = b[j - 1]
                val sub = if (ca == cb) 0.0 else if (NEIGHBOURS[ca]?.contains(cb) == true) 0.5 else 1.0
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + sub)
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < best) best = v
            }
            if (best > max) return max + 1
            System.arraycopy(prev, 0, prev2, 0, prev.size)
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    fun candidates(first: Char, last: Char): List<Entry> = index["$first$last"].orEmpty()

    // The most common English words, most common first (frequency falls off along the list).
    private const val COMMON_ENGLISH = "the be to of and in that have it for not on with he as you do at this but his by from they we say her she or an will my one all would there their what so up out if about who get which go me when make can like time no just him know take people into year your good some could them see other than then now look only come its over think also back after use two how our work first well way even new want because any these give day most us is was are been has had were said did got went made very much more many such those here where why yes ok okay hi hello thanks thank please sorry love today tomorrow tonight morning night later soon home call text sure maybe right great nice cool fine let send message see you"
}

/**
 * Swipe typing for the LP3 keyboard: compares the finger's path with each candidate word's ideal path
 * over the keys (the shape-matching idea behind gesture keyboards) and prefers words used more often.
 */
class SwipeDecoder : Lp3KeyboardSwipeCallback<String> {
    private val centers = HashMap<Char, Pair<Float, Float>>()
    private var keyWidth = 1f

    /** The keyboard reports positions as fractions (0..1) of its width and height; this turns them into key units. */
    private var sx = 1f
    private var sy = 1f
    /** How tall a key row is next to a key's width (Ask's keyboard sets the real figure). */
    var rowToKey = ROW_TO_KEY

    override fun onSwipeLayoutReady(letters: String, xs: FloatArray, ys: FloatArray) {
        val raw = HashMap<Char, Pair<Float, Float>>()
        for (i in letters.indices) if (i < xs.size && i < ys.size) raw[letters[i].lowercaseChar()] = xs[i] to ys[i]
        // One key pitch across (q to w), one row down (q to a); rows are a bit taller than keys are wide.
        val q = raw['q']; val w = raw['w']; val a = raw['a']
        val pitch = if (q != null && w != null) kotlin.math.abs(w.first - q.first) else 0.1f
        val row = if (q != null && a != null) kotlin.math.abs(a.second - q.second) else 0.33f
        sx = 1f / pitch.coerceAtLeast(1e-4f)
        sy = rowToKey / row.coerceAtLeast(1e-4f)
        centers.clear()
        for ((c, p) in raw) centers[c] = p.first * sx to p.second * sy
        keyWidth = 1f
        Vocabulary.load()
    }

    override fun onSwipeStarted() {
    }

    override fun getWordForResult(result: String): CharSequence = result

    override fun onSwipeCompleted(xs: FloatArray, ys: FloatArray, times: FloatArray): List<String> {
        val n = minOf(xs.size, ys.size)
        if (n < 2 || centers.isEmpty() || !Vocabulary.ready) return emptyList()
        val path = List(n) { xs[it] * sx to ys[it] * sy }
        if (length(path) < keyWidth * 0.5f) return emptyList() // a tap, not a swipe
        val starts = nearestKeys(path.first(), 3)
        val ends = nearestKeys(path.last(), 3)
        val sampled = resample(path, SAMPLES)
        val scored = ArrayList<Pair<Vocabulary.Entry, Double>>()
        for (a in starts) for (b in ends) for (e in Vocabulary.candidates(a, b)) {
            val ideal = idealPath(e.keys) ?: continue
            val d = meanDistance(sampled, resample(ideal, SAMPLES)) / keyWidth
            if (d > 1.5) continue
            // Closer shape wins; frequency breaks near-ties (a common word can beat a slightly closer rare one).
            val score = -d * 5.0 + ln(1.0 + e.count) * 0.3 - startEndPenalty(path, e.keys)
            scored += e to score
        }
        val result = scored.sortedByDescending { it.second }.take(5).map { Vocabulary.surface(it.first.word) }
        return result
    }

    /** Swipes start and end on the word's first and last keys: penalise candidates whose ends are farther. */
    private fun startEndPenalty(path: List<Pair<Float, Float>>, keys: String): Double {
        val a = centers[keys.first()] ?: return 1.0
        val b = centers[keys.last()] ?: return 1.0
        val ds = hypot(path.first().first - a.first, path.first().second - a.second) / keyWidth
        val de = hypot(path.last().first - b.first, path.last().second - b.second) / keyWidth
        return (ds + de).toDouble() * 0.6
    }

    private fun nearestKeys(p: Pair<Float, Float>, k: Int): List<Char> =
        centers.entries.sortedBy { hypot(it.value.first - p.first, it.value.second - p.second) }
            .filter { hypot(it.value.first - p.first, it.value.second - p.second) < keyWidth * 1.6f }
            .take(k).map { it.key }

    /** The line through the word's keys, repeated letters once ("hello" passes l once). */
    private fun idealPath(keys: String): List<Pair<Float, Float>>? {
        val out = ArrayList<Pair<Float, Float>>()
        var prev = ' '
        for (c in keys) {
            if (c == prev) continue
            out += centers[c] ?: return null
            prev = c
        }
        return if (out.size == 1) listOf(out[0], out[0]) else out
    }

    private fun length(p: List<Pair<Float, Float>>): Float =
        (1 until p.size).sumOf { hypot(p[it].first - p[it - 1].first, p[it].second - p[it - 1].second).toDouble() }.toFloat()

    /** [count] points evenly spaced along the path. */
    private fun resample(p: List<Pair<Float, Float>>, count: Int): List<Pair<Float, Float>> {
        val total = length(p)
        if (total <= 0f) return List(count) { p.first() }
        val step = total / (count - 1)
        val out = ArrayList<Pair<Float, Float>>(count)
        out += p.first()
        var acc = 0f
        var i = 1
        var prev = p.first()
        while (i < p.size && out.size < count) {
            val cur = p[i]
            val seg = hypot(cur.first - prev.first, cur.second - prev.second)
            if (seg > 0f && acc + seg >= step) {
                val t = (step - acc) / seg
                val q = (prev.first + t * (cur.first - prev.first)) to (prev.second + t * (cur.second - prev.second))
                out += q
                prev = q
                acc = 0f
            } else {
                acc += seg
                prev = cur
                i++
            }
        }
        while (out.size < count) out += p.last()
        return out
    }

    private fun meanDistance(a: List<Pair<Float, Float>>, b: List<Pair<Float, Float>>): Double {
        var sum = 0.0
        for (i in a.indices) sum += hypot((a[i].first - b[i].first).toDouble(), (a[i].second - b[i].second).toDouble())
        return sum / a.size
    }

    private companion object {
        const val SAMPLES = 48
        /** Height of a key row relative to a key's width on the LP3 keyboard. */
        const val ROW_TO_KEY = 1.2f
    }
}
