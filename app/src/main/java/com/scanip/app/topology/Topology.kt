package com.scanip.app.topology

enum class NodeKind { ROUTER, SWITCH, UNMANAGED, SUBNET, DEVICE }

data class TopoNode(
    val id: String,
    val label: String,
    val sublabel: String?,
    val kind: NodeKind,
    /** Set when the node is a scanned device (opens its detail page). */
    val deviceIp: String? = null,
)

/** An undirected link. [portA]/[portB] name the port at each end, when known. */
data class TopoLink(val a: String, val b: String, val portA: String? = null, val portB: String? = null, val inferred: Boolean = false)

data class Topology(
    val nodes: Map<String, TopoNode>,
    val links: List<TopoLink>,
    val root: String?,
    /** Device IP -> "Switch name · port" for devices placed on a switch port. */
    val attachments: Map<String, String>,
) {
    /** Breadth-first spanning tree from [root]: parent -> ordered children, plus the link used. */
    fun tree(): Tree {
        val children = LinkedHashMap<String, MutableList<String>>()
        val viaLink = HashMap<String, TopoLink>()
        val start = root ?: return Tree(null, children, viaLink, nodes.keys.toList())
        val adj = HashMap<String, MutableList<TopoLink>>()
        for (l in links) {
            adj.getOrPut(l.a) { mutableListOf() } += l
            adj.getOrPut(l.b) { mutableListOf() } += l
        }
        val seen = hashSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val next = adj[n].orEmpty().map { l -> (if (l.a == n) l.b else l.a) to l }
                .filter { it.first !in seen && it.first in nodes }
                .sortedWith(compareBy({ nodes[it.first]!!.kind.ordinal }, { sortKey(nodes[it.first]!!) }))
            for ((c, l) in next) {
                if (!seen.add(c)) continue
                children.getOrPut(n) { mutableListOf() } += c
                viaLink[c] = l
                queue += c
            }
        }
        return Tree(start, children, viaLink, nodes.keys.filter { it !in seen })
    }

    private fun sortKey(n: TopoNode): Long =
        n.deviceIp?.let { com.scanip.app.net.IpUtils.toLong(it) } ?: Long.MAX_VALUE

    data class Tree(
        val root: String?,
        val children: Map<String, List<String>>,
        val viaLink: Map<String, TopoLink>,
        val unreachable: List<String>,
    ) {
        /** Port label to show on the edge into [child], seen from the parent side and the child side. */
        fun portLabel(parent: String, child: String): String? {
            val l = viaLink[child] ?: return null
            val (p, c) = if (l.a == parent) l.portA to l.portB else l.portB to l.portA
            return listOfNotNull(p, c?.let { "↔ $it" }).joinToString(" ").ifEmpty { null }
        }
    }
}
