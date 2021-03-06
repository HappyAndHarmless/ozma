/* NSC -- new Scala compiler
 * Copyright 2005-2011 LAMP/EPFL
 * @author  Sébastien Doeraene
 */

package scala.tools.nsc
package backend.mozart

import scala.collection._
import scala.collection.mutable.{ LinkedHashSet, HashSet, HashMap, ListBuffer }

import ozma._

/** This is the compiler component that produces Oz code from AST
 *
 *  @author  Sébastien Doeraene
 *  @version 1.0
 *
 */
abstract class GenOzCode extends OzmaSubComponent {
  import global._
  import ozcodes._

  import definitions.{
    ArrayClass, ObjectClass, ThrowableClass, StringClass, StringModule,
    NothingClass, NullClass, AnyRefClass, UnitClass, ListClass, ConsClass,
    Object_equals, Object_isInstanceOf, Object_asInstanceOf, Object_toString,
    ScalaRunTimeModule,
    BoxedNumberClass, BoxedCharacterClass,
    getMember
  }

  import platform.isMaybeBoxed

  val phaseName = "ozcode"

  override def newPhase(p: Phase) = new OzCodePhase(p)

  class OzCodePhase(prev: Phase) extends StdPhase(prev) {
    var unit: CompilationUnit = _
    val unitClasses: LinkedHashSet[OzClass] = new LinkedHashSet

    override def run {
      scalaPrimitives.init
      nativeMethods.init
      super.run
    }

    override def apply(unit: CompilationUnit): Unit = {
      this.unit = unit
      informProgress("Generating Oz code for " + unit)

      unitClasses.clear
      gen(unit.body)

      unit.body = OzCodeClasses(unitClasses.toList)
      this.unit = null
    }

    def gen(tree: Tree): Context = gen(tree, new Context())

    def gen(trees: List[Tree], ctx: Context): Context = {
      var ctx1 = ctx
      for (t <- trees) ctx1 = gen(t, ctx1)
      ctx1
    }

    /////////////////// Utils ///////////////////////

    lazy val SingleAssignmentClass =
      definitions.getClass("scala.ozma.singleAssignment")

    def hasSingleAssignSemantics(sym: Symbol) =
      !sym.isVariable || sym.hasAnnotation(SingleAssignmentClass)

    lazy val List_cons = definitions.getMember(ListClass, "$colon$colon")

    /////////////////// Code generation ///////////////////////

    def gen(tree: Tree, ctx: Context): Context = tree match {
      case EmptyTree => ctx

      case PackageDef(pid, stats) =>
        gen(stats, ctx setPackage pid.name)

      case ClassDef(mods, name, _ /* tps */, impl) =>
        log("Generating class: " + tree.symbol.fullName)
        val outerClass = ctx.clazz
        ctx setClass (new OzClass(tree.symbol) setCompilationUnit unit)
        addClassFields(ctx, tree.symbol)
        //classes += (tree.symbol -> ctx.clazz)
        unitClasses += ctx.clazz
        gen(impl, ctx)
        ctx.clazz.methods = ctx.clazz.methods.reverse // preserve textual order
        ctx.clazz.fields  = ctx.clazz.fields.reverse  // preserve textual order
        ctx setClass outerClass

      // !! modules should be eliminated by refcheck... or not?
      case ModuleDef(mods, name, impl) =>
        abort("Modules should not reach backend!")

      case ValDef(mods, name, tpt, rhs) =>
        ctx // we use the symbol to add fields

      case DefDef(mods, name, tparams, vparamss, tpt, rhs) =>
        if (settings.debug.value)
          log("Entering method " + name)
        val m = new OzMethod(tree.symbol)
        m.sourceFile = unit.source.toString()
        ctx.clazz.addMethod(m)

        var ctx1 = ctx.enterMethod(m, tree.asInstanceOf[DefDef])
        addMethodParams(ctx1, vparamss)
        m.native = m.symbol.hasAnnotation(definitions.NativeAttr)

        ctx1.method.code = if (m.native)
          nativeMethods.getBodyFor(m.symbol)
        else if (!m.isAbstractMethod) {
          val base = genExpression(rhs, ctx1)
          if (!m.hasReturn) base else {
            ast.Try(base, ast.Catch(List(
                ast.CaseClause(
                    ast.Tuple(ast.Atom("return"), ast.Variable("R")),
                    ast.Variable("R"))
            )), ast.NoFinally())
          }
        } else
          null

        ctx1

      case Template(_, _, body) =>
        gen(body, ctx)

      case _ =>
        abort("Illegal tree in gen: " + tree)
    }

