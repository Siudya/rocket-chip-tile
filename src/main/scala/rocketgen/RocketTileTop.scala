package rocketgen
import chisel3._
import chisel3.stage.ChiselGeneratorAnnotation
import freechips.rocketchip.diplomacy.RegionType.TRACKED
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.rocket.{DCacheParams, ICacheParams, MulDivParams, PgLevels, RocketCoreParams}
import freechips.rocketchip.subsystem.{CacheBlockBytes, RocketCrossingParams, SystemBusKey}
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import circt.stage.{ChiselStage, FirtoolOption}

case object RocketTileParamsKey extends Field[RocketTileParams]
case object MngParamsKey extends Field[TLSlavePortParameters]
case object CliParamsKey extends Field[TLMasterPortParameters]

class RocketTileCfg extends Config((site, here, up) => {
  case TileKey => RocketTileParams(
    core = RocketCoreParams(mulDiv = Some(MulDivParams(
      mulUnroll = 8,
      mulEarlyOut = true,
      divEarlyOut = true))),
    dcache = Some(DCacheParams()),
    icache = Some(ICacheParams())
  )
  case BuildRoCC => Seq()
  case MngParamsKey => TLSlavePortParameters.v1(
    managers = Seq(TLSlaveParameters.v1(
      address            = AddressSet.misaligned(0, 1L << 33),
      resources          = new SimpleBus("master", Nil).ranges,
      executable         = true,
      supportsAcquireT   = TransferSizes(64, 64),
      supportsAcquireB   = TransferSizes(64, 64),
      supportsGet        = TransferSizes(1, 64),
      supportsPutFull    = TransferSizes(1, 64),
      supportsPutPartial = TransferSizes(1, 64),
      supportsArithmetic = TransferSizes(1, 8),
      supportsLogical    = TransferSizes(1, 8),
      regionType         = TRACKED)),
    beatBytes = 32,
    endSinkId = 1024,
    minLatency = 16
  )
  case CliParamsKey => TLMasterPortParameters.v1(
    clients = Seq(TLMasterParameters.v1(
      name = "master",
      sourceId = IdRange(0, 16),
      supportsProbe = TransferSizes(1, 4),
      supportsGet = TransferSizes(1, 4),
      supportsPutFull    = TransferSizes(1, 4),
      supportsPutPartial = TransferSizes(1, 4),
    ))
  )
  case MaxHartIdBits => 16
  case XLen => 64
  case PgLevels => 3
})

class RocketTileTop(implicit p:Parameters) extends LazyModule with BindingScope
  with HasNonDiplomaticTileParameters {
  private val tile = LazyModule(tileParams
    .asInstanceOf[RocketTileParams]
    .instantiate(
      RocketCrossingParams(),
      PriorityMuxHartIdFromSeq(Seq(tileParams))
    )
  )

  private val tlmNode = TLManagerNode(Seq(p(MngParamsKey)))
  tlmNode := tile.masterNode

  private val resetVectorNode = BundleBridgeSource(tile.frontend.resetVectorSinkNode.genOpt)
  tile.resetVectorNode := resetVectorNode

  private val intInwardNode = IntSourceNode(IntSourcePortSimple(5))
  tile.intInwardNode := intInwardNode

  private val nmiNode = BundleBridgeSource[NMI](tile.nmiSinkNode.genOpt)
  tile.nmiNode := nmiNode

  private val traceNode = BundleBridgeSink(tile.traceSourceNode.genOpt)
  traceNode := tile.traceNode

  private val bpwatchNode = BundleBridgeSink(tile.bpwatchSourceNode.genOpt)
  bpwatchNode := tile.bpwatchNode

  private val hartIdNode = BundleBridgeSource(() => UInt(p(MaxHartIdBits).W))
  tile.hartIdNode :=* hartIdNode

  private val wfiNode = IntSinkNode(IntSinkPortSimple())
  wfiNode :=* tile.wfiNode

  private val haltNode = IntSinkNode(IntSinkPortSimple())
  haltNode :=* tile.haltNode

  private val ceaseNode = IntSinkNode(IntSinkPortSimple())
  ceaseNode :=* tile.ceaseNode

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val tlm = tlmNode.makeIOs()
    val reset_vector = resetVectorNode.makeIOs()
    val intr_in = intInwardNode.makeIOs()
    val nmi = nmiNode.makeIOs()
    val trace = traceNode.makeIOs()
    val bpwatch = bpwatchNode.makeIOs()
    val hartId = hartIdNode.makeIOs()
    val wfi = wfiNode.makeIOs()
    val halt = haltNode.makeIOs()
    val cease = ceaseNode.makeIOs()
  }
}


object TopMain extends App {
  val cfg = new RocketTileCfg
  private val tile = DisableMonitors(p => LazyModule(new RocketTileTop()(p)))(cfg)
  (new ChiselStage).execute(args, Seq(
    FirtoolOption("-O=release"),
    FirtoolOption("--disable-all-randomization"),
    FirtoolOption("--disable-annotation-unknown"),
    FirtoolOption("--strip-debug-info"),
    FirtoolOption("--lower-memories"),
    FirtoolOption("--add-vivado-ram-address-conflict-synthesis-bug-workaround"),
    FirtoolOption("--lowering-options=noAlwaysComb," +
      " disallowPortDeclSharing, disallowLocalVariables," +
      " emittedLineLength=120, explicitBitcast, locationInfoStyle=plain," +
      " disallowExpressionInliningInPorts, disallowMuxInlining"),
    ChiselGeneratorAnnotation(() => tile.module)
  ))
}