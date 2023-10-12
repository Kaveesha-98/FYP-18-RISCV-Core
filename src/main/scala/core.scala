//package pipeline

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxResource
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO
import com.sun.org.apache.xpath.internal.operations

class core extends Module {
  val icache = Module(new pipeline.memAccess.cache.iCache)

  val iPort = IO(icache.lowLevelMem.cloneType)

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
    val registersOut = IO(Output(Vec(32, UInt(64.W))))
    registersOut.zipWithIndex.foreach { case (outVal, i) => outVal := registerFile(i) }

    //val robEmpty = IO(Input(Bool()))
  } )

  // connecting instruction issue from fetch to decode
  Seq(fetch.toDecode.fired, decode.fromFetch.fired).foreach(
    _ := (fetch.toDecode.ready && decode.fromFetch.ready)
  )
  decode.fromFetch.pc           := fetch.toDecode.pc
  decode.fromFetch.instruction  := fetch.toDecode.instruction
  fetch.toDecode.expected.valid := decode.fromFetch.expected.valid
  fetch.toDecode.expected.pc    := decode.fromFetch.expected.pc

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
  decode.writeBackResult.rdAddr := rob.commit.rdAddr
  decode.writeBackResult.writeBackData := rob.commit.writeBackData
  decode.writeBackResult.robAddr := rob.commit.robAddr
  decode.writeBackResult.execptionOccured := rob.commit.execptionOccured
  decode.writeBackResult.mcause := rob.commit.mcause
  decode.writeBackResult.mepc := rob.commit.mepc

  decode.writeBackResult.opcode := rob.commit.opcode


  // opcode is left dangling for the moment

  val exec = Module(new pipeline.exec.exec)

  // connecting decode to issue instruction (exec & rob)
  Seq(decode.toExec.fired, rob.fromDecode.fired, exec.fromIssue.fired).foreach(
    _ := (
      decode.toExec.ready && rob.fromDecode.ready && exec.fromIssue.ready &&
      (!decode.toExec.src1.fromRob || rob.fromDecode.fwdrs1.valid) &&
      (!decode.toExec.src2.fromRob || rob.fromDecode.fwdrs2.valid) &&
      (!decode.toExec.writeData.fromRob || rob.fromDecode.fwdrs2.valid)
    )
  )
  decode.toExec.robAddr := rob.fromDecode.robAddr
  exec.fromIssue.robAddr := rob.fromDecode.robAddr
  exec.fromIssue.src1 := Mux(decode.toExec.src1.fromRob, rob.fromDecode.fwdrs1.value, decode.toExec.src1.data)
  exec.fromIssue.src2 := Mux(decode.toExec.src2.fromRob, rob.fromDecode.fwdrs2.value, decode.toExec.src2.data)
  exec.fromIssue.writeData := Mux(decode.toExec.writeData.fromRob, rob.fromDecode.fwdrs2.value, decode.toExec.writeData.data)
  exec.fromIssue.instruction := decode.toExec.instruction
  rob.fromDecode.instOpcode := decode.toExec.instruction(6, 0)
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

  val memAccess = Module(new pipeline.memAccess.memAccess)
  val peripheral = IO(memAccess.peripherals.cloneType)
  memAccess.peripherals <> peripheral

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

  val execOut = IO(Output(new Bundle {
    val fired = Bool()
    val instruction = UInt(32.W)
    val pc = UInt(64.W)
    val src1 = UInt(64.W)
    val src2 = UInt(64.W)
    val writeData = UInt(64.W)
  }))
  execOut.fired := exec.fromIssue.fired
  execOut.instruction := exec.fromIssue.instruction
  execOut.pc := decode.toExec.pc
  execOut.src1 := exec.fromIssue.src1
  execOut.src2 := exec.fromIssue.src2
  execOut.writeData := exec.fromIssue.writeData

  // Debug Signals

  val branchOut = IO(Output(new Bundle() {
    val mispredicted = Bool()
    val resfired = Bool()
    val isbranch = Bool()
    val btbhit = Bool()
    val fetchsent = Bool()
    val resPC = UInt(64.W)
    val decodePC = UInt(64.W)
    val decodeIns = UInt(32.W)
    val isRas = Bool()
    val rasAction = UInt(64.W)
    val curPC = UInt(64.W)
    val predRasAction = UInt(64.W)
    val rasOveride = UInt(64.W)
    val rasLogicTrigger = UInt(64.W)
    val btbVal = UInt(64.W)
  }))
  branchOut.mispredicted := fetch.misprediction
  branchOut.resfired := fetch.resfired
  branchOut.isbranch := fetch.isabranch
  branchOut.btbhit := fetch.btbhit
  branchOut.fetchsent := fetch.fetchsent
  branchOut.resPC := fetch.resPC
  branchOut.decodePC := decode.decodePC
  branchOut.decodeIns := decode.decodeIns
  branchOut.isRas := fetch.isras
  branchOut.rasAction := fetch.rasAction
  branchOut.curPC := fetch.curPC
  branchOut.predRasAction := fetch.predRasAction
  branchOut.rasOveride := fetch.rasOveride
  branchOut.rasLogicTrigger := fetch.rasLogicTrigger
  branchOut.btbVal := fetch.btbVal


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

  val fetchOut = IO(Output(new Bundle {
    val fired = Bool()
    val pc = UInt(64.W)
  }))
  fetchOut.fired := fetch.toDecode.fired
  fetchOut.pc := fetch.toDecode.pc

  val robOut = IO(Output(new Bundle() {
    val commitFired = Bool()
    val pc         = UInt(64.W)
  }))
  robOut.commitFired := rob.commit.fired
  robOut.pc          := rob.commit.mepc
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
