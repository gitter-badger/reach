package edu.arizona.sista.bionlp

import edu.arizona.sista.odin._
import edu.arizona.sista.bionlp.mentions._
import edu.arizona.sista.odin.domains.bigmechanism.summer2015.{DarpaFlow, LocalGrounder, Coref}
import RuleReader._
import edu.arizona.sista.processors.Document
import edu.arizona.sista.processors.bionlp.BioNLPProcessor
import scala.collection.immutable.HashSet
import scala.collection.mutable

class ReachSystem(rules: Option[Rules] = None,
                  proc: Option[BioNLPProcessor] = None) {
  import ReachSystem._

  val entityRules = if (rules.isEmpty) readEntityRules() else rules.get.entities
  val modificationRules = if (rules.isEmpty) readModificationRules() else rules.get.modifications
  val eventRules = if (rules.isEmpty) readEventRules() else rules.get.events
  val corefRules = if (rules.isEmpty) readCorefRules() else rules.get.coref
  // initialize actions object
  val actions = new DarpaActions
  // initialize grounder
  val grounder = new LocalGrounder
  // start entity extraction engine
  // this engine extracts all physical entities of interest and grounds them
  val entityEngine = ExtractorEngine(entityRules, actions, grounder.apply)
  // start modification engine
  // this engine extracts modification features and attaches them to the corresponding entity
  val modificationEngine = ExtractorEngine(modificationRules, actions)
  // start event extraction engine
  // This will be our global action for the eventEngine
  val cleanupEvents =
    DarpaFlow(actions.siteSniffer) andThen
    DarpaFlow((mentions: Seq[Mention], state: State) => mentions.filter(m => actions.validArguments(m, state))) andThen
    // This should happen before attempting to keep the most complete Mentions
    DarpaFlow(actions.detectNegations) andThen
    DarpaFlow(actions.detectHypotheses) andThen
    DarpaFlow(actions.splitSimpleEvents) andThen
    DarpaFlow(keepMostCompleteMentions)

  // this engine extracts simple and recursive events and applies coreference
  val eventEngine = ExtractorEngine(eventRules, actions, cleanupEvents.apply)
  // this engine extracts generic mentions that can be anaphora like "it" and tries to resolve them
  val corefEngine = ExtractorEngine(corefRules, actions, cleanupEvents.apply)
  // initialize processor
  val processor = if (proc.isEmpty) new BioNLPProcessor else proc.get
  processor.annotate("something")

  def mkDoc(text: String, docId: String, chunkId: String = ""): Document = {
    val doc = processor.annotate(text, keepText = true)
    val id = if (chunkId.isEmpty) docId else s"${docId}_${chunkId}"
    doc.id = Some(id)
    doc
  }

  def extractFrom(entry: FriesEntry): Seq[BioMention] =
    extractFrom(entry.text, entry.name, entry.chunkId)

  def extractFrom(text: String, docId: String, chunkId: String): Seq[BioMention] = {
    extractFrom(mkDoc(text, docId, chunkId))
  }

  def extractFrom(doc: Document): Seq[BioMention] = {
    require(doc.id.isDefined, "document must have an id")
    require(doc.text.isDefined, "document should keep original text")
    val entities = extractEntitiesFrom(doc)
    val events = extractEventsFrom(doc, entities)
    val unresolved = extractResolvedFrom(doc, events)
    // initialize coref
    val coref = new Coref
    val finalMentions = coref(unresolved,State(unresolved))

    resolveDisplay(finalMentions)
  }

  def extractEntitiesFrom(doc: Document): Seq[BioMention] = {
    // extract entities and ground them
    val entities = entityEngine.extractByType[BioMention](doc)
    // attach modification features to entities
    val modifiedEntities = modificationEngine.extractByType[BioMention](doc, State(entities))
    modifiedEntities
  }

  def extractEventsFrom(doc: Document, ms: Seq[BioMention]): Seq[BioMention] = {
    val mentions = eventEngine.extractByType[BioMention](doc, State(ms))
    // clean modified entities
    // remove ModificationTriggers
    // Make sure we don't have any "ModificationTrigger" Mentions
    val validMentions = mentions.filterNot(_ matches "ModificationTrigger")
    // handle multiple Negation modifications
    handleNegations(validMentions)
    validMentions
  }

  //
  def extractResolvedFrom(doc: Document, ms: Seq[BioMention]): Seq[BioMention] = {
    val mentions = corefEngine.extractByType[BioMention](doc, State(ms))
    mentions
  }
}