    /**
     * Add all fields of the given class symbol to the current OzCode
     * class.
     */
    private def addClassFields(ctx: Context, cls: Symbol) {
      if (settings.debug.value)
        assert(ctx.clazz.symbol eq cls,
               "Classes are not the same: " + ctx.clazz.symbol + ", " + cls)

      /** Non-method term members are fields, except for module members. Module
       *  members can only happen on .NET (no flatten) for inner traits. There,
       *  a module symbol is generated (transformInfo in mixin) which is used
       *  as owner for the members of the implementation class (so that the
       *  backend emits them as static).
       *  No code is needed for this module symbol.
       */
      for (f <- cls.info.decls ; if !f.isMethod && f.isTerm && !f.isModule)
        ctx.clazz addField new OzField(f)
    }

    /**
     * Add parameters to the current OzCode method. It is assumed the methods
     * have been uncurried, so the list of lists contains just one list.
     */
    private def addMethodParams(ctx: Context, vparamss: List[List[ValDef]]) {
      vparamss match {
        case Nil => ()

        case vparams :: Nil =>
          for (p <- vparams) {
            val lv = new Local(p.symbol, true)
            ctx.method.addParam(lv)
          }
          ctx.method.params = ctx.method.params.reverse

        case _ =>
          abort("Malformed parameter list: " + vparamss)
      }
    }

