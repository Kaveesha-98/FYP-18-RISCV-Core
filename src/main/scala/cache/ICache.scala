package pipeline.memAccess.cache

/**
  * This is a place holder code will change in the future in major ways
  * like removing a dedicated register to keep track of state.
  */

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxResource
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

import pipeline.ports._
import pipeline.configuration.coreConfiguration._
import pipeline.memAccess.AXI
import os.write

class iCacheRegisters extends BlackBox(
  Map("offset_width " -> iCacheOffsetWidth,
  "line_width" -> iCacheLineWidth)
) with HasBlackBoxResource {

  val io = IO(new Bundle {
    val address = Input(UInt(32.W))//input [31:0] address,
    val instruction = Output(UInt(32.W))//output [31:0] instruction,
    val tag = Output(UInt(iCacheTagWidth.W))//output [tag_width-1: 0] tag,
    val tag_valid = Output(Bool())//output valid,
    val write_line_index = Input(UInt(iCacheLineWidth.W))//input [line_width-1:0] write_line_index,
    val write_block = Input(UInt(iCacheBlockSize.W))//input [32*block_size - 1:0] write_block,
    val write_tag = Input(UInt(iCacheTagWidth.W))//input [tag_width-1, 0] write_tag,
    val write_in = Input(Bool())//input reset, write_in, clock
    val invalidate_all = Input(Bool())
    val clock = Input(Clock())
    val reset = Input(Reset())
  })

  //val clock = IO(Input(Clock()))
  addResource("/iCacheRegisters.v")
}


