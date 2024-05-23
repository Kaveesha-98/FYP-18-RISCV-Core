//package pipeline

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxResource
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO
import com.sun.org.apache.xpath.internal.operations

import pipeline.ports._

class core extends Module {
  val MTIP = IO(Input(Bool()))

  val icache = Module(new pipeline.memAccess.cache.iCache)

  val iPort = IO(icache.lowLevelMem.cloneType)

  val insState = IO(Output(UInt(3.W)))

  icache.lowLevelMem <> iPort

  val fetch = Module(new fetch(2))

  // connecting fetch unit to icache
  fetch.cache <> icache.fromFetch
  /* Seq(icache.updateAllCachelines.fired, fetch.updateAllCachelines.fired).foreach(
    _ := (icache.updateAllCachelines.ready && fetch.updateAllCachelines.ready)
  )
  Seq(icache.cachelinesUpdatesResp.fired, fetch.cachelinesUpdatesResp).foreach(
    i => i := (icache.cachelinesUpdatesResp.ready && fetch.cachelinesUpdatesResp.ready)
  ) */

  val decode = Module(new pipeline.decode.decode {
    val registersOut = IO(Output(Vec(33, UInt(64.W))))
    registersOut.zipWithIndex.foreach { case (outVal, i) => i match {
      case 32 => registersOut(32) := mstatus(0)
      case _ => outVal := registerFile(i)
    }  }
    //For floating point
    val registersOutF = IO(Output(Vec(32, UInt(32.W))))
    registersOutF.zipWithIndex.foreach { case (outVal, i) => i match {
      case _ => outVal := registerFileF(i)
    }  }
    
    val mtvecOut = IO(Output(UInt(64.W)))
    mtvecOut := mtvec(0)
    //val robEmpty = IO(Input(Bool()))
  } )
  insState := decode.insState

  val interrProc = RegInit(false.B)

  // connecting instruction issue from fetch to decode
  Seq(fetch.toDecode.fired, decode.fromFetch.fired).foreach(
    _ := (fetch.toDecode.ready && decode.fromFetch.ready)
  )
  decode.fromFetch.pc           := fetch.toDecode.pc
  decode.fromFetch.instruction  := fetch.toDecode.instruction
  fetch.toDecode.expected.valid := decode.fromFetch.expected.valid
  fetch.toDecode.expected.pc    := decode.fromFetch.expected.pc
  when(MTIP && decode.allowInterrupt && (fetch.toDecode.instruction(6, 0) =/= "b1110011".U) && (fetch.toDecode.instruction(6, 0) =/= "b0001111".U)) {
    // this is custom instruction to indicate an interrupt
    decode.fromFetch.instruction := "h80000073".U(64.W)
    interrProc := decode.fromFetch.fired
  }

  when(interrProc) {
    fetch.toDecode.expected.valid := true.B
    fetch.toDecode.expected.pc := decode.mtvecOut
    Seq(fetch.toDecode.fired, decode.fromFetch.fired).foreach(_ := false.B)
  }

  when(interrProc) {
    interrProc := !(decode.writeBackResult.fired && decode.writeBackResult.execptionOccured)
  }

  // connecting branch results to fetch unit
  Seq(fetch.branchRes.fired, decode.branchRes.fired).foreach(
    _ := (fetch.branchRes.ready && decode.branchRes.ready && (!decode.fromFetch.expected.valid || (decode.fromFetch.expected.pc === fetch.toDecode.pc)))
  )
  fetch.branchRes.isBranch      <> decode.branchRes.isBranch
  fetch.branchRes.branchTaken   <> decode.branchRes.branchTaken
  fetch.branchRes.pc            <> decode.branchRes.pc
  fetch.branchRes.pcAfterBrnach <> decode.branchRes.pcAfterBrnach
  fetch.branchRes.isRas := decode.branchRes.isRas
  fetch.branchRes.rasAction := decode.branchRes.rasAction
/* Lets connect all interfaces relating to fences later
  val dcache = Module(new pipeline.memAccess.cache.dCache)

  // connecting to implement fence from dcache(to clean) to fetch(to refetch)
  Seq(fetch.carryOutFence.fired, dcache.cachesCleaned.fired).foreach(
    _ := (fetch.carryOutFence.ready && dcache.cachesCleaned.ready)
  ) */

  val rob = Module(new pipeline.rob.rob {
    val isEmpty = IO(Output(Bool()))
    isEmpty := fifo.isEmpty
  })
  decode.robEmpty := rob.isEmpty

  // connecting decode to RoB
  Seq(decode.writeBackResult.fired, rob.commit.fired).foreach(
    _ := (decode.writeBackResult.ready && rob.commit.ready)
  )
  // these connections are incorrect an need to be fixed
  // decode.writeBackResult.rdAddr := rob.commit.rdAddr
  decode.writeBackResult.writeBackData := rob.commit.writeBackData
  decode.writeBackResult.robAddr := rob.commit.robAddr
  decode.writeBackResult.execptionOccured := rob.commit.execptionOccured
  decode.writeBackResult.mcause := rob.commit.mcause
  decode.writeBackResult.mepc := rob.commit.mepc

