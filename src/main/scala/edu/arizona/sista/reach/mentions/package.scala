package edu.arizona.sista.reach

import edu.arizona.sista.coref.AntecedentSelector
import edu.arizona.sista.reach.context.Context
import edu.arizona.sista.odin._

package object mentions {
  type BioMention = Mention with Modifications with Grounding with Display with Context
  type CorefMention = BioMention with Anaphoric
  type Link = (Seq[CorefMention], AntecedentSelector) => Seq[CorefMention]

  implicit class MentionOps(mention: Mention) {
    def toBioMention: BioMention = mention match {
      case m: BioMention => m

      case m: TextBoundMention =>
        new BioTextBoundMention(
          m.labels,
          m.tokenInterval,
          m.sentence,
          m.document,
          m.keep,
          m.foundBy)

      case m: EventMention =>
        new BioEventMention(
          m.labels,
          m.trigger,
          convertArguments(m.arguments),
          m.sentence,
          m.document,
          m.keep,
          m.foundBy)

      case m: RelationMention =>
        new BioRelationMention(
          m.labels,
          convertArguments(m.arguments),
          m.sentence,
          m.document,
          m.keep,
          m.foundBy)
    }

    def toCorefMention: CorefMention = mention match {
      case m: CorefMention => m

      case m: BioTextBoundMention => {
        val tbm = new CorefTextBoundMention(
          m.labels,
          m.tokenInterval,
          m.sentence,
          m.document,
          m.keep,
          m.foundBy
        )
        CorefMention.copyAttachments(m, tbm)
        tbm
      }
      case m: BioEventMention => {
        val ev = new CorefEventMention(
          m.labels,
          m.trigger,
          corefArguments(m.arguments),
          m.sentence,
          m.document,
          m.keep,
          m.foundBy
        )
        CorefMention.copyAttachments(m, ev)
        ev
      }

      case m: BioRelationMention => {
        val rel = new CorefRelationMention(
          m.labels,
          corefArguments(m.arguments),
          m.sentence,
          m.document,
          m.keep,
          m.foundBy
        )
        CorefMention.copyAttachments(m, rel)
        rel
      }

      case m: Mention => m.toBioMention.toCorefMention
    }


    def antecedentOrElse [M >: CorefMention] (default: => M): M = {
      mention.toCorefMention.antecedent.getOrElse(default).asInstanceOf[CorefMention]
    }


    /** Return the named argument from the arguments of the given mention. */
    def namedArguments (argName:String): Option[Seq[Mention]] = {
      val crm = this.toCorefMention.antecedentOrElse(this.toCorefMention)
      val named = crm.arguments.get(argName)
      if (named.isDefined)
        Some(named.get.map(m => m.toCorefMention.antecedentOrElse(m.toCorefMention)))
      else None
    }

    def controlledArgs  (): Option[Seq[Mention]] = namedArguments("controlled")
    def controllerArgs  (): Option[Seq[Mention]] = namedArguments("controller")
    def destinationArgs (): Option[Seq[Mention]] = namedArguments("destination")
    def themeArgs       (): Option[Seq[Mention]] = namedArguments("theme")
    def siteArgs        (): Option[Seq[Mention]] = namedArguments("site")
    def sourceArgs      (): Option[Seq[Mention]] = namedArguments("source")


    private def convertArguments(
      arguments: Map[String, Seq[Mention]]
    ): Map[String, Seq[BioMention]] = arguments.transform {
      case (k, v) => v.map(_.toBioMention)
    }

    private def corefArguments(
      arguments: Map[String, Seq[Mention]]
    ): Map[String,Seq[CorefMention]] = arguments.transform {
      case (k, v) => v.map(_.toCorefMention)
    }

  }

}
