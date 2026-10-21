package com.vibeplayer.net

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/** Small namespace-aware DOM helpers for the WebDAV and DLNA replies (P13). */
internal object Xml {
    fun parse(text: String): Element? = try {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // No external entities or DTDs from a server.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        f.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(text))).documentElement
    } catch (e: Exception) {
        null
    }

    fun children(e: Element, local: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = e.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && (n.localName ?: n.nodeName.substringAfter(':')) == local) out += n as Element
        }
        return out
    }

    fun child(e: Element, local: String): Element? = children(e, local).firstOrNull()

    /** All descendants with this local name. */
    fun all(e: Element, local: String): List<Element> {
        val nodes = e.getElementsByTagNameNS("*", local)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    fun text(e: Element?, local: String): String? = e?.let { child(it, local)?.textContent?.trim() }
}
