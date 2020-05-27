// See LICENSE for license details.

package plugin

import scala.tools.nsc
import nsc.{Global, Phase}
import nsc.plugins.Plugin
import nsc.plugins.PluginComponent
import scala.reflect.internal.Flags
import scala.tools.nsc.transform.TypingTransformers

class ChiselPlugin(val global: Global) extends Plugin {
  val name = "chiselplugin"
  val description = "chisel's plugin"
  val components = List[PluginComponent](new ChiselComponent(global)/*, new Namer(global)*/)
}

class ChiselComponent(val global: Global) extends PluginComponent with TypingTransformers {
  import global._
  val runsAfter = List[String]("uncurry")
  override val runsRightAfter: Option[String] = Some("uncurry")
  val phaseName: String = "chiselcomponent"
  def newPhase(_prev: Phase): ChiselComponentPhase = new ChiselComponentPhase(_prev)
  class ChiselComponentPhase(prev: Phase) extends StdPhase(prev) {
    override def name: String = phaseName
    def apply(unit: CompilationUnit): Unit = {
      unit.body = new MyTypingTransformer(unit).transform(unit.body)
    }
  }

  class MyTypingTransformer(unit: CompilationUnit)
    extends TypingTransformer(unit) {

    // Determines if a type has a given parent trait
    def typeHasTrait(s: Type, name: String): Boolean = {
      s.parents.exists { p =>
        p.toString().toString == name  || typeHasTrait(p, name)
      }
    }

    // Utility function to help debug compiler plugin
    def serializeError(original: ValDef, modified: ValDef): Unit = {
      global.reporter.error(modified.pos, show(modified))
      writeAST("originalRaw", showRaw(original))
      write("original", show(original))
      writeAST("modifiedRaw", showRaw(modified))
      write("modified", show(modified))
    }

    // Indicates whether a ValDef is properly formed to get name
    def okVal(dd: ValDef): Boolean = {

      // These were found through trial and error
      def okFlags(mods: Modifiers): Boolean = {
        val badFlags = Set(
          Flag.PARAM,
          Flag.SYNTHETIC,
          Flag.DEFERRED,
          Flags.TRIEDCOOKING,
          Flags.CASEACCESSOR,
          Flags.PARAMACCESSOR
        )
        badFlags.forall{ x => !mods.hasFlag(x)}
      }

      // Ensure expression isn't null, as you can't call `null.pluginName("myname")`
      val isNull = dd.rhs match {
        case Literal(Constant(null)) => true
        case _ => false
      }
      okFlags(dd.mods) && typeHasTrait(dd.tpt.tpe, "chisel3.internal.HasId") && !isNull && dd.rhs != EmptyTree
    }

    // Method called by the compiler to modify source tree
    override def transform(tree: Tree): Tree = tree match {
      case dd @ ValDef(mods, name, tpt, rhs) if okVal(dd) && !localTyper.context.reporter.hasErrors =>
        val TermName(str: String) = name
        val ret = try {
          // Select the right function to call
          val sel = localTyper.typed1(
            Select(rhs, TermName("pluginName")),
            nsc.EXPRmode,
            MethodType(List(definitions.StringTpe.typeSymbol), tpt.tpe)
          )
          // Call the function
          val appl = localTyper.doTypedApply(
            rhs,
            sel,
            List(Literal(Constant(str))),
            nsc.EXPRmode,
            tpt.tpe
          )
          // Return modified tree
          treeCopy.ValDef(dd, mods, name, tpt, appl)
        } catch {
          case e: TypeError => throw new TypeError(dd.pos, e.msg + "\n" + showRaw(dd) + "\n" + dd.pos.toString)
        }
        ret
      case _ => super.transform(tree)
    }
  }
}