  decode.writeBackResult.inst := rob.commit.inst
  decode.writeBackResult.fFlags := rob.commit.eflags

  val nonRobForwarding = WireInit(VecInit(Seq.fill(4)(new forwardPort Lit(_.valid -> false.B))))

  // opcode is left dangling for the moment
  val exec = Module(new pipeline.exec.exec {
    val forward = IO(Output(new forwardPort))
    forward.valid := !bufferedEntries(0).free && (
      !bufferedEntries(0).instruction(6).asBool && bufferedEntries(0).instruction(4).asBool && 
      ((bufferedEntries(0).instruction(5, 2) =/= BitPat("b11?0")) || !bufferedEntries(0).instruction(25).asBool)  
    )
    forward.robAddr := bufferedEntries(0).robAddr
    forward.data := execResult
  })
  nonRobForwarding(0) := exec.forward
  nonRobForwarding(1).valid := exec.toRob.ready
  nonRobForwarding(1).robAddr := exec.toRob.robAddr
  nonRobForwarding(1).data := exec.toRob.execResult

  def forwardFromNonRobValid(x: UInt) = {
    nonRobForwarding.map(i => i.valid && (i.robAddr === x)).reduce(_ || _)
  }
  // connecting decode to issue instruction (exec & rob)
  Seq(decode.toExec.fired, rob.fromDecode.fired, exec.fromIssue.fired).foreach(
    _ := (
      decode.toExec.ready && rob.fromDecode.ready && exec.fromIssue.ready &&
      (!decode.toExec.src1.fromRob || rob.fromDecode.fwdrs1.valid || forwardFromNonRobValid(decode.toExec.src1.robAddr)) &&
      (!decode.toExec.src2.fromRob || rob.fromDecode.fwdrs2.valid || forwardFromNonRobValid(decode.toExec.src2.robAddr)) &&
      (!decode.toExec.writeData.fromRob || rob.fromDecode.fwdrs2.valid || forwardFromNonRobValid(decode.toExec.writeData.robAddr))
    )
  )
  def forwardToIssue(x: UInt) = {
    MuxCase(nonRobForwarding.head.data, nonRobForwarding.tail.map(i => (i.valid && (i.robAddr === x)) -> i.data))
  }
  decode.toExec.robAddr := rob.fromDecode.robAddr
  exec.fromIssue.robAddr := rob.fromDecode.robAddr
  exec.fromIssue.src1 := Mux(decode.toExec.src1.fromRob, Mux(forwardFromNonRobValid(decode.toExec.src1.robAddr), forwardToIssue(decode.toExec.src1.robAddr), rob.fromDecode.fwdrs1.value), decode.toExec.src1.data)
  exec.fromIssue.src2 := Mux(decode.toExec.src2.fromRob, Mux(forwardFromNonRobValid(decode.toExec.src2.robAddr), forwardToIssue(decode.toExec.src2.robAddr), rob.fromDecode.fwdrs2.value), decode.toExec.src2.data)
  exec.fromIssue.writeData := Mux(decode.toExec.writeData.fromRob, Mux(forwardFromNonRobValid(decode.toExec.writeData.robAddr), forwardToIssue(decode.toExec.writeData.robAddr), rob.fromDecode.fwdrs2.value), decode.toExec.writeData.data)
  exec.fromIssue.instruction := decode.toExec.instruction
  rob.fromDecode.inst:= decode.toExec.instruction
  rob.fromDecode.rd := decode.toExec.instruction(11, 7)
  rob.fromDecode.fwdrs1.robAddr := decode.toExec.src1.robAddr
  rob.fromDecode.fwdrs2.robAddr := Mux(decode.toExec.writeData.fromRob, decode.toExec.writeData.robAddr, decode.toExec.src2.robAddr)
  rob.fromDecode.pc := decode.toExec.pc

  // connecting exec results with RoB
  Seq(rob.fromExec.fired, exec.toRob.fired).foreach(
    _ := (rob.fromExec.ready && exec.toRob.ready)
  )
  rob.fromExec.execResult := exec.toRob.execResult
  rob.fromExec.robAddr := exec.toRob.robAddr
  rob.fromExec.execeptionOccured := exec.toRob.execptionOccured
  rob.fromExec.mcause := exec.toRob.mcause
  rob.fromExec.eflags := exec.toRob.eflags

  val memAccess = Module(new pipeline.memAccess.memAccess)
  val peripheral = IO(memAccess.peripherals.cloneType)
  memAccess.peripherals <> peripheral
  nonRobForwarding(2).valid := memAccess.toRob.ready
  nonRobForwarding(2).robAddr := memAccess.toRob.robAddr
  nonRobForwarding(2).data := memAccess.toRob.writeBackData