object ReachSystem {

  // a simple, imperfect way of removing incomplete Mentions
  def pruneMentions(ms: Seq[BioMention]): Seq[BioMention] = {

    val (events, nonEvents) = ms.partition(_.isInstanceOf[BioEventMention])
    // We need to remove underspecified EventMentions of near-duplicate groupings
    // (ex. same phospho, but one is missing a site)
    val mentionGroupings =
      events.map(_.asInstanceOf[BioEventMention]).groupBy(m => (m.trigger, m.label))

    // remove incomplete mentions
    val completeEventMentions =
      for ((k, ems) <- mentionGroupings) yield {
        val maxSize: Int = ems.map(_.arguments.size).max
        val filteredEMs = ems.filter(m => m.arguments.size == maxSize)
        filteredEMs
      }
    nonEvents ++ completeEventMentions.flatten.toSeq
  }

  // Removal of incomplete Mentions with special care to Regulations
  // calls pruneMentions after attending to Regulations
  def keepMostCompleteMentions(ms: Seq[Mention], state: State): Seq[Mention] = {

    // Move any Negation modification on a Regulation's controlled arg to the Regulation
    def promoteNegationModifications(parent: BioMention, child: BioMention): Unit = {
     val (negs, other) = child.modifications.partition(_.isInstanceOf[Negation])
      parent.modifications ++= negs
      // remove Negation modifications from child
      child.modifications = other
    }

    // Regulations require special attention
    val (complexRegs, other: Seq[Mention]) = ms.partition(_ matches "ComplexEvent")
    // We need to keep track of what SimpleEvents to remove from the state
    // whenever another is used to replace it as a "controlled" arg in a Regulation
    var toRemove = mutable.Set[Mention]()
    // Check each Regulation to see if there are any "more complete" Mentions
    // for the controlled available in the state
    val correctedRegulations =
      for {reg <- complexRegs
           // it should only ever have ONE controlled
           controlled =
           reg.arguments("controlled")
             .head
             // treat it as a BioEventMention to simplify filtering
             .asInstanceOf[BioEventMention]
           // how many args does the controlled Mention have?
           argCount = controlled.arguments.size
      } yield {
        // Are there any "more complete" SimpleEvents in the State
        // that are candidates to replace the current "controlled" arg?
        val replacementCandidates: Seq[BioEventMention] =
          state.mentionsFor(reg.sentence, controlled.tokenInterval, controlled.label)
            // If the label is the same, these MUST be BioEventMentions (i.e SimpleEvents)
            .map(_.asInstanceOf[BioEventMention])
            .filter(m => m.arguments.size > argCount && (m.trigger == controlled.trigger))
        // Do we have any "more complete" Mentions to substitute for the controlled?
        replacementCandidates match {
          // Use the current reg, since there aren't any "more complete"
          // candidate Mentions for the controlled
          case Nil => Seq(reg)
          // There are some more complete candidates for the controlled arg...
          case candidates =>
            // For each "more complete" SimpleEvent, create a new Regulation...
              for (r <- candidates) yield {
                reg match {
                  // Is the reg we're replacing a BioRelationMention?
                  case relReg: BioRelationMention =>
                    val updatedArgs = relReg.arguments updated("controlled", Seq(r))
                    val junk = relReg.arguments("controlled").head.toBioMention
                    // Keep track of what we need to get rid of...
                    toRemove += junk
                    // Create the "more complete" BioRelationMentions
                    val moreCompleteReg =
                      new BioRelationMention(
                        relReg.labels,
                        updatedArgs,
                        relReg.sentence,
                        relReg.document,
                        relReg.keep,
                        relReg.foundBy)
                    // Move Negation modifications from controlled to Reg.
                    promoteNegationModifications(moreCompleteReg, r)
                    // return the new Regulation
                    moreCompleteReg
                  // Is the Regulation we're replacing a BioEventMention?
                  case eventReg: BioEventMention =>
                    val updatedArgs = eventReg.arguments updated("controlled", Seq(r))
                    val junk = eventReg.arguments("controlled").head.toBioMention
                    // Keep track of what we need to get rid of...
                    toRemove += junk
                    // Create the "more complete" BioEventMentions
                    val moreCompleteReg =
                      new BioEventMention(
                        eventReg.labels,
                        eventReg.trigger,
                        updatedArgs,
                        eventReg.sentence,
                        eventReg.document,
                        eventReg.keep,
                        eventReg.foundBy)
                    // Move Negation modifications from controlled to Reg.
                    promoteNegationModifications(eventReg, r)
                    // return the new Regulation
                    moreCompleteReg
                }
              }
        }
      }

    // Remove any "controlled" Mentions we discarded
    val cleanMentions =
      (correctedRegulations.flatten ++ other)
        .filter(m => !toRemove.contains(m))
        .map(_.toBioMention)

    val (regs, nonRegs) = cleanMentions.partition(_ matches "ComplexEvent")
    // Run the old pruning code first on regulations
    val remainingRegs = pruneMentions(regs)
    // We don't want to accidentally filter out any SimpleEvents
    // that are valid arguments to the filtered Regs
    val keepThese:Seq[Mention] =
      remainingRegs.flatMap(_.arguments.values)
        .flatten
        .filter(m => m matches "SimpleEvent")
        .distinct
    // Return the filtered with the arguments to the Regulations
    val remainingNonRegs = pruneMentions(nonRegs) ++ keepThese.map(_.toBioMention)
    (remainingRegs ++ remainingNonRegs).distinct
  }

