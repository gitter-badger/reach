package edu.arizona.sista.reach.apis

import java.util.{Date, Map => JMap}

import com.typesafe.config.ConfigFactory
import edu.arizona.sista.odin.Mention
import edu.arizona.sista.reach._
import edu.arizona.sista.reach.extern.export.fries.FriesOutput
import edu.arizona.sista.reach.extern.export.indexcards.IndexCardOutput
import edu.arizona.sista.reach.nxml.{FriesEntry, NxmlReader}
import edu.arizona.sista.reach.extern.export.IncrementingId

import scala.collection.JavaConverters._

/**
  * External interface class to accept and process text strings and NXML documents,
  * returning REACH results in either FRIES or IndexCard JSON format.
  *   Last Modified: Add output format argument to API calls.
  */
object ApiRuler {
  // a response is a heterogeneous Java Map from String to either String or Boolean
  type Response = JMap[String, Any]

  // shared counter for request numbering
  val apiRequestCntr = new IncrementingId()

  // some internal defaults for parameters required in lower layers
  private val prefix = "api"
  private val suffix = "Reach"

  // read configuration to determine processing parameters
  val config = ConfigFactory.load()
  val ignoreSections = config.getStringList("nxml2fries.ignoreSections").asScala.toList
  val encoding = config.getString("encoding")

  val reader = new NxmlReader(ignoreSections)

  val reach = new ReachSystem               // start reach system

  val friesOutputter = new FriesOutput         // converts results to json in FRIES format
  val indexCardOutputter = new IndexCardOutput // converts results to json in Index Card format

  /** Extracts raw text from given nxml and returns a response with all the mentions. */
  def annotateNxml(nxml: String, outFormat: String): Response = {
    val entries = reader.readNxml(nxml, prefix)
    mkResponse(entries, entries flatMap extractMentions, outFormat)
  }

  /** Annotates some text by converting it to a FriesEntry and calling annotateEntry().
      Uses fake document ID and chunk ID. */
  def annotateText(text: String, outFormat: String): Response = {
    annotateEntry(FriesEntry(prefix, suffix, "NoSection", "NoSection", false, text), outFormat)
  }

  /** annotates some text by converting it to a FriesEntry and calling annotateEntry(). */
  def annotateText(text: String, docId: String=prefix, chunkId: String=suffix,
                   outFormat: String="fries"): Response =
  {
    annotateEntry(FriesEntry(docId, chunkId, "NoSection", "NoSection", false, text), outFormat)
  }


  /** Annotates a single FriesEntry and returns a response. */
  def annotateEntry(entry: FriesEntry, outFormat: String): Response =
    mkResponse(Seq(entry), extractMentions(entry), outFormat)

  /** Gets a sequence of FriesEntries and their extracted mentions (by name)
      and constructs a Response. */
  def mkResponse(entries: Seq[FriesEntry], lazyMentions: => Seq[Mention],
                 outFormat: String): Response =
  {
    try {
      val startTime = new Date()
      val mentions = lazyMentions
      val endTime = new Date()
      val requestId = s"${prefix}${apiRequestCntr.genNextId()}"
      val json = if (outFormat == "indexcard")
        indexCardOutputter.toJSON(requestId, mentions, entries, startTime, endTime, prefix)
      else
        friesOutputter.toJSON(requestId, mentions, entries, startTime, endTime, prefix)
      Map("resultJson" -> json, "hasError" -> false).asJava
    } catch {
      case e: Exception => Map("resultJson" -> "", "hasError" -> true, "errorMessage" -> e.getMessage).asJava
    }
  }

  /** Extracts the mentions from a FriesEntry to a list. */
  def extractMentions(entry: FriesEntry): Seq[Mention] =
    reach.extractFrom(entry).toList

}