    def genExpression(tree: Tree, ctx: Context): ast.Phrase = {
      if (settings.debug.value)
        log("at line: " + (if (tree.pos.isDefined) tree.pos.line else tree.pos))

      (tree match {
        case LabelDef(name, params, rhs) =>
          if (settings.debug.value)
            log("Encountered a label def: " + tree)
          val sym = tree.symbol
          ctx.method.addLocal(new Local(sym, arg = false))

          val procVar = varForSymbol(sym)
          val body = genExpression(rhs, ctx)
          val arguments = params map { case ident @ Ident(name) =>
            varForSymbol(ident.symbol)
          }

          val proc = ast.Fun(procVar, arguments, body)
          val call = ast.Apply(procVar, arguments)

          ctx.method.labels = proc :: ctx.method.labels
          call

        case ValDef(_, nme.THIS, _, _) =>
          if (settings.debug.value)
            log("skipping trivial assign to _$this: " + tree)
          ast.UnitVal()

        case ValDef(_, _, _, rhs) =>
          val sym = tree.symbol
          val local = ctx.method.addLocal(new Local(sym, false))

          val value = if (sym.hasAnnotation(SingleAssignmentClass)) {
            ast.Wildcard() setPos rhs.pos
          } else if (rhs == EmptyTree) {
            if (settings.debug.value)
              log("Uninitialized variable " + tree + " at: " + (tree.pos));
            ast.Wildcard() setPos tree.pos
          } else {
            genExpression(rhs, ctx)
          }

          val rightOfEqual = if (hasSingleAssignSemantics(sym))
            value
          else
            genBuiltinApply("NewCell", value)

          rightOfEqual match {
            case _: ast.Wildcard => ast.UnitVal()
            case _ =>
              ast.And(ast.Eq(varForSymbol(sym) setPos tree.pos, rightOfEqual),
                  ast.UnitVal())
          }

        case t @ If(cond, thenp, elsep) =>
          ast.IfThenElse(genExpression(cond, ctx), genExpression(thenp, ctx),
              genExpression(elsep, ctx))

        case Return(expr) =>
          ctx.method.hasReturn = true
          ast.Raise(ast.Tuple(ast.Atom("return"), genExpression(expr, ctx)))

        case t @ Try(_, _, _) =>
          genTry(t, ctx)

        case Throw(expr) =>
          ast.And(
              genBuiltinApply("Throw", genExpression(expr, ctx)),
              ast.UnitVal())

        case New(tpt) =>
          abort("Unexpected New")

        case Apply(TypeApply(fun, targs), _) =>
          val sym = fun.symbol
          val cast = sym match {
            case Object_isInstanceOf => false
            case Object_asInstanceOf => true
            case _ =>
              abort("Unexpected type application " + fun + "[sym: " + sym.fullName + "]" + " in: " + tree)
          }

          val Select(obj, _) = fun
          val from = obj.tpe
          val to = targs.head.tpe
          val l = toTypeKind(from)
          val r = toTypeKind(to)
          val source = genExpression(obj, ctx)

          if (l.isValueType && r.isValueType) {
            if (cast)
              genConversion(l, r, source)
            else
              ast.Bool(l == r)
          }
          else if (l.isValueType) {
            val blackhole = blackholeReturnedValue(source)
            val result = if (cast) {
              ast.Raise(genNew(definitions.ClassCastExceptionClass))
            } else
              ast.False()
            ast.And(blackhole, result)
          }
          else if (r.isValueType && cast) {
            // Erasure should have added an unboxing operation to prevent that.
            assert(false, tree)
            source
          }
          else if (r.isValueType)
            genCast(from, definitions.boxedClass(to.typeSymbol).tpe, source, false)
          else
            genCast(from, to, source, cast)

        // 'super' call
        case Apply(fun @ Select(sup @ Super(_, mix), _), args) =>
          if (settings.debug.value)
            log("Call to super: " + tree)

          val superClass = varForSymbol(sup.symbol.superClass) setPos sup.pos
          val arguments = args map { genExpression(_, ctx) }
          val message = buildMessage(atomForSymbol(fun.symbol) setPos fun.pos,
              arguments)

          val superCall = ast.ObjApply(superClass, message)

          def isStaticModule(sym: Symbol): Boolean =
            (sym.isModuleClass && !sym.isImplClass && !sym.isLifted &&
                sym.companionModule != NoSymbol)

          // We initialize the module instance just after the super constructor
          // call.
          if (isStaticModule(ctx.clazz.symbol) && !ctx.isModuleInitialized &&
              ctx.method.symbol.isClassConstructor) {
            ctx.isModuleInitialized = true
            val module = ctx.clazz.symbol.companionModule
            val initModule = ast.Eq(varForModuleInternal(module), ast.Self())

            ast.And(ast.Eq(ast.Wildcard(), superCall),
                initModule, ast.UnitVal())
          } else
            superCall

        // 'new' constructor call
        case Apply(fun @ Select(New(tpt), nme.CONSTRUCTOR), args) =>
          val ctor = fun.symbol
          if (settings.debug.value)
            assert(ctor.isClassConstructor,
                   "'new' call to non-constructor: " + ctor.name)

          val arguments = args map { genExpression(_, ctx) }

          val generatedType = toTypeKind(tpt.tpe)
          if (settings.debug.value)
            assert(generatedType.isReferenceType || generatedType.isArrayType,
                 "Non reference type cannot be instantiated: " + generatedType)

          generatedType match {
            case arr @ ARRAY(elem) =>
              genNewArray(arr.elementKind, arr.dimensions, arguments)

            case rt @ REFERENCE(cls) =>
              genNew(cls, arguments,
                  atomForSymbol(fun.symbol) setPos fun.pos).setTailCallInfo(
                      computeTailCallInfo(fun.symbol))
          }

        case Apply(fun @ _, List(dynapply:ApplyDynamic))
        if (definitions.isUnbox(fun.symbol) &&
            hasPrimitiveReturnType(dynapply.symbol)) =>
          genApplyDynamic(dynapply, ctx, nobox = true)

        case app @ Apply(fun, args) =>
          val sym = fun.symbol

          if (sym.isLabel) {  // jump to a label
            if (settings.verbose.value)
              println("warning: jump found at "+tree.pos+", doing my best ...")

            val procVar = varForSymbol(sym)
            val arguments = args map { genExpression(_, ctx) }
            ast.Apply(procVar, arguments)
          } else if (isPrimitive(sym)) {
            // primitive operation
            genPrimitiveOp(app, ctx)
          } else if (sym == List_cons) {
            // inline head :: tail as ::(head, tail) to help 'tailcalls' phase
            val List(head) = args
            val Select(tail, _) = fun
            val arguments = List(head, tail).map(arg => genExpression(arg, ctx))
            val ctor = ConsClass.primaryConstructor
            genNew(ConsClass, arguments,
                atomForSymbol(ctor) setPos fun.pos).setTailCallInfo(
                      computeTailCallInfo(ctor))
          } else {  // normal method call
            if (settings.debug.value)
              log("Gen CALL_METHOD with sym: " + sym + " isStaticSymbol: " + sym.isStaticMember);

            if (sym == ctx.method.symbol)
              ctx.method.recursive = true

            val Select(receiver, _) = fun
            val instance = genExpression(receiver, ctx)
            val arguments = args map { genExpression(_, ctx) }
            val message = buildMessage(atomForSymbol(fun.symbol) setPos fun.pos,
                arguments)

            ast.Apply(instance, List(message)).setTailCallInfo(
                computeTailCallInfo(sym))
          }

        case ApplyDynamic(_, _) =>
          genApplyDynamic(tree, ctx)

        case This(qual) =>
          assert(tree.symbol == ctx.clazz.symbol || tree.symbol.isModuleClass,
                 "Trying to access the this of another class: " +
                 "tree.symbol = " + tree.symbol + ", ctx.clazz.symbol = " + ctx.clazz.symbol + " compilation unit:"+unit)

          if (tree.symbol.isModuleClass && tree.symbol != ctx.clazz.symbol) {
            if (settings.debug.value)
              log("LOAD_MODULE from 'This': " + tree.symbol);
            assert(!tree.symbol.isPackageClass, "Cannot use package as value: " + tree)
            genModule(tree.symbol, tree.pos)
          } else {
            ast.Self()
          }

        case Select(Ident(nme.EMPTY_PACKAGE_NAME), module) =>
          if (settings.debug.value) {
            assert(tree.symbol.isModule,
                   "Selection of non-module from empty package: " + tree.toString() +
                   " sym: " + tree.symbol +
                   " at: " + (tree.pos))
            log("LOAD_MODULE from Select(<emptypackage>): " + tree.symbol)
          }
          assert(!tree.symbol.isPackageClass, "Cannot use package as value: " + tree)
          genModule(tree.symbol, tree.pos)

        case Select(qualifier, selector) =>
          val sym = tree.symbol

          if (sym.isModule) {
            if (settings.debug.value)
              log("LOAD_MODULE from Select(qualifier, selector): " + sym)
            assert(!tree.symbol.isPackageClass, "Cannot use package as value: " + tree)
            genModule(sym, tree.pos)
          } else if (sym.isStaticMember) {
            genStaticMember(sym, tree.pos)
          } else {
            val symVar = varForSymbol(sym)

            if (qualifier.isInstanceOf[This] || symVar.isInstanceOf[ast.Variable])
              ast.At(symVar)
            else {
              val instance = genExpression(qualifier, ctx)
              val arguments = List(symVar)
              val message = buildMessage(
                  ast.Atom("$getPublic$") setPos tree.pos, arguments)

              ast.Apply(instance, List(message))
            }
          }

        case Ident(name) =>
          val sym = tree.symbol
          if (!sym.isPackage) {
            if (sym.isModule) {
              if (settings.debug.value)
                log("LOAD_MODULE from Ident(name): " + sym)
              assert(!sym.isPackageClass, "Cannot use package as value: " + tree)
              genModule(sym, tree.pos)
            } else {
              val value = varForSymbol(sym)

              if (hasSingleAssignSemantics(sym))
                value
              else
                ast.At(value)
            }
          } else
            ast.Atom("package")

        case Literal(value) =>
          value.tag match {
            case UnitTag =>
              ast.UnitVal()
            case BooleanTag =>
              ast.Bool(value.booleanValue)
            case ByteTag | ShortTag | CharTag | IntTag | LongTag =>
              ast.IntLiteral(value.longValue)
            case FloatTag | DoubleTag =>
              ast.FloatLiteral(value.doubleValue)
            case StringTag =>
              genBuiltinApply("StringLiteral",
                  ast.StringLiteral(value.stringValue))
            case NullTag =>
              ast.NullVal()
            case ClassTag =>
              genClassConstant(value.typeValue)
            case EnumTag =>
              genStaticMember(value.symbolValue)
          }

        case Block(stats, expr) =>
          val statements = stats map { genStatement(_, ctx) }
          val expression = genExpression(expr, ctx)

          ast.And((statements ++ List(expression)):_*)

        case Typed(Super(_, _), _) =>
          genExpression(This(ctx.clazz.symbol), ctx)

        case Typed(expr, _) =>
          genExpression(expr, ctx)

        case Assign(lhs @ Select(qualifier, _), rhs) =>
          val sym = lhs.symbol
          val symVar = varForSymbol(sym) setPos lhs.pos

          val assignment =
            if (sym.hasAnnotation(SingleAssignmentClass))
              ast.Eq(genExpression(lhs, ctx), genExpression(rhs, ctx))
            else if (sym.isStaticMember) {
              val instance = genModule(sym.owner, tree.pos)
              val accessor = ast.Atom(methodEncodedName(
                  sym.name.toString + "_$eq",
                  List(tree.tpe), UnitClass.tpe)) setPos tree.pos
              val arg = genExpression(rhs, ctx)
              val message = buildMessage(accessor, List(arg), ast.Wildcard())

              ast.Apply(instance, List(message))
            } else if (qualifier.isInstanceOf[This] || symVar.isInstanceOf[ast.Variable])
              ast.ColonEquals(symVar, genExpression(rhs, ctx))
            else {
              val instance = genExpression(qualifier, ctx)
              val arguments = List(symVar, genExpression(rhs, ctx))
              val message = ast.Tuple(
                  ast.Atom("$setPublic$") setPos tree.pos, arguments:_*)

              ast.Apply(instance, List(message))
            }

          ast.And(assignment, ast.UnitVal())

        case Assign(lhs, rhs) =>
          val sym = lhs.symbol
          val assignment = if (hasSingleAssignSemantics(sym))
            ast.Eq(varForSymbol(sym) setPos lhs.pos, genExpression(rhs, ctx))
          else
            ast.ColonEquals(varForSymbol(sym) setPos lhs.pos,
                genExpression(rhs, ctx))
          ast.And(assignment, ast.UnitVal())

        case ArrayValue(tpt @ TypeTree(), elems) =>
          val elementKind = toTypeKind(tpt.tpe)
          val componentClass = elementKind.toType
          val length = elems.length
          val elements = elems map (elem => genExpression(elem, ctx))

          genBuiltinApply("ArrayValue", genClassConstant(componentClass),
              ast.IntLiteral(length), ast.ListLiteral(elements:_*))

        case Match(selector, cases) =>
          if (settings.debug.value)
            log("Generating SWITCH statement.")

          val expr = genExpression(selector, ctx)

          var clauses: List[ast.CaseClause] = Nil
          var elseClause: ast.OptElse = ast.NoElse() setPos tree.pos

          for (caze @ CaseDef(pat, guard, body) <- cases) {
            assert(guard == EmptyTree)

            val bodyAST = genExpression(body, ctx)

            pat match {
              case lit: Literal =>
                clauses = ast.CaseClause(genExpression(lit, ctx),
                    bodyAST) :: clauses
              case Ident(nme.WILDCARD) =>
                elseClause = bodyAST
              case _ =>
                abort("Invalid case statement in switch-like pattern match: " +
                    tree + " at: " + (tree.pos))
            }
          }

          ast.Case(expr, clauses.reverse, elseClause)

        case EmptyTree =>
          ast.UnitVal()

        case _ =>
          abort("Unexpected tree in genLoad: " + tree + "/" + tree.getClass +
              " at: " + tree.pos)
      }) setDefaultPos tree.pos
    }

