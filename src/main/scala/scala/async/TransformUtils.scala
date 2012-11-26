/*
 * Copyright (C) 2012 Typesafe Inc. <http://www.typesafe.com>
 */
package scala.async

import scala.reflect.macros.Context
import reflect.ClassTag

/**
 * Utilities used in both `ExprBuilder` and `AnfTransform`.
 */
private[async] final case class TransformUtils[C <: Context](c: C) {

  import c.universe._

  object name {
    def suffix(string: String) = string + "$async"

    def suffixedName(prefix: String) = newTermName(suffix(prefix))

    val state         = suffixedName("state")
    val result        = suffixedName("result")
    val resume        = suffixedName("resume")
    val execContext   = suffixedName("execContext")
    val stateMachine  = newTermName(fresh("stateMachine"))
    val stateMachineT = stateMachine.toTypeName
    val apply         = newTermName("apply")
    val tr            = newTermName("tr")
    val matchRes      = "matchres"
    val ifRes         = "ifres"
    val await         = "await"
    val bindSuffix    = "$bind"

    def fresh(name: TermName): TermName = newTermName(fresh(name.toString))

    def fresh(name: String): String = if (name.toString.contains("$")) name else c.fresh("" + name + "$")
  }

  def defaultValue(tpe: Type): Literal = {
    val defaultValue: Any =
      if (tpe <:< definitions.BooleanTpe) false
      else if (definitions.ScalaNumericValueClasses.exists(tpe <:< _.toType)) 0
      else if (tpe <:< definitions.AnyValTpe) 0
      else null
    Literal(Constant(defaultValue))
  }

  def isAwait(fun: Tree) =
    fun.symbol == defn.Async_await

  /** Replace all `Ident` nodes referring to one of the keys n `renameMap` with a node
    * referring to the corresponding new name
    */
  def substituteNames(tree: Tree, renameMap: Map[Symbol, Name]): Tree = {
    val renamer = new Transformer {
      override def transform(tree: Tree) = tree match {
        case Ident(_) => (renameMap get tree.symbol).fold(tree)(Ident(_))
        case _        => super.transform(tree)
      }
    }
    renamer.transform(tree)
  }

  /** Descends into the regions of the tree that are subject to the
    * translation to a state machine by `async`. When a nested template,
    * function, or by-name argument is encountered, the descend stops,
    * and `nestedClass` etc are invoked.
    */
  trait AsyncTraverser extends Traverser {
    def nestedClass(classDef: ClassDef) {
    }

    def nestedModule(module: ModuleDef) {
    }

    def nestedMethod(module: DefDef) {
    }

    def byNameArgument(arg: Tree) {
    }

    def function(function: Function) {
    }

    override def traverse(tree: Tree) {
      tree match {
        case cd: ClassDef     => nestedClass(cd)
        case md: ModuleDef    => nestedModule(md)
        case dd: DefDef       => nestedMethod(dd)
        case fun: Function    => function(fun)
        case Apply(fun, args) =>
          val isInByName = isByName(fun)
          for ((arg, index) <- args.zipWithIndex) {
            if (!isInByName(index)) traverse(arg)
            else byNameArgument(arg)
          }
          traverse(fun)
        case _                => super.traverse(tree)
      }
    }
  }

  private lazy val Boolean_ShortCircuits: Set[Symbol] = {
    import definitions.BooleanClass
    def BooleanTermMember(name: String) = BooleanClass.typeSignature.member(newTermName(name).encodedName)
    val Boolean_&& = BooleanTermMember("&&")
    val Boolean_|| = BooleanTermMember("||")
    Set(Boolean_&&, Boolean_||)
  }

  def isByName(fun: Tree): (Int => Boolean) = {
    if (Boolean_ShortCircuits contains fun.symbol) i => true
    else fun.tpe match {
      case MethodType(params, _) =>
        val isByNameParams = params.map(_.asTerm.isByNameParam)
        (i: Int) => isByNameParams.applyOrElse(i, (_: Int) => false)
      case _                     => Map()
    }
  }

  def statsAndExpr(tree: Tree): (List[Tree], Tree) = tree match {
    case Block(stats, expr) => (stats, expr)
    case _                  => (List(tree), Literal(Constant(())))
  }

  def mkVarDefTree(resultType: Type, resultName: TermName): c.Tree = {
    ValDef(Modifiers(Flag.MUTABLE), resultName, TypeTree(resultType), defaultValue(resultType))
  }

  def emptyConstructor: DefDef = {
    val emptySuperCall = Apply(Select(Super(This(tpnme.EMPTY), tpnme.EMPTY), nme.CONSTRUCTOR), Nil)
    DefDef(NoMods, nme.CONSTRUCTOR, List(), List(List()), TypeTree(), Block(List(emptySuperCall), c.literalUnit.tree))
  }

  def applied(className: String, types: List[Type]): AppliedTypeTree =
    AppliedTypeTree(Ident(c.mirror.staticClass(className)), types.map(TypeTree(_)))

  object defn {
    def mkList_apply[A](args: List[Expr[A]]): Expr[List[A]] = {
      c.Expr(Apply(Ident(definitions.List_apply), args.map(_.tree)))
    }

    def mkList_contains[A](self: Expr[List[A]])(elem: Expr[Any]) = reify(self.splice.contains(elem.splice))

    def mkFunction_apply[A, B](self: Expr[Function1[A, B]])(arg: Expr[A]) = reify {
      self.splice.apply(arg.splice)
    }

    def mkAny_==(self: Expr[Any])(other: Expr[Any]) = reify {
      self.splice == other.splice
    }

    def mkTry_get[A](self: Expr[util.Try[A]]) = reify {
      self.splice.get
    }

    val Try_get       = methodSym(reify((null: scala.util.Try[Any]).get))
    val TryClass      = c.mirror.staticClass("scala.util.Try")
    val TryAnyType    = appliedType(TryClass.toType, List(definitions.AnyTpe))
    val NonFatalClass = c.mirror.staticModule("scala.util.control.NonFatal")

    val Async_await = {
      val asyncMod = c.mirror.staticClass("scala.async.AsyncBase")
      val tpe = asyncMod.asType.toType
      tpe.member(c.universe.newTermName("await")).ensuring(_ != NoSymbol)
    }
  }

  /** `termSym( (_: Foo).bar(null: A, null: B)` will return the symbol of `bar`, after overload resolution. */
  private def methodSym(apply: c.Expr[Any]): Symbol = {
    val tree2: Tree = c.typeCheck(apply.tree)
    tree2.collect {
      case s: SymTree if s.symbol.isMethod => s.symbol
    }.headOption.getOrElse(sys.error(s"Unable to find a method symbol in ${apply.tree}"))
  }

  /**
   * Using [[scala.reflect.api.Trees.TreeCopier]] copies more than we would like:
   * we don't want to copy types and symbols to the new trees in some cases.
   *
   * Instead, we just copy positions and attachments.
   */
  def attachCopy[T <: Tree](orig: Tree)(tree: T): tree.type = {
    tree.setPos(orig.pos)
    for (att <- orig.attachments.all)
      tree.updateAttachment[Any](att)(ClassTag.apply[Any](att.getClass))
    tree
  }

  def resetInternalAttrs(tree: Tree, internalSyms: List[Symbol]) =
    new ResetInternalAttrs(internalSyms.toSet).transform(tree)

  /**
   * Adaptation of [[scala.reflect.internal.Trees.ResetAttrs]]
   *
   * A transformer which resets symbol and tpe fields of all nodes in a given tree,
   * with special treatment of:
   * `TypeTree` nodes: are replaced by their original if it exists, otherwise tpe field is reset
   * to empty if it started out empty or refers to local symbols (which are erased).
   * `TypeApply` nodes: are deleted if type arguments end up reverted to empty
   *
   * `This` and `Ident` nodes referring to an external symbol are ''not'' reset.
   */
  private final class ResetInternalAttrs(internalSyms: Set[Symbol]) extends Transformer {

    import language.existentials

    override def transform(tree: Tree): Tree = super.transform {
      def isExternal = tree.symbol != NoSymbol && !internalSyms(tree.symbol)

      tree match {
        case tpt: TypeTree                         => resetTypeTree(tpt)
        case TypeApply(fn, args)
          if args map transform exists (_.isEmpty) => transform(fn)
        case EmptyTree                             => tree
        case (_: Ident | _: This) if isExternal    => tree // #35 Don't reset the symbol of Ident/This bound outside of the async block
        case _                                     => resetTree(tree)
      }
    }

    private def resetTypeTree(tpt: TypeTree): Tree = {
      if (tpt.original != null)
        transform(tpt.original)
      else if (tpt.tpe != null && tpt.asInstanceOf[symtab.TypeTree forSome {val symtab: reflect.internal.SymbolTable}].wasEmpty) {
        val dupl = tpt.duplicate
        dupl.tpe = null
        dupl
      }
      else tpt
    }

    private def resetTree(tree: Tree): Tree = {
      val hasSymbol: Boolean = {
        val reflectInternalTree = tree.asInstanceOf[symtab.Tree forSome {val symtab: reflect.internal.SymbolTable}]
        reflectInternalTree.hasSymbol
      }
      val dupl = tree.duplicate
      if (hasSymbol)
        dupl.symbol = NoSymbol
      dupl.tpe = null
      dupl
    }
  }

}