  nonRobForwarding(3).valid := memAccess.dCache.resp.valid
  nonRobForwarding(3).robAddr := memAccess.dCache.resp.bits.robAddr
  nonRobForwarding(3).data := {
    val rightJustified = memAccess.dCache.resp.bits.byteAlignedData >> Cat(memAccess.dCache.resp.bits.address(2, 0), 0.U(3.W))
    VecInit.tabulate(4)(i => i match {
      case 0 => Cat(Fill(56, rightJustified(7) & !memAccess.dCache.resp.bits.funct3(2)), rightJustified(7, 0))
      case 1 => Cat(Fill(48, rightJustified(15) & !memAccess.dCache.resp.bits.funct3(2)), rightJustified(15, 0))
      case 2 => Cat(Fill(32, rightJustified(31) & !memAccess.dCache.resp.bits.funct3(2)), rightJustified(31, 0))
      case _ => memAccess.dCache.resp.bits.byteAlignedData
    })(memAccess.dCache.resp.bits.funct3(1, 0))
  }

  // connecting mem unit and exec unit
  Seq(exec.toMemory.fired, memAccess.fromPipeline.fired).foreach(
    _ := (exec.toMemory.ready && memAccess.fromPipeline.ready)
  )
  memAccess.fromPipeline.address := exec.toMemory.memAddress
  memAccess.fromPipeline.instruction := exec.toMemory.instruction
  memAccess.fromPipeline.robAddr := exec.toMemory.robAddr
  memAccess.fromPipeline.writeData := exec.toMemory.writeData

  // connecting rob and mem unit
  Seq(rob.fromMem.fired, memAccess.toRob.fired).foreach(
    _ := (rob.fromMem.ready && memAccess.toRob.ready)
  )
  rob.fromMem.writeBackData := memAccess.toRob.writeBackData
  rob.fromMem.robAddr := memAccess.toRob.robAddr
  
  val dcache = Module(new pipeline.memAccess.cache.dCache)
  val dPort = IO(dcache.lowLevelMem.cloneType)
  dPort <> dcache.lowLevelMem

  memAccess.dCache <> dcache.pipelineMemAccess

  // rob issuing a fence -> memAccess to push all writes to cache
  Seq(rob.carryOutFence.fired, memAccess.carryOutFence.fired).foreach(
    _ := (rob.carryOutFence.ready && memAccess.carryOutFence.ready)
  )

  // memAccess issuing a cleaning request to dcache
  Seq(memAccess.cleanAllCacheLines.fired, dcache.cleanCaches.fired).foreach(
    _ := (memAccess.cleanAllCacheLines.ready && dcache.cleanCaches.ready)
  )

  // dcache informs fetch unit that its cache is clean
  Seq(dcache.cachesCleaned.fired, icache.updateAllCachelines.fired).foreach(
    _ := (dcache.cachesCleaned.ready && icache.updateAllCachelines.ready)
  )
  fetch.carryOutFence.fired := fetch.carryOutFence.ready
  // fetch informs the icache to update its cache lines
  /* Seq(fetch.updateAllCachelines.fired, icache.updateAllCachelines.fired).foreach(
    _ := (fetch.updateAllCachelines.ready && icache.updateAllCachelines.ready)
  ) */
  fetch.updateAllCachelines.fired := fetch.updateAllCachelines.ready
  // icache informs the fetch unit to start fetching again
  Seq(icache.cachelinesUpdatesResp.fired, fetch.cachelinesUpdatesResp.fired).foreach(
    _ := (icache.cachelinesUpdatesResp.ready && fetch.cachelinesUpdatesResp.ready)
  )

  

//  val fetchOut = IO(Output(new Bundle {
//    val fired = Bool()
//    val pc = UInt(64.W)
//  }))
//  fetchOut.fired := fetch.toDecode.fired
//  fetchOut.pc := fetch.toDecode.pc
//
//  val robOut = IO(Output(new Bundle {
//    val fired = Bool()
//    val mepc = UInt(64.W)
//  }))
//  robOut.fired := rob.commit.fired
//  robOut.mepc := rob.commit.mepc


//  val regOut = IO(Output(Vec(32, UInt(64.W))))
//  regOut := decode.regFile



  val ziscsrIn = RegInit(false.B)
  when(ziscsrIn) {
    Seq(decode.toExec.fired, exec.fromIssue.fired, rob.fromDecode.fired).foreach(_ := false.B)
    ziscsrIn := !(rob.commit.fired && rob.commit.execptionOccured)
  }.otherwise {
    ziscsrIn := decode.toExec.fired && (decode.toExec.instruction(6, 0) === "h73".U)
  }

  val core_sample0, core_sample1 = IO(Output(UInt(1.W)))
  core_sample0 := decode.fromFetch.expected.valid.asUInt
  core_sample1 := decode.fromFetch.expected.pc(30)

}

/* trait registersOut extends core {
  override val decode = Module(new pipeline.decode.decode {
    val registersOut = IO(Output(Vec(32, UInt(32.W))))
    registersOut.zipWithIndex.foreach { case (outVal, i) => outVal := registerFile(i) }
  } )
} */


object core extends App {
  emitVerilog(new core)
}