    private def genStatement(tree: Tree, ctx: Context): ast.Phrase = {
      blackholeReturnedValue(genExpression(tree, ctx))
    }

    lazy val isThreadMethod = makeThreadMethods()

    def makeThreadMethods() = {
      import definitions.{ getClass, getMember }
      val module = definitions.getModule("scala.ozma.package")

      val suffs = for (kind <- primitiveKinds)
        yield "$m" + kind.primitiveCharCode + "c$sp"
      val suffixes = "" :: suffs

      val methods = for (suffix <- suffixes)
        yield getMember(module, "thread" + suffix)

      methods.toSet
    }

    private def isPrimitive(sym: Symbol) = {
      (scalaPrimitives.isPrimitive(sym) && (sym ne definitions.String_+)) || (
          isThreadMethod(sym))
    }

    private def genPrimitiveOp(tree: Apply, ctx: Context): ast.Phrase = {
      val sym = tree.symbol
      val Apply(fun @ Select(receiver, _), args) = tree

      if (isThreadMethod(sym))
        ast.Thread(genExpression(args.head, ctx))
      else {
        import scalaPrimitives._

        val code = scalaPrimitives.getPrimitive(sym, receiver.tpe)

        if (isArithmeticOp(code) || isLogicalOp(code) || isComparisonOp(code))
          genSimpleOp(tree, receiver :: args, ctx, code)
        else if (code == scalaPrimitives.CONCAT)
          genStringConcat(tree, receiver, args, ctx)
        else if (code == HASH)
          genScalaHash(tree, receiver, ctx)
        else if (isArrayOp(code))
          genArrayOp(tree, ctx, code)
        else if (code == SYNCHRONIZED)
          genSynchronized(tree, ctx)
        else if (isCoercion(code))
          genCoercion(tree, receiver, ctx, code)
        else
          abort("Primitive operation not handled yet: " + sym.fullName + "(" +
              fun.symbol.simpleName + ") " + " at: " + (tree.pos))
      }
    }

