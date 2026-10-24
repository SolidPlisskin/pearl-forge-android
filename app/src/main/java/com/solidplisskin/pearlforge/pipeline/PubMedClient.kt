package com.solidplisskin.pearlforge.pipeline

import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/** A paper found on PubMed. */
data class PubMedPaper(
    var pmid: String,
    var title: String = "",
    var journal: String = "",
    var year: String = "",
    var authors: MutableList<String> = mutableListOf(),
    var abstract: String = "",
    var doi: String? = null,
) {
    val url: String get() = "https://pubmed.ncbi.nlm.nih.gov/$pmid/"

    /** "Smith J, Lee K, Park S, et al." */
    val authorLine: String
        get() = if (authors.size > 3) authors.take(3).joinToString(", ") + ", et al." else authors.joinToString(", ")
}

/** Searches PubMed through the National Library of Medicine's free E-utilities service. No key is needed. */
object PubMedClient {
    var base = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils"

    /** The most relevant papers for a PubMed query, with their abstracts. */
    suspend fun search(query: String, maxResults: Int = 6): List<PubMedPaper> {
        val ids = searchIds(query, maxResults.coerceIn(1, 10))
        if (ids.isEmpty()) return emptyList()
        return fetch(ids)
    }

    private suspend fun searchIds(query: String, maxResults: Int): List<String> {
        val url = "$base/esearch.fcgi".toHttpUrl().newBuilder()
            .addQueryParameter("db", "pubmed")
            .addQueryParameter("term", query)
            .addQueryParameter("retmax", maxResults.toString())
            .addQueryParameter("sort", "relevance")
            .addQueryParameter("retmode", "json")
            .addQueryParameter("tool", "PearlForge")
            .build()
        val json = parseJson(PipelineHttp.send(Request.Builder().url(url).build(), "PubMed search failed")).asObject()
        return json?.obj("esearchresult")?.arr("idlist").orEmpty().mapNotNull { it.stringValue() }
    }

    private suspend fun fetch(ids: List<String>): List<PubMedPaper> {
        val url = "$base/efetch.fcgi".toHttpUrl().newBuilder()
            .addQueryParameter("db", "pubmed")
            .addQueryParameter("id", ids.joinToString(","))
            .addQueryParameter("rettype", "abstract")
            .addQueryParameter("retmode", "xml")
            .addQueryParameter("tool", "PearlForge")
            .build()
        val papers = parse(PipelineHttp.send(Request.Builder().url(url).build(), "PubMed lookup failed"))
        // Keep PubMed's relevance order.
        return ids.mapNotNull { id -> papers.firstOrNull { it.pmid == id } }
    }

    /** Reads the parts of PubMed's article XML that Pearl Forge shows. Inline markup such as <i> folds into its parent. */
    fun parse(data: ByteArray): List<PubMedPaper> {
        val papers = mutableListOf<PubMedPaper>()
        val handler = object : DefaultHandler() {
            var current: PubMedPaper? = null
            val path = ArrayDeque<String>()
            val texts = ArrayDeque<StringBuilder>()
            var lastName = ""
            var initials = ""
            var articleIdType = ""

            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                path.addLast(qName)
                texts.addLast(StringBuilder())
                when (qName) {
                    "PubmedArticle" -> current = PubMedPaper("")
                    "Author" -> { lastName = ""; initials = "" }
                    "ArticleId" -> articleIdType = attributes.getValue("IdType") ?: ""
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                texts.lastOrNull()?.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                val raw = texts.removeLast().toString()
                texts.lastOrNull()?.append(raw)
                val parent = if (path.size >= 2) path[path.size - 2] else ""
                path.removeLast()
                val paper = current ?: return
                val value = raw.split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
                when {
                    qName == "PMID" && parent == "MedlineCitation" && paper.pmid.isEmpty() -> paper.pmid = value
                    qName == "ArticleTitle" -> paper.title = value
                    qName == "Title" && parent == "Journal" -> paper.journal = value
                    qName == "Year" && parent == "PubDate" -> paper.year = value
                    qName == "MedlineDate" && parent == "PubDate" && paper.year.isEmpty() -> paper.year = value.take(4)
                    qName == "AbstractText" -> paper.abstract += (if (paper.abstract.isEmpty()) "" else " ") + value
                    qName == "LastName" -> lastName = value
                    qName == "Initials" -> initials = value
                    qName == "Author" && lastName.isNotEmpty() -> paper.authors.add(if (initials.isEmpty()) lastName else "$lastName $initials")
                    qName == "ArticleId" && articleIdType == "doi" && paper.doi == null -> paper.doi = value
                    qName == "PubmedArticle" -> {
                        if (paper.pmid.isNotEmpty()) papers.add(paper)
                        current = null
                    }
                }
            }
        }
        val factory = SAXParserFactory.newInstance()
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        val reader = factory.newSAXParser().xmlReader
        // PubMed's XML names a DTD on the web; never fetch it.
        reader.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        reader.contentHandler = handler
        runCatching { reader.parse(InputSource(ByteArrayInputStream(data))) }
        return papers
    }
}