class iCache(
  val blockSize:Int = 4, // 4 instructions per block
) extends Module {
  /**
    * Inputs and Outputs of the module
    */

  // interface with fetch unit
  val fromFetch = IO(new Bundle {
    val req   = Flipped(DecoupledIO(UInt(64.W))) // address is 64 bits wide for 64-bit machine
    val resp  = DecoupledIO(UInt(32.W)) // instructions are 32 bits wide
  })

  // request to update cache lines
  // once fired all pending requests to cache are invalidated
  // updateAllCachelines and cache.req **cannot** be ready at the same time
  val updateAllCachelines = IO(new composableInterface)

  // after updateAllCachelines is fired, this should be ready
  // will fire once all cachelines in I$ is updated
  val cachelinesUpdatesResp = IO(new composableInterface)

  val pendingInvalidate = RegInit(false.B)

  when(!pendingInvalidate) { pendingInvalidate := updateAllCachelines.ready && updateAllCachelines.fired }
  updateAllCachelines.ready := !pendingInvalidate

  val lowLevelMem = IO(new AXI)

  val currentReq = RegInit(VecInit(Seq.fill(2)(RegInit((new Bundle{
		val valid 	= Bool()
    val address = UInt(64.W)
	}).Lit(
		_.valid	-> false.B,
    _.address -> 0.U
	)))))

  val cache = Module(new iCacheRegisters)
  cache.io.address := currentReq(0).address(31, 0)
  cache.io.clock := clock
  cache.io.reset := reset
  cache.io.invalidate_all := pendingInvalidate

  val cacheLookUp = RegInit((new Bundle{
		val valid 	= Bool()
    val address = UInt(64.W)
    val instruction = UInt(32.W)
    val tag = UInt(iCacheTagWidth.W)
    val tagValid = Bool()
	}).Lit(
		_.valid	-> false.B,
    _.address -> 0.U,
    _.instruction -> 0.U,
    _.tag -> 0.U,
    _.tagValid -> false.B
	))

  val cacheHit = (cacheLookUp.tag === cacheLookUp.address(31, 31-iCacheTagWidth)) && cacheLookUp.tagValid
  
  fromFetch.req.ready := (!cacheLookUp.valid || (fromFetch.resp.ready && cacheHit)) && !currentReq(1).valid && !pendingInvalidate

  val arvalid , rready, writeToCache = RegInit(false.B)

  cache.io.write_in := writeToCache

  lowLevelMem.ARVALID := arvalid
  lowLevelMem.RREADY := rready

  // cache misses prompts requests to low level mem
  when(!arvalid) { arvalid := cacheLookUp.valid && !cacheHit }
  when(!rready) { rready := cacheLookUp.valid && !cacheHit }

  lowLevelMem.ARADDR := Cat(cacheLookUp.address(31, 2+iCacheOffsetWidth), 0.U((2+iCacheOffsetWidth).W))
  lowLevelMem.ARBURST := 1.U
  lowLevelMem.ARCACHE := 2.U
  lowLevelMem.ARID := 0.U
  lowLevelMem.ARLEN := (iCacheBlockSize - 1).U
  lowLevelMem.ARLOCK := 0.U
  lowLevelMem.ARPROT := 0.U
  lowLevelMem.ARQOS := 0.U
  lowLevelMem.ARSIZE := 2.U
  lowLevelMem.ARVALID := arvalid

  val newInstrBlock = Reg(UInt((32*iCacheBlockSize).W))

  lowLevelMem.RREADY := rready

  when(lowLevelMem.RVALID && lowLevelMem.RREADY && (lowLevelMem.RID === 0.U)) { newInstrBlock := Cat(newInstrBlock(32*(iCacheBlockSize-1) -1, 0), lowLevelMem.RDATA) }

  cache.io.write_block := newInstrBlock
  cache.io.write_in := writeToCache

  // finish getting new instruction block from low level cache or primary memory(for now its primary memory)
  when(arvalid) { arvalid := !(lowLevelMem.ARVALID && lowLevelMem.ARREADY) }
  when(rready) { rready := !(lowLevelMem.RREADY && lowLevelMem.RVALID && lowLevelMem.RLAST && (lowLevelMem.RID === 0.U)) }

  when(!writeToCache) { writeToCache := (lowLevelMem.RREADY && lowLevelMem.RVALID && lowLevelMem.RLAST && (lowLevelMem.RID === 0.U)) }
  when(writeToCache) { writeToCache := false.B }

  // getting a request check through cache
  when(pendingInvalidate){
    currentReq(0).valid := false.B
  }.elsewhen(writeToCache) {
    // after getting the cache line that missed, reservicing the request
    currentReq(0).address := cacheLookUp.address
    currentReq(0).valid := cacheLookUp.valid
    cacheLookUp.valid := false.B
  }.elsewhen(currentReq(1).valid) {
    // once a cache miss is detected, the request in currentReq(0) is buffered to serve after servicing the blocked request
    currentReq(0) := currentReq(1)
  }.elsewhen((fromFetch.resp.ready && cacheLookUp.valid && cacheHit) || !cacheLookUp.valid) {
    // normal operation
    currentReq(0).address := fromFetch.req.bits
    currentReq(0).valid := (fromFetch.req.valid && fromFetch.req.ready)
  }

  when(pendingInvalidate){
    currentReq(1).valid := false.B
  }.elsewhen(writeToCache) {
    // buffering the request after the blocking request
    // (to service the blocked request, after getting the required cacheline from lower level)
    currentReq(1) := currentReq(0)
  }.elsewhen(currentReq(1).valid) { currentReq(1).valid := false.B }

  // servicing a request
  when(pendingInvalidate){
    cacheLookUp.valid := false.B
  }.elsewhen((fromFetch.resp.ready && cacheHit) || !cacheLookUp.valid) {
    cacheLookUp.address := currentReq(0).address
    cacheLookUp.instruction := cache.io.instruction
    cacheLookUp.tag := cache.io.tag
    cacheLookUp.tagValid := cache.io.tag_valid
    cacheLookUp.valid := currentReq(0).valid
  }.elsewhen(writeToCache) {
    cacheLookUp.valid := false.B
  }

  fromFetch.resp.valid := cacheHit && cacheLookUp.valid && !pendingInvalidate

  fromFetch.resp.bits := cacheLookUp.instruction

  lowLevelMem.AWADDR := 0.U
  lowLevelMem.AWBURST := 1.U
  lowLevelMem.AWCACHE := 2.U
  lowLevelMem.AWID := 0.U
  lowLevelMem.AWLEN := 0.U
  lowLevelMem.AWLOCK := 0.U
  lowLevelMem.AWPROT := 0.U
  lowLevelMem.AWQOS := 0.U
  lowLevelMem.AWSIZE := 0.U
  lowLevelMem.AWVALID := false.B

  lowLevelMem.WDATA := 0.U
  lowLevelMem.WLAST := false.B
  lowLevelMem.WSTRB := 0.U
  lowLevelMem.WVALID := false.B

  lowLevelMem.BREADY := false.B

  val waitOnPendingLowLevelReqs = arvalid || rready || writeToCache 
  cachelinesUpdatesResp.ready := pendingInvalidate && !waitOnPendingLowLevelReqs
  when(pendingInvalidate) { pendingInvalidate := !(cachelinesUpdatesResp.ready && cachelinesUpdatesResp.fired) }
}

object iCache extends App {
  emitVerilog(new iCache)
}