    private def genSimpleOp(tree: Apply, args: List[Tree], ctx: Context,
        code: Int): ast.Phrase = {
      import scalaPrimitives._

      val sources = args map (arg => genExpression(arg, ctx))

      sources match {
        // Unary operation
        case List(source) =>
          (code match {
            case POS =>
              source // nothing to do
            case NEG =>
              ast.UnaryOpApply("~", source)
            case NOT =>
              genBuiltinApply("BinNot", source)
            case ZNOT =>
              genBuiltinApply("Not", source)
            case _ =>
              abort("Unknown unary operation code: " + code)
          }) setPos tree.pos

        // Binary operation
        case List(lsrc, rsrc) =>
          lazy val leftKind = toTypeKind(args.head.tpe)

          def divOperator = leftKind match {
            case _:INT => "div"
            case _:FLOAT => "/"
          }

          def genEquality(eqeq: Boolean, not: Boolean) = {
            if (eqeq && leftKind.isReferenceType) {
              val body = genEqEqPrimitive(args(0), args(1), lsrc, rsrc, ctx)
              if (not) genBuiltinApply("Not", body) else body
            } else
              ast.BinaryOpApply(if (not) "\\=" else "==", lsrc, rsrc)
          }

          (code match {
            case ADD => ast.BinaryOpApply("+", lsrc, rsrc)
            case SUB => ast.BinaryOpApply("-", lsrc, rsrc)
            case MUL => ast.BinaryOpApply("*", lsrc, rsrc)
            case DIV => ast.BinaryOpApply(divOperator, lsrc, rsrc)
            case MOD => ast.BinaryOpApply("mod", lsrc, rsrc)
            case OR => genBuiltinApply("BinOr", lsrc, rsrc)
            case XOR => genBuiltinApply("BinXor", lsrc, rsrc)
            case AND => genBuiltinApply("BinAnd", lsrc, rsrc)
            case LSL => genBuiltinApply("LSL", lsrc, rsrc)
            case LSR => genBuiltinApply("LSR", lsrc, rsrc)
            case ASR => genBuiltinApply("ASR", lsrc, rsrc)
            case LT => ast.BinaryOpApply("<", lsrc, rsrc)
            case LE => ast.BinaryOpApply("=<", lsrc, rsrc)
            case GT => ast.BinaryOpApply(">", lsrc, rsrc)
            case GE => ast.BinaryOpApply(">=", lsrc, rsrc)
            case EQ => genEquality(eqeq = true, not = false)
            case NE => genEquality(eqeq = true, not = true)
            case ID => genEquality(eqeq = false, not = false)
            case NI => genEquality(eqeq = false, not = true)
            case ZOR => ast.OrElse(lsrc, rsrc)
            case ZAND => ast.AndThen(lsrc, rsrc)
            case _ =>
              abort("Unknown binary operation code: " + code)
          }) setPos tree.pos

        case _ =>
          abort("Too many arguments for primitive function: " + tree)
      }
    }