  // Alter Negation modifications in-place
  def handleNegations(ms: Seq[BioMention]): Unit = {
    ms foreach { m =>
      val (negMods, other) = m.modifications.partition(_.isInstanceOf[Negation])
      val negationModifications = negMods.map(_.asInstanceOf[Negation])

      // count the negations
      negationModifications match {
        // 0 or 1 Neg modifications means no change...
        case noChange if noChange.size <= 1 => ()
        // if we have an even number of Negations, remove them all
        case pos if pos.size % 2 == 0 =>
          m.modifications = other
        // if we have an odd number, report only the first...
        case neg if neg.size % 2 != 0 =>
          val singleNeg =
            negationModifications
              .toSeq
              .sortBy(_.evidence.tokenInterval)
              .head
          m.modifications = other + singleNeg
      }
    }
  }

  // This function should set the right displayMention for each mention.
  // By default the displayMention is set to the main label of the mention,
  // so sometimes it may not require modification
  def resolveDisplay(ms: Seq[BioMention]): Seq[BioMention] = {
    // let's do a first attempt, using only grounding info
    // this is useful for entities that do not participate in events
    for(m <- ms) {
      m match {
        case em:TextBoundMention with Display with Grounding =>
          if(m.isGrounded) {
            if(m.xref.get.namespace.contains("interpro"))
              m.displayLabel = "Family"
            else if(m.xref.get.namespace.contains("uniprot"))
              m.displayLabel = "Protein"
          }
        case _ => // nothing to do
      }
    }

    // now let's try to disambiguate Gene_or_gene_product that participate in events
    for(m <- ms) {
      if(m.labels.contains("Event")) {
        val parents = new HashSet[String]
        resolveDisplayForArguments(m, parents)
      }
    }

    // last resort: displayLabel is set to the default value
    ms.foreach(m => if(m.displayLabel == null) m.displayLabel = m.label)

    ms
  }

  def resolveDisplayForArguments(em:BioMention, parents:Set[String]) {
    if(em.labels.contains("Event")) { // recursively traverse the arguments of events
      val newParents = new mutable.HashSet[String]()
      newParents ++= parents
      newParents += em.label
      em.arguments.values.foreach(ms => ms.foreach( m => {
        resolveDisplayForArguments(m.asInstanceOf[BioMention], newParents.toSet)
      }))
    } else if(em.labels.contains("Gene_or_gene_product")) { // we only need to disambiguate these
      if(em.xref.isDefined && em.xref.get.namespace.contains("interpro")) {
        // found a Family incorrectly labeled as protein
        em.displayLabel = "Family"
      } else if(parents.contains("Transcription")) {
        // found a Gene
        em.displayLabel = "Gene"
      } else {
        em.displayLabel = "Protein"
      }
    }
  }

}
