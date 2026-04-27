import java.io.File
import kotlin.math.max

// ─── Entry point ─────────────────────────────────────────────────────────────
//
// CLI:    java -jar flamegraph.jar [--width N] [--title T] [--colors SCHEME]
//                                  [--output file.svg] [input.collapsed]
//
// Gradle: ./gradlew -q :tools:flamegraph:run \
//           -Pfg.width=1800 -Pfg.title="My Graph" -Pfg.colors=mem \
//           -Pfg.input=alloc.collapsed -Pfg.output=flamegraph.svg
//
// Color schemes: hot (default), mem, java, io

fun main(args: Array<String>) {
    var width  = System.getProperty("fg.width")?.toIntOrNull()  ?: 1800
    var title  = System.getProperty("fg.title")                 ?: "Flamegraph"
    var colors = System.getProperty("fg.colors")                ?: "hot"
    var output = System.getProperty("fg.output")
    var input  = System.getProperty("fg.input")

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--width"  -> { i++; width  = args[i].toInt() }
            "--title"  -> { i++; title  = args[i] }
            "--colors" -> { i++; colors = args[i] }
            "--output" -> { i++; output = args[i] }
            else -> input = args[i]
        }
        i++
    }

    val lines = when {
        input != null && input!!.isNotEmpty() -> File(input!!).readLines()
        else -> generateSequence(::readLine).toList()
    }

    val root = parseStacks(lines)
    if (root.count == 0L) {
        System.err.println("flamegraph: no stack data found in input")
        return
    }

    val svg = generateSvg(root, width, title, colors)

    if (output != null) File(output!!).writeText(svg) else print(svg)
}

// ─── Data model ──────────────────────────────────────────────────────────────

class Node(val name: String) {
    var count = 0L
    val children = LinkedHashMap<String, Node>()
}

fun parseStacks(lines: List<String>): Node {
    val root = Node("<root>")
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
        val sp = trimmed.lastIndexOf(' ')
        if (sp < 0) continue
        val count = trimmed.substring(sp + 1).toLongOrNull() ?: continue
        if (count <= 0) continue
        root.count += count
        var node = root
        for (frame in trimmed.substring(0, sp).split(';')) {
            if (frame.isEmpty()) continue
            node = node.children.getOrPut(frame) { Node(frame) }
            node.count += count
        }
    }
    return root
}

// ─── Layout ──────────────────────────────────────────────────────────────────

data class Rect(val name: String, val x: Double, val w: Double, val depth: Int, val count: Long)

fun layout(root: Node, usableWidth: Double, offsetX: Double): Pair<List<Rect>, Int> {
    val rects = mutableListOf<Rect>()
    var maxDepth = 0

    fun visit(node: Node, x: Double, w: Double, depth: Int) {
        if (node !== root) {
            rects += Rect(node.name, x, w, depth, node.count)
            if (depth > maxDepth) maxDepth = depth
        }
        var cx = x
        for (child in node.children.values) {
            val cw = w * child.count.toDouble() / node.count.toDouble()
            if (cw >= 0.1) visit(child, cx, cw, depth + 1)
            cx += cw
        }
    }

    visit(root, offsetX, usableWidth, 0)
    return rects to maxDepth
}

// ─── Colors ──────────────────────────────────────────────────────────────────

// Stable per-name coloring: same function name always gets the same shade.
fun frameColor(name: String, scheme: String): String {
    var h = 0
    for (c in name) h = h * 31 + c.code
    h = h and 0x7FFFFFFF
    val v1 = h % 55         // 0‥54  primary variation
    val v2 = (h / 55) % 80  // 0‥79  secondary variation
    return when (scheme) {
        "mem"  -> "rgb(0,${105 + v2},${200 + v1 / 2})"          // blue–teal (allocation)
        "io"   -> "rgb(${60 + v1},${60 + v2},200)"               // indigo (I/O wait)
        "java",
        "hot"  -> "rgb(${200 + v1},${100 + v2},0)"               // warm orange (CPU / hot)
        else   -> "rgb(${200 + v1},${100 + v2},0)"
    }
}

// ─── SVG ─────────────────────────────────────────────────────────────────────

private const val PAD_X   = 10
private const val PAD_TOP = 30   // title + reset button
private const val PAD_BOT = 26   // info bar
private const val FRAME_H = 15   // row pitch; rect height = FRAME_H - 1

