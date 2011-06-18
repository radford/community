/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher

import commands._
import scala.util.parsing.combinator._
import org.neo4j.graphdb.Direction

class CypherParser extends JavaTokenParsers {
  def ignoreCase(str:String): Parser[String] = ("""(?i)\Q""" + str + """\E""").r

  def query: Parser[Query] = start ~ opt(matching) ~ opt(where) ~ returns ~ opt(order) ~ opt(skip) ~ opt(limit) ^^ {
    case start ~ matching ~ where ~ returns ~ sort ~ None ~ None => Query(returns._1, start, matching, where, returns._2, sort, None)
    case start ~ matching ~ where ~ returns ~ sort ~ skip ~ limit => Query(returns._1, start, matching, where, returns._2, sort, Some(Slice(skip, limit)))
  }

  def skip: Parser[Int] = ignoreCase("skip") ~> positiveNumber ^^ { case startAt => startAt.toInt }

  def limit: Parser[Int] = ignoreCase("limit") ~> positiveNumber ^^ { case count => count.toInt }

  def start: Parser[Start] = ignoreCase("start") ~> repsep(nodeByIds | nodeByIndex | nodeByIndexQuery | relsByIds | relsByIndex, ",") ^^ (Start(_: _*))

  def matching: Parser[Match] = ignoreCase("match") ~> rep1sep(relatedTo, ",") ^^ { case matching:List[List[Pattern]] => Match(matching.flatten: _*) }

  def relatedTo: Parser[List[Pattern]] = relatedHead ~ rep1(relatedTail) ^^ {
    case head ~ tails => {
      val namer = new NodeNamer
      var last = namer.name(head)
      val list = tails.map((item) => {
        val back = item._1
        val relName = item._2
        val relType = item._3
        val forward = item._4
        val end = namer.name(item._5)

        val result: Pattern = RelatedTo(last, end, relName, relType, getDirection(back, forward))

        last = end

        result
      })

      list
    }
  }

  def relatedHead:Parser[Option[String]] = "(" ~> opt(identity) <~ ")" ^^ { case name => name }

  def relatedTail = opt("<") ~ "-" ~ opt("[" ~> (relationshipInfo1 | relationshipInfo2) <~ "]") ~ "-" ~ opt(">") ~ "(" ~ opt(identity) ~ ")" ^^ {
    case back ~ "-" ~ relInfo ~ "-" ~ forward ~ "(" ~ end ~ ")" => relInfo match {
      case Some(x) => (back, x._1, x._2, forward, end)
      case None => (back, None, None, forward, end)
    }
  }

  def relationshipInfo1 = opt(identity <~ ",") ~ ":" ~ identity ^^ {
    case relName ~ ":" ~ relType => (relName, Some(relType))
  }

  def relationshipInfo2 = identity ^^ {
    case relName => (Some(relName), None)
  }

  def nodeByIds = identity ~ "=" ~ "(" ~ rep1sep(wholeNumber, ",") ~ ")" ^^ {
    case varName ~ "=" ~ "(" ~ id ~ ")" => NodeById(varName, id.map(_.toLong).toSeq: _*)
  }

  def nodeByIndex = identity ~ "=" ~ "(" ~ identity ~ "," ~ identity ~ "," ~ string ~ ")" ^^ {
    case varName ~ "=" ~ "(" ~ index ~ "," ~ key ~ "," ~ value ~ ")" => NodeByIndex(varName, index, key, value)
  }

  def nodeByIndexQuery = identity ~ "=" ~ "(" ~ identity ~ "," ~ string ~ ")" ^^ {
    case varName ~ "=" ~ "(" ~ index ~ "," ~ query ~ ")" => NodeByIndexQuery(varName, index, query)
  }


  def relsByIds = identity ~ "=" ~ "<" ~ rep1sep(wholeNumber, ",") ~ ">" ^^ {
    case varName ~ "=" ~ "<" ~ id ~ ">" => RelationshipById(varName, id.map(_.toLong).toSeq: _*)
  }

  def relsByIndex = identity ~ "=" ~ "<" ~ identity ~ "," ~ identity ~ "," ~ string ~ ">" ^^ {
    case varName ~ "=" ~ "<" ~ index ~ "," ~ key ~ "," ~ value ~ ">" => RelationshipByIndex(varName, index, key, value)
  }

  def desc:Parser[String] = ignoreCase("descending") | ignoreCase("desc")

  def asc:Parser[String] = ignoreCase("ascending") | ignoreCase("asc")

  def ascOrDesc:Parser[Boolean] = opt(asc | desc) ^^ {
    case None => true
    case Some(txt) => txt.toLowerCase.startsWith("a")
  }

  def sortItem :Parser[SortItem] = (nullablePropertyOutput | propertyOutput | nodeOutput ) ~ ascOrDesc ^^ {
    case returnItem ~ reverse => {
      returnItem match {
        case x : EntityOutput => throw new SyntaxError("Cannot ORDER BY on nodes or relationships")
        case _ => SortItem(returnItem, reverse)
      }
    }
  }

  def order: Parser[Sort] = ignoreCase("order by")  ~> rep1sep(sortItem, ",") ^^
    {
      case items => Sort(items:_*)
    }

  def aggregate:Parser[AggregationItem] = count

  def returnItem:Parser[ReturnItem] = nullablePropertyOutput | relationshipTypeOutput | propertyOutput | nodeOutput