    def genEqEqPrimitive(l: Tree, r: Tree, lsrc: ast.Phrase, rsrc: ast.Phrase,
        ctx: Context) = {
      /** True if the equality comparison is between values that require the use of the rich equality
        * comparator (scala.runtime.Comparator.equals). This is the case when either side of the
        * comparison might have a run-time type subtype of java.lang.Number or java.lang.Character.
        * When it is statically known that both sides are equal and subtypes of Number of Character,
        * not using the rich equality is possible (their own equals method will do ok.)*/
      def mustUseAnyComparator: Boolean = {
        def areSameFinals = l.tpe.isFinalType && r.tpe.isFinalType && (l.tpe =:= r.tpe)
        !areSameFinals && isMaybeBoxed(l.tpe.typeSymbol) && isMaybeBoxed(r.tpe.typeSymbol)
      }

      val function = if (mustUseAnyComparator) "AnyEqEq" else "AnyRefEqEq"
      genBuiltinApply(function, lsrc, rsrc)
    }

    private def genStringConcat(tree: Apply, receiver: Tree, args: List[Tree],
        ctx: Context): ast.Phrase = {
      val List(arg) = args

      val instance = genExpression(receiver, ctx)
      val boxed = makeBox(instance, receiver.tpe)

      val messageToString = buildMessage(atomForSymbol(Object_toString), Nil)
      val stringInstance = ast.Apply(boxed, List(messageToString))

      val plusMessage = buildMessage(atomForSymbol(definitions.String_+),
          List(genExpression(args.head, ctx)))
      ast.Apply(stringInstance, List(plusMessage))
    }

    private def makeBox(expr: ast.Phrase, tpe: Type) =
      makeBoxUnbox(expr, tpe, "box")

    private def makeUnbox(expr: ast.Phrase, tpe: Type) =
      makeBoxUnbox(expr, tpe, "unbox")

    private def makeBoxUnbox(expr: ast.Phrase, tpe: Type, function: String) = {
      val module = tpe.typeSymbol.companionModule
      val boxSymbol = definitions.getMember(module, function)
      val messageBox = buildMessage(atomForSymbol(boxSymbol), List(expr))
      ast.Apply(genModule(module), List(messageBox))
    }

    private def genScalaHash(tree: Apply, receiver: Tree,
        ctx: Context): ast.Phrase = {
      val instance = varForSymbol(ScalaRunTimeModule) setPos tree.pos
      val arguments = List(genExpression(receiver, ctx),
          ast.Dollar() setPos tree.pos)
      val sym = getMember(ScalaRunTimeModule, "hash")
      val message = ast.Tuple(atomForSymbol(sym) setPos tree.pos,
          arguments:_*)

      ast.Apply(instance, List(message)) setPos tree.pos
    }

