package vyxal.parsing

import scala.language.strictEquality

import vyxal.{Elements, Modifiers}
import vyxal.parsing.Common.withRange
// import vyxal.parsing.Common.given // For custom whitespace
import vyxal.parsing.TokenType.*

import fastparse.*
import fastparse.JavaWhitespace.whitespace

/** Lexer for literate mode */
private[parsing] object LiterateLexer:
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
    "->",
    "else:",
    "else",
    "elif",
    "else-if",
    "body",
    "do",
    "branch",
    "then",
    "in",
    "using",
    "no?",
    "=>",
    "from",
    "as",
    "with",
    "given",
    ":and:",
    "has",
    "does",
    "using",
    "on",
  )

  /** Map keywords to their token types */
  private val keywords = Map(
    "close-all" -> TokenType.StructureAllClose,
    "end-all" -> TokenType.StructureAllClose,
    "end-end" -> TokenType.StructureDoubleClose,
  )

  private val lambdaOpeners = Map(
    "lambda" -> StructureType.Lambda,
    "lam" -> StructureType.Lambda,
    "map-lambda" -> StructureType.LambdaMap,
    "map-lam" -> StructureType.LambdaMap,
    "map<" -> StructureType.LambdaMap,
    "filter-lambda" -> StructureType.LambdaFilter,
    "filter-lam" -> StructureType.LambdaFilter,
    "filter<" -> StructureType.LambdaFilter,
    "sort-lambda" -> StructureType.LambdaSort,
    "sort-lam" -> StructureType.LambdaSort,
    "sort<" -> StructureType.LambdaSort,
    "reduce-lambda" -> StructureType.LambdaReduce,
    "reduce-lam" -> StructureType.LambdaReduce,
    "reduce<" -> StructureType.LambdaReduce,
    "fold-lambda" -> StructureType.LambdaReduce,
    "fold-lam" -> StructureType.LambdaReduce,
    "fold<" -> StructureType.LambdaReduce,
  )

  /** Keywords for opening structures. Has to be a separate map because while
    * all of them have the same [[TokenType]], they have different values
    * depending on the kind of structure
    */
  private val structOpeners = Map(
    // These can't go in the big map, because that's autogenerated
    "yes?" -> StructureType.Ternary,
    "if" -> StructureType.IfStatement,
    "for" -> StructureType.For,
    "for<" -> StructureType.For,
    "do-to-each" -> StructureType.For,
    "each-as" -> StructureType.For,
    "while" -> StructureType.While,
    "while<" -> StructureType.While,
    "exists<" -> StructureType.DecisionStructure,
    "relation<" -> StructureType.GeneratorStructure,
    "generate-from<" -> StructureType.GeneratorStructure,
    "generate<" -> StructureType.GeneratorStructure,
    "define" -> StructureType.DefineStructure,
  )

  private val groupModifierToToken = Map(
    "." -> ((range) => LitToken(MonadicModifier, "⸠", range)),
    ":" -> ((range) => LitToken(DyadicModifier, "ϩ", range)),
    ":." -> ((range) => LitToken(TriadicModifier, "э", range)),
    "::" -> ((range) => LitToken(TetradicModifier, "Ч", range)),
    "," -> ((range) => LitToken(MonadicModifier, "ᵈ", range)),
    ";" -> ((range) => LitToken(DyadicModifier, "ᵉ", range)),
    ";," -> ((range) => LitToken(TriadicModifier, "ᶠ", range)),
    ";;" -> ((range) => LitToken(TetradicModifier, "ᵍ", range)),
  )

  lazy val literateModeMappings: Map[String, String] =
    Elements.elements.values.view.flatMap { elem =>
      elem.keywords.map(_ -> elem.symbol)
    }.toMap ++
      Modifiers.modifiers.view.flatMap { (symbol, mod) =>
        mod.keywords.map(_ -> symbol)
      }.toMap ++
      keywords.map { (kw, typ) => kw -> typ.canonicalSBCS.get }.toMap ++
      endKeywords.map(_ -> TokenType.StructureClose.canonicalSBCS.get).toMap ++
      branchKeywords.map(_ -> TokenType.Branch.canonicalSBCS.get).toMap ++
      (lambdaOpeners ++ structOpeners).map { (kw, typ) => kw -> typ.open }

  def wordStart[$: P]: P[String] = P(CharIn("a-zA-Z_<>?!*+\\-=&%:@").!)
  def word[$: P]: P[String] =
    P((wordStart ~~ CharsWhileIn("0-9a-zA-Z_<>?!*+\\-=&%:'@", 0)).!)

  def litInt[$: P]: P[String] =
    P(("0" | (CharIn("1-9") ~~ CharsWhileIn("_0-9", 0))).!)
  def litDigits[$: P]: P[String] = P(CharsWhileIn("_0-9", 0).!)
  def litDecimal[$: P]: P[String] =
    P(("-".? ~~ (litInt ~~ ("." ~~ litDigits).? | "." ~~ litDigits)).!)
  def litNumber[$: P]: P[LitToken] =
    parseToken(
      Number,
      ((litDecimal ~~
        ("ı" ~~ litDecimal.? | "i" ~~ (litDecimal | !wordStart)).?) |
        "i" ~~
        (litDecimal | !wordStart) | "ı" ~~ litDecimal.?).!,
    ).opaque("<number (literate)>").map {
      case LitToken(_, v, range) =>
        val value = v.asInstanceOf[String]
        val temp = value.replace("i", "ı").replace("_", "")
        val parts =
          if !temp.endsWith("ı") then temp.split("ı").toSeq
          else temp.init.split("ı").toSeq :+ ""
        LitToken(
          Number,
          parts
            .map(x => if x.startsWith("-") then x.substring(1) + "_" else x)
            .mkString("ı"),
          range,
        )
    }
  end litNumber

  def contextIndex[$: P]: P[LitToken] =
    parseToken(ContextIndex, "`" ~~ CharsWhileIn("0-9", 0).! ~~ "`")

  def functionCall[$: P]: P[LitToken] =
    parseToken(FunctionCall, "`" ~~ Common.varName.! ~~ "`")

  def defineObj[$: P]: P[LitToken] =
    parseToken(DefineRecord, "record" ~ Common.varName.!)

  def isLambdaParam(word: String): Boolean =
    !structOpeners.contains(word) && !lambdaOpenerSet.contains(word) &&
      !branchKeywords.contains(word) && !endKeywords.contains(word)

  def defineModBlock[$: P]: P[LitToken] =
    P(
      withRange("define".!) ~ withRange("*".!) ~/
        withRange(word.filter(isLambdaParam)) ~/ litBranch ~
        ( // Grab the first parameter branch
          withRange(",".! | word.filter(isLambdaParam)).rep ~ litBranch
        ) ~
        ( // Grab the second
          withRange(",".! | word.filter(isLambdaParam)).rep ~ litBranch
        )
    ).map {
      case (
            open,
            openRange,
            (mode, modeRange),
            (name, nameRange),
            branchTok,
            funcArgs,
            elemArgs,
          ) =>
        val nameTok = LitToken(Param, name, nameRange)
        val commadFuncArgs = funcArgs(0).map {
          case (param, range) =>
            if param == "," then LitToken(Command, ",", range)
            else LitToken(Param, param, range)
        }
        val fArgs = commadFuncArgs :+ funcArgs(1)

        val commadElemArgs = elemArgs(0).map {
          case (param, range) =>
            if param == "," then LitToken(Command, ",", range)
            else LitToken(Param, param, range)
        }
        val eArgs = commadElemArgs :+ elemArgs(1)
        LitToken(
          TokenType.Group,
          LitToken(TokenType.StructureOpen, "#::", openRange) +:
            LitToken(TokenType.Command, mode, modeRange) +:
            (List(nameTok, branchTok) ++ (fArgs ++ eArgs)).toList,
          nameRange,
        )
    }

  def defineElemBlock[$: P]: P[LitToken] =
    P(
      withRange("define".!) ~ withRange("@".!) ~/
        withRange(word.filter(isLambdaParam)) ~/ litBranch ~
        ( // Grab the first parameter branch
          withRange(",".! | word.filter(isLambdaParam)).rep ~ litBranch
        )
    ).map {
      case (
            open,
            openRange,
            (mode, modeRange),
            (name, nameRange),
            branchTok,
            elemArgs,
          ) =>
        // Case of an element definition
        val nameTok = LitToken(Param, name, nameRange)
        val commadArgs = elemArgs(0).map {
          case (param, range) =>
            if param == "," then LitToken(Command, ",", range)
            else LitToken(Param, param, range)
        }
        val args = commadArgs :+ elemArgs(1)
        LitToken(
          Group,
          (LitToken(StructureOpen, "#::", openRange) +:
            LitToken(Command, mode, modeRange) +:
            (List(nameTok, branchTok) ++ args)).toList,
          nameRange,
        )
    }

  def defineBlock[$: P]: P[LitToken] = P(defineModBlock | defineElemBlock)

  def extensionKeyword[$: P]: P[LitToken] =
    P(
      withRange("extension".!) ~ "(".? ~ withRange(word.filter(isLambdaParam)) ~
        ")".? ~/ litBranch ~
        // Grab the first parameter branch
        withRange(
          "(".? ~ ("*".! | word.filter(isLambdaParam) ~ ")".?) ~ litBranch
        ).rep
    ).map {
      case (opener, openRange, name, branchTok, parameters) =>
        val nameTok = LitToken(Param, name._1, name._2)
        val params = parameters.map(_._1)

        LitToken(
          Group,
          List(LitToken(DefineExtension, "#:>>", openRange), nameTok) ++
            (branchTok +:
              params
                .map(param =>
                  List(
                    LitToken(Param, param._1, Range.fake),
                    param._2,
                  )
                )
                .flatten),
          openRange,
        )

    }

  val lambdaOpenerSet = lambdaOpeners.keys.toSet
  def lambdaBlock[$: P]: P[LitToken] =
    P(
      withRange("{".! | "lambda".! | "lam".! | "λ".!) ~/
        ( // Keep going until the branch indicating params end, but don't stop at ","
          withRange(",".! | word.filter(isLambdaParam)).rep ~ litBranch
        ).? ~
        (!(litStructClose | structureSingleClose | structureDoubleClose |
          structureAllClose) ~ NoCut(singleTokenGroup)).rep.map(_.flatten) ~
        (End | litStructClose | structureSingleClose | structureDoubleClose |
          &(structureAllClose))
    ).map {
      case (opener, openRange, possibleParams, body, endTok) =>
        val openerTok = LitToken(
          StructureOpen,
          StructureType.Lambda.open,
          openRange,
        )
        val possParams = possibleParams match
          case Some((params, branch)) =>
            // Branches get turned into `|` when sbcsifying. To preserve commas, turn them into Commands instead
            val paramsWithCommas = params.map {
              case (param, range) =>
                if param == "," then LitToken(Command, ",", range)
                else LitToken(Param, param, range)
            }
            paramsWithCommas :+ branch
          case None => Nil
        val withoutEnd = openerTok +: (possParams ++ body)
        val total = endTok match
          case tok: LitToken => withoutEnd :+ tok
          case _ =>
            // This means there was a StructureAllClose or we hit EOF
            withoutEnd

        LitToken(TokenType.Group, total.toList, openRange)
    }
  end lambdaBlock

  def specialLambdaBlock[$: P]: P[LitToken] =
    P(
      withRange("{".! | Common.lambdaOpen | word.filter(lambdaOpenerSet).!) ~
        (!(litStructClose | structureSingleClose | structureDoubleClose |
          structureAllClose) ~ NoCut(singleTokenGroup)).rep.map(_.flatten) ~
        (End | litStructClose | structureSingleClose | structureDoubleClose |
          &(structureAllClose))
    ).map {
      case (opener, openRange, body, endTok) =>
        val openerTok = LitToken(
          StructureOpen,

          // If it's a keyword, map it to SBCS
          if opener == "{" then StructureType.Lambda.open
          else lambdaOpeners.get(opener).map(_.open).getOrElse(opener),
          openRange,
        )
        val withoutEnd = openerTok +: body
        val total = endTok match
          case tok: LitToken => withoutEnd :+ tok
          case _ =>
            // This means there was a StructureAllClose or we hit EOF
            withoutEnd

        LitToken(TokenType.Group, total.toList, openRange)
    }

  def structureSingleClose[$: P]: P[LitToken] =
    parseToken(StructureClose, "}".!)

  def structureDoubleClose[$: P]: P[LitToken] =
    parseToken(StructureDoubleClose, ")".!)

  def structureAllClose[$: P]: P[LitToken] =
    parseToken(StructureAllClose, "]".!)

  def litString[$: P]: P[LitToken] =
    parseToken(
      Str,
      """"""" ~/ ("\\" ~~ AnyChar | !""""""" ~ AnyChar).rep.! ~ """"""",
    )

  def groupModifier[$: P]: P[LitToken] =
    parseToken(GroupType, (";;" | ";," | "::" | ":." | ";" | ":" | "." | ",").!)
  def normalGroup[$: P]: P[LitToken] =
    val temp = P("(" ~ groupModifier.? ~~/ tokens ~ ")")
    temp.map {
      case (mod, tokens) =>
        if mod.isEmpty then
          LitToken(Group, tokens, Range(temp.startIndex, temp.index))
        else
          // This is actually a next-n items as lambda group
          val value = mod.get.value.asInstanceOf[String]
          LitToken(
            Group,
            groupModifierToToken(value)(mod.get.range) +: tokens,
            Range(temp.startIndex, temp.index),
          )
    }

  def keywordsParser[$: P](keywords: Iterable[String]): P[String] =
    // TODO(user): Make this not use filter
    // This can't be a one-liner because we want it to be strictly evaluated
    val isKeyword = keywords.toSet
    word.filter(x => isKeyword(removeDoubleNt(x)))

  def negatedKeywordParser[$: P](keywords: Iterable[String]): P[String] =
    // This can't be a one-liner because we want it to be strictly evaluated
    val isKeyword = keywords.toSet
    word.filter(x => isKeyword(removeDoubleNt(x).stripSuffix("n't")))

  private def removeDoubleNt(word: String): String =
    var temp = word
    while temp.endsWith("n'tn't") do temp = temp.stripSuffix("n'tn't")
    temp

  def elementKeyword[$: P]: P[LitToken] =
    parseToken(
      Command,
      keywordsParser(Elements.elements.values.flatMap(_.keywords)).map(kw =>
        Elements.elements.values
          .find(elem => elem.keywords.contains(removeDoubleNt(kw)))
          .get
          .symbol
      ),
    ).opaque("<element keyword>")

  def negatedElementKeyword[$: P]: P[LitToken] =
    parseToken(
      NegatedCommand,
      negatedKeywordParser(Elements.elements.values.flatMap(_.keywords)).map(
        kw =>
          Elements.elements.values
            .find(elem =>
              elem.keywords.contains(removeDoubleNt(kw).stripSuffix("n't"))
            )
            .get
            .symbol
      ),
    ).opaque("<negated element keyword>")

  def modifierKeyword[$: P]: P[LitToken] =
    withRange(
      keywordsParser(Modifiers.modifiers.values.flatMap(_.keywords)) | "<-}".!
    ).opaque("<modifier keyword>").map {
      case (keyword, range) =>
        val mod =
          Modifiers.modifiers.values.find(_.keywords.contains(keyword)).get
        val name = Modifiers.modifiers.find(_._2._3.contains(keyword)).get._1
        val tokenType = mod.arity match
          case 1 => MonadicModifier
          case 2 => DyadicModifier
          case 3 => TriadicModifier
          case 4 => TetradicModifier
          case _ => SpecialModifier
        LitToken(tokenType, name, range)
    }

  def structOpener[$: P]: P[LitToken] =
    withRange("?->" | "?").opaque("<ternary>").map {
      case (_, range) =>
        LitToken(StructureOpen, StructureType.Ternary.open, range)
    } |
      withRange(keywordsParser(structOpeners.keys))
        .opaque("<struct opener>")
        .map {
          case (word, range) =>
            val sbcs = structOpeners(word).open
            LitToken(StructureOpen, sbcs, range)
        }

  def otherKeyword[$: P]: P[LitToken] =
    withRange(keywordsParser(keywords.keys)).opaque("<other keyword>").map {
      case (word, range) => LitToken(keywords(word), word, range)
    }

  def litGetVariable[$: P]: P[LitToken] =
    parseToken(GetVar, "$" ~/ Common.varName.?.!)

  def litSetVariable[$: P]: P[LitToken] =
    parseToken(SetVar, ":=" ~ Common.varName.?.!)

  def litSetConstant[$: P]: P[LitToken] =
    parseToken(Constant, ":!=" ~/ Common.varName.?.!)

  def litAugVariable[$: P]: P[LitToken] =
    parseToken(AugmentVar, ":>" ~/ Common.varName.?.!)

  def unpackVar[$: P]: P[Seq[LitToken]] =
    P(withRange(":=") ~ list).map {
      case (_, unpackRange, listTokens) =>
        (LitToken(UnpackTrigraph, "#:[", unpackRange) +:
          listTokens.slice(1, listTokens.size - 1)) :+
          LitToken(UnpackClose, "]", listTokens.last.range)
    }

  def list[$: P]: P[Seq[LitToken]] =
    P(
      parseToken(ListOpen, "[".!) ~~/
        (litBranch | !"]" ~ singleTokenGroup ~ litBranch.?).rep ~
        parseToken(ListClose, "]".!)
    ).map {
      case (startTok, elems, endTok) =>
        val middle = elems.flatMap {
          case branch: LitToken => List(branch)
          case (elem, branch) => elem ++ branch
        }
        (startTok +: middle) :+ endTok
    }

  def litBranch[$: P]: P[LitToken] =
    P(
      parseToken(Branch, "|".!) | parseToken(Branch, ",".!) |
        parseToken(Branch, keywordsParser(branchKeywords))
          .opaque("<branch keyword>")
    )

  def litStructClose[$: P]: P[LitToken] =
    parseToken(StructureClose, endKeywords.map(_.!).reduce(_ | _))
      .opaque("<end keyword>")

  def tokenMove[$: P]: P[LitToken] = parseToken(MoveRight, "'".rep(1).!)

  def rawCode[$: P]: P[Seq[LitToken]] =
    P("#" ~ Index ~ (!"#}" ~ AnyChar).rep.! ~ "#}").map {
      case (offset, value) => SBCSLexer.lex(value).map { tok =>
          val newTok = LitToken(tok.tokenType, tok.value, tok.range)
          newTok.copy(range =
            Range(
              startOffset = offset + tok.range.startOffset,
              endOffset = offset + tok.range.endOffset,
            )
          )
        }
    }

  def modifierSymbol[$: P]: P[LitToken] =
    parseToken(ModifierSymbol, "$:" ~~/ Common.varName)

  def elementSymbol[$: P]: P[LitToken] =
    parseToken(ElementSymbol, "$@" ~~/ Common.varName)

  def unmodSymbol[$: P]: P[LitToken] =
    parseToken(OriginalSymbol, "$." ~~/ AnyChar.!)

  /** This is split from singleToken to prevent stack overflows when Fastparse's
    * `|` macro is expanded
    */
  def singleTokenSplit1[$: P]: P[LitToken] = ???

  def litVarToken[$: P]: P[LitToken] =
    P(litGetVariable | litSetVariable | litSetConstant | litAugVariable)

  def litKeyword[$: P]: P[LitToken] =
    P(
      extensionKeyword | elementKeyword | negatedElementKeyword |
        modifierKeyword | otherKeyword
    )

  def litStructToken[$: P]: P[LitToken] =
    P(structOpener | litBranch | litStructClose)

  def singleToken[$: P]: P[LitToken] =
    P(
      lambdaBlock | litKeyword | unmodSymbol | defineObj | defineBlock |
        specialLambdaBlock | contextIndex | functionCall | modifierSymbol |
        elementSymbol | litVarToken | tokenMove | litStructToken | litNumber |
        litString | normalGroup
    )

  def singleTokenGroup[$: P]: P[Seq[LitToken]] =
    P(
      list | unpackVar | singleToken.map(Seq(_)) | rawCode |
        SBCSLexer.token.map((token) =>
          Seq(LitToken(token.tokenType, token.value, token.range))
        )
    )

  def tokens[$: P]: P[List[LitToken]] =
    P(singleTokenGroup.rep).map(_.flatten.toList)

  def parseToken[$: P](
      tokenType: TokenType,
      tokenParser: => P[String],
  ): P[LitToken] =
    withRange(tokenParser)
      .map { (value, range) =>
        LitToken(tokenType, value, range)
      }
      .opaque(tokenType.toString)
  def parseAll[$: P]: P[List[LitToken]] = P(tokens ~ End)

  def lex(code: String): List[LitToken] =
    parse(code, this.parseAll) match
      case Parsed.Success(res, ind) =>
        if ind == code.length then res.toList
        else throw LexingException.LeftoverCodeException(code.substring(ind))
      case f @ Parsed.Failure(label, index, extra) =>
        val trace = f.trace()
        throw LexingException.Fastparse(trace.longMsg)
end LiterateLexer
