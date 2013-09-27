package miniboxing.plugin

import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.Plugin
import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.transform.InfoTransform
import scala.tools.nsc.transform.TypingTransformers

/** Specialization hijacking component `@specialized T -> @miniboxed T` */
trait HijackComponent extends
    PluginComponent
    with MiniboxInfoHijack
    with MiniboxDefinitions {

  def flag_hijack_spec: Boolean
}

/** Duplicator component `def t -> def t_L, def t_J` */
trait MiniboxDuplComponent extends
    PluginComponent
    with MiniboxLogic
    with MiniboxDuplInfoTransformation
    with MiniboxLogging
    with MiniboxDuplTreeTransformation
    with MiniboxTreeSpecializer
    with MiniboxPeepholeTransformation
    with MiniboxSpecializationInfo
    with MiniboxDefinitions {

  def mboxDuplPhase: StdPhase

  def afterMinibox[T](op: => T): T =
    global.afterPhase(mboxDuplPhase)(op)

  def beforeMinibox[T](op: => T): T =
    global.beforePhase(mboxDuplPhase)(op)

  def flag_log: Boolean
  def flag_debug: Boolean
  def flag_stats: Boolean
  def flag_spec_no_opt: Boolean
  def flag_loader_friendly: Boolean
}

/** Specializer component `T @storage -> Long` */
trait MiniboxSpecComponent extends
    PluginComponent
    with MiniboxPostInfoTransformer
    with MiniboxPostTreeTransformer {

  val minibox: MiniboxDuplComponent { val global: MiniboxSpecComponent.this.global.type }
  def flag_log: Boolean
  def flag_debug: Boolean
  def flag_stats: Boolean
}

/** Main miniboxing class */
class Minibox(val global: Global) extends Plugin {
  import global._

  val name = "minibox"
  val description = "spcializes generic classes"

  val components = List[PluginComponent](HijackPhase, MiniboxDuplPhase, MiniboxSpecPhase)

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
    s"  -P:${name}:debug             debug logging for the miniboxing plugin (rarely used)" +
    s"  -P:${name}:hijack-spec       hijack the @specialized(...) notation for miniboxing" +
    s"  -P:${name}:spec-no-opt       don't optimize method specialization, do create useless specializations" +
    s"  -P:${name}:loader            generate classloader-friendly code (but more verbose)")

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

  private object MiniboxDuplPhase extends MiniboxDuplComponent {
    val global: Minibox.this.global.type = Minibox.this.global
    val runsAfter = List("refchecks")
    override val runsRightAfter = Some("uncurry")
    val phaseName = Minibox.this.name + "-dupl"

    def flag_log = Minibox.this.flag_log
    def flag_debug = Minibox.this.flag_debug
    def flag_stats = Minibox.this.flag_stats
    def flag_spec_no_opt = Minibox.this.flag_spec_no_opt
    def flag_loader_friendly = Minibox.this.flag_loader_friendly

    var mboxDuplPhase : StdPhase = _
    override def newPhase(prev: scala.tools.nsc.Phase): StdPhase = {
      mboxDuplPhase = new Phase(prev)
      mboxDuplPhase
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

  private object MiniboxSpecPhase extends {
    val minibox: MiniboxDuplPhase.type = MiniboxDuplPhase
  } with MiniboxSpecComponent {
    val global: Minibox.this.global.type = Minibox.this.global
    val runsAfter = List(MiniboxDuplPhase.phaseName)
    override val runsRightAfter = Some(MiniboxDuplPhase.phaseName)
    val phaseName = Minibox.this.name + "-spec"

    def flag_log = Minibox.this.flag_log
    def flag_debug = Minibox.this.flag_debug
    def flag_stats = Minibox.this.flag_stats

    var mboxSpecPhase : StdPhase = _
    override def newPhase(prev: scala.tools.nsc.Phase): StdPhase = {
      mboxSpecPhase = new Phase(prev)
      mboxSpecPhase
    }
  }
}