    private def genArrayOp(tree: Tree, ctx: Context, code: Int) = {
      import scalaPrimitives._

      val Apply(Select(arrayObj, _), args) = tree
      val arrayValue = genExpression(arrayObj, ctx)
      val arguments = args map (arg => genExpression(arg, ctx))

      if (scalaPrimitives.isArrayGet(code)) {
        // get an item of the array
        if (settings.debug.value)
          assert(args.length == 1,
                 "Too many arguments for array get operation: " + tree)

        val message = buildMessage(ast.Atom("get"), arguments)
        ast.Apply(arrayValue, List(message))
      }
      else if (scalaPrimitives.isArraySet(code)) {
        // set an item of the array
        if (settings.debug.value)
          assert(args.length == 2,
                 "Too many arguments for array set operation: " + tree)

        val message = ast.Tuple(ast.Atom("put"), arguments:_*)
        ast.And(ast.Apply(arrayValue, List(message)), ast.UnitVal())
      }
      else {
        // length of the array
        val message = buildMessage(ast.Atom("length"), Nil)
        ast.Apply(arrayValue, List(message))
      }
    }

    private def genSynchronized(tree: Apply, ctx: Context): ast.Phrase = {
      val Apply(Select(receiver, _), args) = tree
      val receiverExpr = genExpression(receiver, ctx)
      val body = genExpression(args.head, ctx)

      val proc = ast.Fun(ast.Dollar(), Nil, body)
      val message = buildMessage(ast.Atom("synchronized"), List(proc))

      ast.Apply(receiverExpr, List(message))
    }

    private def genCoercion(tree: Apply, receiver: Tree, ctx: Context,
        code: Int): ast.Phrase = {
      import scalaPrimitives._

      val source = genExpression(receiver, ctx)

      (code: @scala.annotation.switch) match {
        case B2F | B2D | S2F | S2D | C2F | C2D | I2F | I2D | L2F | L2D =>
          genBuiltinApply("IntToFloat", source) setPos tree.pos

        case F2B | F2S | F2C | F2I | F2L | D2B | D2S | D2C | D2I | D2L =>
          genBuiltinApply("FloatToInt", source) setPos tree.pos

        case _ => source
      }
    }

    private def genApplyDynamic(tree: Tree, ctx: Context,
        nobox: Boolean = false): ast.Phrase = {
      val sym = tree.symbol
      val ApplyDynamic(receiver, args) = tree

      val instance = genExpression(receiver, ctx)
      val arguments = genApplyDynamicArgs(sym, args, ctx)
      val message = buildMessage(atomForSymbol(sym) setPos tree.pos,
          arguments)

      val apply = ast.Apply(instance, List(message)).setTailCallInfo(
          computeTailCallInfo(sym))

      val returnType = returnTypeOf(sym)
      if (nobox || !isPrimitiveKind(toTypeKind(returnType)))
        apply
      else
        makeBox(apply, returnType)
    }

    private def genApplyDynamicArgs(sym: Symbol, args: List[Tree],
        ctx: Context) = {
      val types = sym.tpe match {
        case MethodType(params, _) => params map (_.tpe)
        case NullaryMethodType(_) => Nil
      }

      args zip types map { case (arg, tpe) =>
        if (isPrimitiveKind(toTypeKind(tpe)))
          unboxDynamicParam(arg, tpe, ctx)
        else
          genExpression(arg, ctx)
      }
    }

    private def isPrimitiveKind(kind: TypeKind) = kind match {
      case BOOL | _:INT | _:FLOAT => true
      case _ => false
    }

    private def returnTypeOf(sym: Symbol) = sym.tpe match {
      case MethodType(_, tpe) => tpe
      case NullaryMethodType(tpe) => tpe
    }

    private def hasPrimitiveReturnType(sym: Symbol) =
      isPrimitiveKind(toTypeKind(returnTypeOf(sym)))

    private def unboxDynamicParam(tree: Tree, tpe: Type,
        ctx: Context): ast.Phrase = {
      (tree: @unchecked) match {
        case Apply(_, List(result)) if (definitions.isBox(tree.symbol)) =>
          genExpression(result, ctx)
        case _ => makeUnbox(genExpression(tree, ctx), tpe)
      }
    }

    private def blackholeReturnedValue(expr: ast.Phrase): ast.Phrase = {
      expr match {
        case _:ast.Constant => ast.Skip() setPos expr.pos

        case ast.And(statement, _:ast.RecordLabel) => statement

        case and:ast.And =>
          val last :: others = and.phrases.toList.reverse
          val newLast = blackholeReturnedValue(last)
          val newOthers = others filterNot (_.isInstanceOf[ast.Skip])
          val newStatements = (newLast :: newOthers).reverse
          ast.And(newStatements:_*)

        case ast.IfThenElse(condition, thenStats, elseStats: ast.Phrase) =>
          ast.IfThenElse(condition,
              blackholeReturnedValue(thenStats),
              blackholeReturnedValue(elseStats)) setPos expr.pos

        case ast.Raise(_) => expr

        case _ => ast.Eq(ast.Wildcard(), expr) setPos expr.pos
      }
    }

