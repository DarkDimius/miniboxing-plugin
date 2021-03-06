package miniboxing.plugin

import scala.tools.nsc
import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.Plugin
import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.symtab.Flags
import scala.tools.nsc.transform.InfoTransform
import scala.tools.nsc.transform.TypingTransformers

trait MiniboxComponent extends
    PluginComponent
    with MiniboxLogic
    with MiniboxInfoTransformation
    with MiniboxLogging
    with MiniboxTreeTransformation
    with MiniboxTreeSpecializer
    with MiniboxPeepholeTransformation
    with MiniboxSpecializationInfo
    with MiniboxDefinitions {

  def mboxPhase: StdPhase

  def afterMinibox[T](op: => T): T =
    global.afterPhase(mboxPhase)(op)

  def beforeMinibox[T](op: => T): T =
    global.beforePhase(mboxPhase)(op)

  def flag_log: Boolean
  def flag_debug: Boolean
  def flag_stats: Boolean
  def flag_spec_no_opt: Boolean
  def flag_loader_friendly: Boolean
}

trait HijackComponent extends
    PluginComponent
    with MiniboxInfoHijack
    with MiniboxDefinitions {

  def flag_hijack_spec: Boolean
}

trait PreTyperComponent extends
  PluginComponent with
  TypingTransformers {
  val miniboxing: MiniboxComponent { val global: PreTyperComponent.this.global.type }
}

trait PostTyperComponent extends
  PluginComponent with
  TypingTransformers {

  import global._; import Flags._
  val miniboxing: MiniboxComponent { val global: PostTyperComponent.this.global.type }
}

class Minibox(val global: Global) extends Plugin {
  import global._

  val name = "minibox"
  val description = "specializes generic classes"

  val components = List[PluginComponent](HijackPhase, MiniboxPhase, PreTyperPhase, PostTyperPhase)

  var flag_log = sys.props.get("miniboxing.log").isDefined
  var flag_debug = sys.props.get("miniboxing.debug").isDefined
  var flag_stats = sys.props.get("miniboxing.stats").isDefined
  var flag_hijack_spec = sys.props.get("miniboxing.hijack.spec").isDefined
  var flag_spec_no_opt = sys.props.get("miniboxing.spec.no-opt").isDefined
  var flag_loader_friendly = sys.props.get("miniboxing.loader").isDefined

  override def processOptions(options: List[String], error: String => Unit) {
    for (option <- options) {
      if (option.toLowerCase() == "log")
        flag_log = true
      else if (option.toLowerCase() == "debug")
        flag_debug = true
      else if (option.toLowerCase() == "stats")
        flag_stats = true
      else if (option.toLowerCase() == "hijack")
        flag_hijack_spec = true
      else if (option.toLowerCase() == "spec-no-opt")
        flag_spec_no_opt = true
      else if (option.toLowerCase() == "loader")
        flag_loader_friendly = true
      else
        error("Miniboxing: Option not understood: " + option)
    }
  }

  override val optionsHelp: Option[String] = Some(
    s"  -P:${name}:log               log miniboxing signature transformations\n" +
    s"  -P:${name}:stats             log miniboxing tree transformations (verbose logging)\n" +
    s"  -P:${name}:debug             debug logging for the miniboxing plugin (rarely used)\n" +
    s"  -P:${name}:hijack            hijack the @specialized(...) notation for miniboxing\n" +
    s"  -P:${name}:spec-no-opt       don't optimize method specialization, do create useless specializations\n" +
    s"  -P:${name}:loader            generate classloader-friendly code (but more verbose)\n")

  private object MiniboxPhase extends MiniboxComponent {

    val global: Minibox.this.global.type = Minibox.this.global
    val runsAfter = List("refchecks")
    override val runsRightAfter = Some("uncurry")
    val phaseName = Minibox.this.name

    def flag_log = Minibox.this.flag_log
    def flag_debug = Minibox.this.flag_debug
    def flag_stats = Minibox.this.flag_stats
    def flag_spec_no_opt = Minibox.this.flag_spec_no_opt
    def flag_loader_friendly = Minibox.this.flag_loader_friendly

    var mboxPhase : StdPhase = _
    override def newPhase(prev: scala.tools.nsc.Phase): StdPhase = {
      mboxPhase = new Phase(prev)
      mboxPhase
    }

    override def newTransformer(unit: CompilationUnit): Transformer = new Transformer {
      override def transform(tree: Tree) = {
        // execute the tree transformer after all symbols have been processed
        val tree1 = afterMinibox(new MiniboxTreeTransformer(unit).transform(tree))
        val tree2 = afterMinibox(new MiniboxPeepholeTransformer(unit).transform(tree1))
        tree2.foreach(tree => assert(tree.tpe != null, tree))
        tree2
      }
    }
  }

  private object HijackPhase extends HijackComponent {
    val global: Minibox.this.global.type = Minibox.this.global
    val runsAfter = List("typer")
    override val runsRightAfter = Some("extmethods")
    val phaseName = "hijacker"

    def flag_hijack_spec = Minibox.this.flag_hijack_spec

    // no change
    override def newTransformer(unit: CompilationUnit): Transformer = new Transformer {
      override def transform(tree: Tree) = tree
    }
  }

  private object PreTyperPhase extends {
    val miniboxing: MiniboxPhase.type = MiniboxPhase
  } with PreTyperComponent {
    val global: Minibox.this.global.type = Minibox.this.global
    val runsAfter = List()
    override val runsRightAfter = Some("parser")
    val phaseName = "mb-pretyper"

    def newPhase(_prev: Phase) = new StdPhase(_prev) {
      override def name = PreTyperPhase.phaseName
      def apply(unit: CompilationUnit) {
        import global._; import Flags._
        for (sym <- miniboxing.specializedBase)
          if (miniboxing.originalTraitFlag(sym))
            sym.resetFlag(ABSTRACT)
          else
            sym.resetFlag(ABSTRACT | TRAIT)
      }
    }
  }

  private object PostTyperPhase extends {
    val miniboxing: MiniboxPhase.type = MiniboxPhase
  } with PreTyperComponent {
    val global: Minibox.this.global.type = Minibox.this.global
    val runsAfter = List("typer")
    //override val runsRightAfter = Some("typer")
    val phaseName = "mb-posttyper"

    def newPhase(_prev: Phase) = new StdPhase(_prev) {
      override def name = PostTyperPhase.phaseName
      def apply(unit: CompilationUnit) {
        import global._; import Flags._
        for (sym <- miniboxing.specializedBase)
          sym.setFlag(ABSTRACT | TRAIT)
      }
    }
  }
}
