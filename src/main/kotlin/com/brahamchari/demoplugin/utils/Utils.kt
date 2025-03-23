package com.brahamchari.demoplugin.utils

import javax.xml.xpath.XPathConstants
import org.w3c.dom.Document
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathFactory
import org.xml.sax.InputSource

object Utils {

    fun cleanHierarchyDump(xmlDump: String): List<Map<String, String>> {
        val doc: Document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(xmlDump)))

        val xPath = XPathFactory.newInstance().newXPath()
        val nodeList = xPath.evaluate("//node", doc, XPathConstants.NODESET) as org.w3c.dom.NodeList

        val importantNodes = mutableListOf<Map<String, String>>()

        for (i in 0 until nodeList.length) {
            val node = nodeList.item(i)
            val attributes = listOf("resource-id", "class", "content-desc", "clickable", "bounds", "text", "checked", "focused", "selected")

            val nodeData = attributes.associateWith { attr ->
                node.attributes?.getNamedItem(attr)?.nodeValue ?: ""
            }

            // Add only non-empty nodes
            if (nodeData.any { it.value.isNotBlank() }) {
                importantNodes.add(nodeData)
            }
        }

        return importantNodes
    }
}