fun generateSvg(root: Node, svgWidth: Int, title: String, colorScheme: String): String {
    val usable = (svgWidth - 2 * PAD_X).toDouble()
    val (rects, maxDepth) = layout(root, usable, PAD_X.toDouble())
    val svgHeight = PAD_TOP + maxDepth * FRAME_H + PAD_BOT
    val total = root.count
    val cx = svgWidth / 2

    val sb = StringBuilder(rects.size * 180)

    // ── header ───────────────────────────────────────────────────────────
    sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$svgWidth" height="$svgHeight">""")
    sb.append("<defs><style>")
    sb.append("text{font-family:ui-monospace,monospace;font-size:11px;pointer-events:none}")
    sb.append("rect.fg{cursor:pointer}rect.fg:hover{stroke:#000;stroke-width:0.5}")
    sb.append("</style></defs>")

    // ── JavaScript ───────────────────────────────────────────────────────
    sb.append("<script><![CDATA[\n")
    sb.append("var T=${total},PX=${PAD_X},U=${usable.toLong()},ZX=${PAD_X},ZW=${usable.toLong()},ZD=0;\n")
    sb.append("var F=[")
    rects.forEachIndexed { idx, r ->
        if (idx > 0) sb.append(',')
        sb.append("[\"${r.name.jsEsc()}\",${"%.1f".format(r.x)},${"%.1f".format(r.w)},${r.depth},${r.count}]")
    }
    sb.append("];\n")
    sb.append(
        """function nfo(i){var f=F[i],p=(f[4]/T*100).toFixed(2);document.getElementById('fgi').textContent=f[0]+' ('+f[4]+' samples, '+p+'%)';}
function clr(){document.getElementById('fgi').textContent='';}
function zoom(i){var f=F[i];ZX=f[1];ZW=f[2];ZD=f[3];draw();}
function reset(){ZX=PX;ZW=U;ZD=0;draw();}
function draw(){
  var sc=U/ZW;
  F.forEach(function(f,i){
    var r=document.getElementById('r'+i),t=document.getElementById('t'+i);
    if(f[3]<ZD||f[1]+f[2]<ZX-0.5||f[1]>ZX+ZW+0.5){r.style.display='none';t.style.display='none';return;}
    r.style.display='';
    var nx=PX+(f[1]-ZX)*sc,nw=f[2]*sc;
    r.setAttribute('x',nx.toFixed(1));r.setAttribute('width',nw.toFixed(1));
    t.setAttribute('x',(nx+3).toFixed(1));
    if(nw<35){t.style.display='none';}else{t.style.display='';var mc=Math.floor(nw/6.5);t.textContent=f[0].length>mc?f[0].slice(0,mc-2)+'..':f[0];}
  });
}"""
    )
    sb.append("\n]]></script>\n")

    // ── background ───────────────────────────────────────────────────────
    sb.append("""<rect width="$svgWidth" height="$svgHeight" fill="#f0f0f0"/>""")

    // ── title ────────────────────────────────────────────────────────────
    sb.append("""<text x="$cx" y="18" text-anchor="middle" style="font-size:15px;font-weight:bold;pointer-events:none">${title.xmlEsc()}</text>""")

    // ── reset button ─────────────────────────────────────────────────────
    sb.append("""<g onclick="reset()" style="cursor:pointer;user-select:none">""")
    sb.append("""<rect x="10" y="5" width="46" height="18" rx="3" fill="#e0e0e0" stroke="#bbb" stroke-width="0.5"/>""")
    sb.append("""<text x="33" y="17" text-anchor="middle" style="font-size:11px;pointer-events:none">Reset</text>""")
    sb.append("</g>\n")

    // ── frames ───────────────────────────────────────────────────────────
    rects.forEachIndexed { idx, r ->
        val fill = frameColor(r.name, colorScheme)
        val ry   = PAD_TOP + (maxDepth - r.depth) * FRAME_H
        val ty   = ry + (FRAME_H - 1) / 2 + 1
        val show = r.w >= 35
        val maxChars = if (show) (r.w / 6.5).toInt() else 0
        val label = if (show) {
            val s = r.name
            (if (s.length > maxChars) s.take(maxChars - 2) + ".." else s).xmlEsc()
        } else ""

        sb.append("""<rect id="r$idx" class="fg" x="${"%.1f".format(r.x)}" y="$ry" width="${"%.1f".format(r.w)}" height="${FRAME_H - 1}" fill="$fill" rx="1" onclick="zoom($idx)" onmouseover="nfo($idx)" onmouseout="clr()"/>""")
        sb.append("""<text id="t$idx" x="${"%.1f".format(r.x + 3)}" y="$ty" dominant-baseline="central"${if (!show) " style=\"display:none\"" else ""}>$label</text>""")
        sb.append('\n')
    }

    // ── info bar ─────────────────────────────────────────────────────────
    val iy = PAD_TOP + maxDepth * FRAME_H + 18
    sb.append("""<text id="fgi" x="$cx" y="$iy" text-anchor="middle" style="font-size:12px;fill:#333;pointer-events:none"></text>""")

    sb.append("\n</svg>")
    return sb.toString()
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

fun String.xmlEsc(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
fun String.jsEsc():  String = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
