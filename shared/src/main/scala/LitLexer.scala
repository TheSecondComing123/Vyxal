package vyxal

import scala.language.strictEquality

import vyxal.impls.Elements
import vyxal.Token.*
import vyxal.TokenType.*

import scala.collection.mutable
import scala.collection.mutable.{ListBuffer, Queue}
import scala.util.matching.Regex
import scala.util.parsing.combinator.*

object LitLexer:
  private val endKeywords = List(
    "endfor",
    "end-for",
    "endwhile",
    "end-while",
    "endlambda",
    "end-lambda",
    "end",
  )

  private val branchKeywords = List(
    ":",
    ",",
    "else",
    "elif",
    "else-if",
    "body",
    "do",
    "branch",
    "->",
    "then",
    "in",
    "using",
  )

  /** Map keywords to their token types */
  private val keywords = Map(
    "close-all" -> TokenType.StructureAllClose
  )

  private val lambdaOpeners = Map(
    "lambda" -> StructureType.Lambda,
    "lam" -> StructureType.Lambda,
  )

  /** Keywords for opening structures. Has to be a separate map because while
    * all of them have the same [[TokenType]], they have different values
    * depending on the kind of structure
    */
  private val structOpeners = Map(
    // These can't go in the big map, because that's autogenerated
    "?" -> StructureType.Ternary,
    "?->" -> StructureType.Ternary,
    "if" -> StructureType.IfStatement,
    "for" -> StructureType.For,
    "do-to-each" -> StructureType.For,
    "while" -> StructureType.While,
    "is-there?" -> StructureType.DecisionStructure,
    "does-exist?" -> StructureType.DecisionStructure,
    "is-there" -> StructureType.DecisionStructure,
    "does-exist" -> StructureType.DecisionStructure,
    "any-in" -> StructureType.DecisionStructure,
    "relation" -> StructureType.GeneratorStructure,
    "generate-from" -> StructureType.GeneratorStructure,
    "generate" -> StructureType.GeneratorStructure,
    "map-lambda" -> StructureType.LambdaMap,
    "map-lam" -> StructureType.LambdaMap,
    "filter-lambda" -> StructureType.LambdaFilter,
    "filter-lam" -> StructureType.LambdaFilter,
    "sort-lambda" -> StructureType.LambdaSort,
    "sort-lam" -> StructureType.LambdaSort,
    "reduce-lambda" -> StructureType.LambdaReduce,
    "reduce-lam" -> StructureType.LambdaReduce,
    "fold-lambda" -> StructureType.LambdaReduce,
    "fold-lam" -> StructureType.LambdaReduce
  )

  /** Tokenize a piece of code in literate mode */
  def apply(code: String): Either[VyxalCompilationError, List[Token]] =
    import LiterateParsers.*
    (parseAll(tokens, code): @unchecked) match
      case NoSuccess(msg, _) => Left(VyxalCompilationError(msg))
      case Success(result, _) => Right(result)

  private def sbcsifySingle(token: Token): String =
    val Token(tokenType, value, _) = token
    tokenType match
      case GetVar => "#$" + value
      case SetVar => s"#=$value"
      case AugmentVar => s"#>$value"
      case Constant => s"#!$value"
      case Str => s""""$value""""
      case Branch => "|"
      case StructureClose => "}"
      case StructureAllClose => "]"
      case ListOpen => "#["
      case ListClose => "#]"
      case SyntaxTrigraph if value == ":=[" => "#:["
      case Command if !Elements.elements.contains(value) =>
        Elements.symbolFor(value).get
      case _ => value
  end sbcsifySingle

  /** Convert literate mode code into SBCS mode code */
  def sbcsify(tokens: List[Token]): String =
    val out = StringBuilder()

    for i <- tokens.indices do
      val token @ Token(tokenType, value, _) = tokens(i)
      val sbcs = sbcsifySingle(token)
      out.append(sbcs)

      if i < tokens.length - 1 then
        val next = tokens(i + 1)
        tokenType match
          case Number =>
            if value != "0" && next.tokenType == Number
              && next.value != "." && !value.endsWith(".")
            then out.append(" ")
          case GetVar | SetVar | AugmentVar | Constant =>
            if "[a-zA-Z0-9_]+".r.matches(sbcsifySingle(next)) then
              out.append(" ")
          case _ =>
    end for

    out.toString
  end sbcsify

  private object LiterateParsers extends Lexer:
    override def skipWhitespace: Boolean = false

    def ws: Parser[List[Token]] = "[ \t\r\f]+|##[^\n]*".r ^^^ Nil

    private val litDecimalRegex =
      raw"(-?((0|[1-9][0-9_]*)?\.[0-9]*|0|[1-9][0-9_]*))"
    override def number: Parser[Token] =
      withRange(
        raw"(${litDecimalRegex}i($litDecimalRegex)?)|(i$litDecimalRegex)|$litDecimalRegex|(i( |$$))".r
      ) ^^ { case (value, range) =>
        val temp = value.replace("i", "ı").replace("_", "")
        val parts =
          if !temp.endsWith("ı") then temp.split("ı").toSeq
          else temp.init.split("ı").toSeq :+ ""
        Token(
          Number,
          parts
            .map(x => if x.startsWith("-") then x.substring(1) + "_" else x)
            .mkString("ı"),
          range
        )
      }

    override def string: Parser[Token] =
      withRange(raw"""("(?:[^"\\]|\\.)*")""".r) ^^ { case (value, range) =>
        Token(Str, value.substring(1, value.length - 1), range)
      }

    override def contextIndex: Parser[Token] =
      withRange("""`\d*`""".r) ^^ { case (value, range) =>
        Token(ContextIndex, value.substring(1, value.length - 2), range)
      }

    def lambdaBlock: Parser[List[Token]] =
      withRange(
        ("{": Parser[String]) | StructureType.Lambda.open |
          lambdaOpeners.keys.map(opener => opener: Parser[String]).reduce(_ | _)
      )
      // Keep going until the branch indicating params end, but don't stop at ","
        ~! (rep(not((branch | litBranch).filter(_.value != ",")) ~> singleToken)
          .map(_.flatten)
          ~ (branch | litBranch)).?
        ~ rep(
          not(
            litStructClose | structureSingleClose | structureDoubleClose | structureAllClose
          ) ~> singleToken
        ).map(_.flatten)
        ~ (litStructClose | structureSingleClose | structureDoubleClose | not(
          not(structureAllClose)
        )).? ^^ { case (_, openRange) ~ possibleParams ~ body ~ endTok =>
          val opener =
            Token(StructureOpen, StructureType.Lambda.open, openRange)
          val possParams = possibleParams match
            case Some(params ~ branch) =>
              // Branches get turned into `|` when sbcsifying. To preserve commas, turn them into Commands instead
              val paramsWithCommas = params.map(tok =>
                if tok.tokenType == Branch && tok.value == "," then
                  Token(Command, ",", tok.range)
                else if tok.tokenType == Command then
                  tok.copy(tokenType = Param)
                else tok
              )
              paramsWithCommas :+ branch
            case None => Nil
          val withoutEnd = opener :: (possParams ::: body)
          endTok match
            case Some(tok: Token) => withoutEnd :+ tok
            case _ =>
              // This means there was a StructureAllClose or we hit EOF
              withoutEnd
        }

    def litListOpen: Parser[Token] = withRange("[") ^^ { case (_, range) =>
      Token(ListOpen, "#[", range)
    }

    def litListClose: Parser[Token] = withRange("]") ^^ { case (_, range) =>
      Token(ListClose, "#]", range)
    }

    def normalGroup: Parser[List[Token]] = "(" ~> tokens <~ ")"

    def keywordsParser(keywords: Iterable[String]): Parser[(String, Range)] =
      // Sort to ensure bigger strings matched first
      keywords.toSeq.sortBy(-_.length).map(withRange(_)).reduce(_ | _)

    def elementKeyword: Parser[Token] =
      keywordsParser(Elements.elements.values.flatMap(_.keywords)) ^^ {
        case (word, range) => Token(Command, word, range)
      }

    def modifierKeyword: Parser[Token] =
      keywordsParser(Modifiers.modifiers.values.flatMap(_.keywords)) ^^ {
        case (keyword, range) =>
          val mod =
            Modifiers.modifiers.values.find(_.keywords.contains(keyword)).get
          val tokenType = mod.arity match
            case 1 => MonadicModifier
            case 2 => DyadicModifier
            case 3 => TriadicModifier
            case 4 => TetradicModifier
            case _ => SpecialModifier
          Token(tokenType, keyword, range)
      }

    def structOpener: Parser[Token] =
      keywordsParser(structOpeners.keys) ^^ { case (word, range) =>
        val sbcs = structOpeners(word).open
        Token(StructureOpen, sbcs, range)
      }

    def otherKeyword: Parser[Token] =
      keywordsParser(keywords.keys) ^^ { case (word, range) =>
        Token(keywords(word), word, range)
      }

    def litGetVariable: Parser[Token] =
      withRange("""\$([_a-zA-Z][_a-zA-Z0-9]*)?""".r) ^^ { case (value, range) =>
        Token(GetVar, value.substring(1), range)
      }

    def litSetVariable: Parser[Token] =
      withRange(""":=([_a-zA-Z][_a-zA-Z0-9]*)?""".r) ^^ { case (value, range) =>
        Token(SetVar, value.substring(2), range)
      }

    def litSetConstant: Parser[Token] =
      withRange(""":!=([_a-zA-Z][_a-zA-Z0-9]*)?""".r) ^^ {
        case (value, range) =>
          Token(Constant, value.substring(3), range)
      }

    def litAugVariable: Parser[Token] =
      withRange(""":>([a-zA-Z][_a-zA-Z0-9]*)?""".r) ^^ { case (value, range) =>
        Token(AugmentVar, value.substring(2), range)
      }

    def unpackVar: Parser[List[Token]] =
      withRange(":=") ~ list ^^ { case (_, unpackRange) ~ listTokens =>
        (Token(SyntaxTrigraph, "#:[", unpackRange) :: listTokens.slice(
          1,
          listTokens.size - 1
        )) :+ Token(UnpackClose, "]", listTokens.last.range)
      }

    def list: Parser[List[Token]] =
      parseToken(ListOpen, "[") ~! rep(
        not(raw"[|,\]]".r) ~> singleToken ~
          (branch | litBranch).?
      ) ~ parseToken(ListClose, "]") ^^ { case startTok ~ elems ~ endTok =>
        val middle = elems.flatMap { case elem ~ branch => elem ++ branch }
        (startTok +: middle) :+ endTok
      }

    // TODO figure out what this is for
    // def tilde: Parser[Token] = "~" ^^^ AlreadyCode("!")

    def litBranch: Parser[Token] = keywordsParser(branchKeywords) ^^ {
      case (value, range) => Token(Branch, value, range)
    }

    def litStructClose: Parser[Token] = keywordsParser(endKeywords) ^^ {
      case (value, range) => Token(StructureClose, value, range)
    }

    def rawCode: Parser[List[Token]] = withStartPos("#([^#]|#[^}])*#}".r) ^^ {
      case (value, row, col) =>
        super
          .parseAll(super.tokens, value.substring(1, value.length - 2))
          .map { tokens =>
            tokens.map { tok =>
              tok.copy(range =
                tok.range.copy(
                  startRow = row + tok.range.startRow,
                  startCol =
                    if tok.range.startRow == 0 then col + tok.range.startCol
                    else tok.range.startCol
                )
              )
            }
          }
          .get
    }

    def singleToken: Parser[List[Token]] =
      lambdaBlock | list | unpackVar | (litGetVariable | litSetVariable | litSetConstant | litAugVariable
        | elementKeyword | modifierKeyword | structOpener | otherKeyword | litBranch | litStructClose)
        .map(List(_))
        | ws | normalGroup | rawCode | super.token.map(List(_))

    override def tokens: Parser[List[Token]] = rep(singleToken).map(_.flatten)

  end LiterateParsers
end LitLexer
