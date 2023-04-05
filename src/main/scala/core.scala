//package pipeline

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxResource
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

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

  val decode = Module(new pipeline.decode.decode)

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
/* Lets connect all interfaces relating to fences later
  val dcache = Module(new pipeline.memAccess.cache.dCache)

  // connecting to implement fence from dcache(to clean) to fetch(to refetch)
  Seq(fetch.carryOutFence.fired, dcache.cachesCleaned.fired).foreach(
    _ := (fetch.carryOutFence.ready && dcache.cachesCleaned.ready)
  ) */

  val rob = Module(new pipeline.rob.rob)

  // connecting decode to RoB
  Seq(decode.writeBackResult.fired, rob.commit.fired).foreach(
    _ := (decode.writeBackResult.ready && rob.commit.ready)
  )
  // these connections are incorrect an need to be fixed
  decode.writeBackResult.rdAddr := rob.commit.rd
  decode.writeBackResult.writeBackData := rob.commit.value  
  decode.writeBackResult.robAddr := rob.commit.robAddr
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
  rob.fromDecode.fwdrs2.robAddr := Mux(decode.toExec.src2.fromRob, decode.toExec.src2.robAddr, decode.toExec.writeData.fromRob)

  // connecting exec results with RoB
  Seq(rob.fromExec.fired, exec.toRob.fired).foreach(
    _ := (rob.fromExec.ready && exec.toRob.ready)
  )
  rob.fromExec.execResult := exec.toRob.execResult
  rob.fromExec.robAddr := exec.toRob.robAddr

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
  rob.fromMem.execResult := memAccess.toRob.writeBackData
  rob.fromMem.robAddr := memAccess.toRob.robAddr
  
  val dcache = Module(new pipeline.memAccess.cache.dCache)
  val dPort = IO(dcache.lowLevelAXI.cloneType)
  dPort <> dcache.lowLevelAXI

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
  Seq(dcache.cachesCleaned.fired, fetch.carryOutFence.fired).foreach(
    _ := (dcache.cachesCleaned.ready && fetch.carryOutFence.ready)
  )

  // fetch informs the icache to update its cache lines
  Seq(fetch.updateAllCachelines.fired, icache.updateAllCachelines.fired).foreach(
    _ := (fetch.updateAllCachelines.ready && icache.updateAllCachelines.ready)
  )

  // icache informs the fetch unit to start fetching again
  Seq(icache.cachelinesUpdatesResp.fired, fetch.cachelinesUpdatesResp.fired).foreach(
    _ := (icache.cachelinesUpdatesResp.ready && fetch.cachelinesUpdatesResp.ready)
  )
}

object core extends App {
  emitVerilog(new core)
}