    private def genModule(sym: Symbol, pos: Position = NoPosition) = {
      val symbol = if (sym.isModuleClass) sym.companionModule else sym
      ast.Apply(varForModule(symbol) setPos pos, Nil) setPos pos
    }

    private def genStaticMember(sym: Symbol, pos: Position = NoPosition) = {
      val instance = genModule(sym.owner, pos)
      val accessor = ast.Atom(methodEncodedName(sym.name.toString,
          Nil, sym.tpe)) setPos pos
      val message = buildMessage(accessor, Nil)

      ast.Apply(instance, List(message))
    }

    def genConversion(from: TypeKind, to: TypeKind, value: ast.Phrase) = {
      def int0 = ast.IntLiteral(0) setPos value.pos
      def int1 = ast.IntLiteral(1) setPos value.pos
      def float0 = ast.FloatLiteral(0.0) setPos value.pos
      def float1 = ast.FloatLiteral(1.0) setPos value.pos

      (from, to) match {
        case _ if from eq to => value

        case (_:INT, _:FLOAT) => genBuiltinApply("IntToFloat", value)
        case (_:FLOAT, _:INT) => genBuiltinApply("FloatToInt", value)

        case (_:INT, BOOL) => ast.BinaryOpApply("\\=", value, int0)
        case (_:FLOAT, BOOL) => ast.BinaryOpApply("\\=", value, float0)

        case (BOOL, _:INT) => ast.IfThenElse(value, int1, int0)
        case (BOOL, _:FLOAT) => ast.IfThenElse(value, float1, float0)
      }
    }

    def genCast(from: Type, to: Type, value: ast.Phrase, cast: Boolean) = {
      val classConstant = genClassConstant(to) setPos value.pos
      if (cast) {
        genBuiltinApply("AsInstance", value,
            classConstant).setTailCallInfo(List(0))
      } else {
        genBuiltinApply("IsInstance", value, classConstant)
      }
    }

    private def genTry(tree: Try, ctx: Context): ast.Phrase = {
      val Try(block, catches, finalizer) = tree

      val blockAST = genExpression(block, ctx)

      val clauses = for (CaseDef(pat, _, body) <- catches) yield {
        val bodyAST = genExpression(body, ctx)

        def genCaseClause(tpe: Type, E: ast.Phrase = ast.Variable("E")) = {
          val isInstance = genBuiltinApply("IsInstance", E,
              genClassConstant(tpe))

          val pat = ast.Record(ast.Atom("error"),
              ast.Tuple(ast.Atom("throwable"), E),
              ast.Colon(ast.Atom("debug"), ast.Wildcard()))

          val pattern =
            ast.SideCondition(pat, ast.Skip(), isInstance)
          ast.CaseClause(pattern, bodyAST)
        }

        pat match {
          case Typed(Ident(nme.WILDCARD), tpt) =>
            genCaseClause(tpt.tpe)
          case Ident(nme.WILDCARD) =>
            genCaseClause(ThrowableClass.tpe)
          case Bind(name, _) =>
            genCaseClause(pat.symbol.tpe, varForSymbol(pat.symbol))
        }
      }

      val catchesAST = if (clauses.isEmpty)
        ast.NoCatch()
      else
        ast.Catch(clauses)

      val finalizerAST = genStatement(finalizer, ctx) match {
        case ast.Skip() => ast.NoFinally()
        case ast => ast
      }

      ast.Try(blockAST, catchesAST, finalizerAST)
    }

    /////////////////// Context ///////////////////////

    class Context {
      /** The current package. */
      var packg: Name = _

      /** The current class. */
      var clazz: OzClass = _

      /** The current method. */
      var method: OzMethod = _

      /** Current method definition. */
      var defdef: DefDef = _

      var isModuleInitialized = false

      def this(other: Context) = {
        this()
        this.packg = other.packg
        this.clazz = other.clazz
        this.method = other.method
      }

      override def toString(): String = {
        val buf = new StringBuilder()
        buf.append("\tpackage: ").append(packg).append('\n')
        buf.append("\tclazz: ").append(clazz).append('\n')
        buf.append("\tmethod: ").append(method).append('\n')
        buf.toString()
      }

      def setPackage(p: Name): this.type = {
        this.packg = p
        this
      }

      def setClass(c: OzClass): this.type = {
        this.clazz = c
        this
      }

      def setMethod(m: OzMethod): this.type = {
        this.method = m
        this
      }

      /** Prepare a new context upon entry into a method.
       *
       *  @param m ...
       *  @param d ...
       *  @return  ...
       */
      def enterMethod(m: OzMethod, d: DefDef): Context = {
        val ctx1 = new Context(this) setMethod(m)
        ctx1.defdef = d
        ctx1
      }
    }
  }
}