  def returns: Parser[(Return, Option[Aggregation])] = ignoreCase("return") ~> rep1sep((aggregate | returnItem), ",") ^^
    { case items => {
      val list = items.filter(_.isInstanceOf[AggregationItem]).map(_.asInstanceOf[AggregationItem])

      (
        Return(items.filter(!_.isInstanceOf[AggregationItem]): _*),
        list match {
          case List() => None
          case _ => Some(Aggregation(list : _*))
        }
      )
    }}

  def nodeOutput:Parser[ReturnItem] = identity ^^ { EntityOutput(_) }

  def propertyOutput:Parser[ReturnItem] = identity ~ "." ~ identity ^^
    { case c ~ "." ~ p => PropertyOutput(c,p) }

  def nullablePropertyOutput:Parser[ReturnItem] = identity ~ "." ~ identity ~ "?" ^^
    { case c ~ "." ~ p ~ "?" => NullablePropertyOutput(c,p) }

  def relationshipTypeOutput:Parser[ReturnItem] = identity <~ ":TYPE" ^^
    { case c => RelationshipTypeOutput(c) }

  def count:Parser[AggregationItem] = ignoreCase("count") ~>"(" ~> ("*" | returnItem) <~ ")" ^^
    {
      case "*" => CountStar()
      case x:ReturnItem => Count(x)
    }

  def where: Parser[Clause] = ignoreCase("where") ~> clause ^^ { case klas => klas }

  def clause: Parser[Clause] = (orderedComparison | not | notEquals | equals | regexp | parens) * (
    ignoreCase("and") ^^^ { (a: Clause, b: Clause) => And(a, b) } |
    ignoreCase("or") ^^^ {  (a: Clause, b: Clause) => Or(a, b) })

  def regexp: Parser[Clause] = value ~ "=~" ~ regularLiteral ^^ {
    case a ~ "=~" ~ b => RegularExpression(a, stripQuotes(b))
  }

  def regularLiteral = ("/"+"""([^"\p{Cntrl}\\]|\\[\\/bfnrt]|\\u[a-fA-F0-9]{4})*"""+"/").r

  def parens: Parser[Clause] = "(" ~> clause <~ ")"

  def equals: Parser[Clause] = value ~ "=" ~ value ^^ {
    case l ~ "=" ~ r => Equals(l,r)
  }

  def orderedComparison:Parser[Clause] = (lessThanOrEqual | greaterThanOrEqual | lessThan | greaterThan)

  def lessThan: Parser[Clause] = value ~ "<" ~ value ^^ {
    case l ~ "<" ~ r => LessThan(l,r)
  }

  def greaterThan: Parser[Clause] = value ~ ">" ~ value ^^ {
    case l ~ ">" ~ r => GreaterThan(l,r)
  }

  def lessThanOrEqual: Parser[Clause] = value ~ "<=" ~ value ^^ {
    case l ~ "<=" ~ r => LessThanOrEqual(l,r)
  }

  def greaterThanOrEqual: Parser[Clause] = value ~ ">=" ~ value ^^ {
    case l ~ ">=" ~ r => GreaterThanOrEqual(l,r)
  }

  def notEquals: Parser[Clause] = value ~ "<>" ~ value ^^ {
    case l ~ "<>" ~ r => Not(Equals(l,r))
  }

  def not:Parser[Clause] = ignoreCase("not") ~ "(" ~ clause ~ ")" ^^ {
    case not ~ "(" ~ inner ~ ")" => Not(inner)
  }

  def value: Parser[Value] = (boolean | relationshipType | property | stringValue | decimal)

  def property: Parser[Value] = identity ~ "." ~ identity ^^ {  case v ~ "." ~ p => PropertyValue(v,p) }

  def stringValue: Parser[Value] = string ^^ { case str => Literal(str) }

  def decimal: Parser[Value] = decimalNumber ^^ { case num => Literal(num.toLong) }

  def boolean: Parser[Value] = (trueX | falseX)
  def trueX: Parser[Value] = ignoreCase("true") ^^ { case str => Literal(true) }
  def falseX: Parser[Value] = ignoreCase("false") ^^ { case str => Literal(false) }

  def relationshipType: Parser[Value] = identity <~ ":TYPE" ^^ {  case v => RelationshipTypeValue(v) }

  def identity:Parser[String] = (ident | escapedIdentity)

  def escapedIdentity:Parser[String] = ("`(``|[^`])*`").r ^^ { case str => stripQuotes(str).replace("``", "`") }

  private def stripQuotes(s: String) = s.substring(1, s.length - 1)

  def positiveNumber: Parser[String] = """\d+""".r

  def string: Parser[String] = (stringLiteral | apostropheString)  ^^ { case str => stripQuotes(str) }

  def apostropheString:Parser[String] = ("\'"+"""([^'\p{Cntrl}\\]|\\[\\/bfnrt]|\\u[a-fA-F0-9]{4})*"""+"\'").r

  private def getDirection(back:Option[String], forward:Option[String]):Direction =
    (back.nonEmpty, forward.nonEmpty) match {
      case (true,false) => Direction.INCOMING
      case (false,true) => Direction.OUTGOING
      case (false,false) => Direction.BOTH
      case (true,true) => Direction.BOTH
    }

  @throws(classOf[SyntaxError])
  def parse(queryText: String): Query =
    parseAll(query, queryText) match {
      case Success(r, q) => r
      case NoSuccess(message, input) => message match {
        case "string matching regex `-?\\d+' expected but `)' found" => throw new SyntaxError("Last element of list must be a value")
        case _ => throw new SyntaxError(message)
      }
    }

  class NodeNamer {
    var lastNodeNumber = 0

    def name(s:Option[String]):String = s match {
      case None => {
        lastNodeNumber += 1
        "___NODE" + lastNodeNumber
      }
      case Some(x) => x
    }
  }
}