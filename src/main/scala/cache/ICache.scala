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
    val write_block = Input(UInt((32*iCacheBlockSize).W))//input [32*block_size - 1:0] write_block,
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

  val lowLevelMem = IO(new AXI)

  val next :: buffered :: servicing :: Nil = Enum(3)
  val requests = RegInit(VecInit(Seq.fill(3)(RegInit((new Bundle{
		val valid 	= Bool()
    val address = UInt(64.W)
	}).Lit(
		_.valid	-> false.B,
    _.address -> 0.U
	)))))

  val cache = Module(new iCacheRegisters)

  val results = RegInit(VecInit(Seq.fill(2)(RegInit((new Bundle{
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
	)))))

  val cacheFill = RegInit((new Bundle {
    val valid = Bool()
    val block = UInt((32*iCacheBlockSize).W)
  }).Lit(
    _.block -> 0.U,
    _.valid -> false.B
  ))
  val cacheMissed = !(results(next).tagValid && (results(next).address >> (32-iCacheTagWidth)) === results(next).tag) && results(next).valid 
  val arvalid , rready = RegInit(false.B)

  when(lowLevelMem.RREADY && lowLevelMem.RVALID) { cacheFill.block := Cat(lowLevelMem.RDATA, cacheFill.block(32*iCacheBlockSize-1, 32)) }

  when(!arvalid) { arvalid := cacheMissed && !rready && !cacheFill.valid } 
  .otherwise { arvalid := !(lowLevelMem.ARVALID && lowLevelMem.ARREADY) }

  when(!rready) { rready := (lowLevelMem.ARVALID && lowLevelMem.ARREADY) }
  .otherwise { rready := !(lowLevelMem.RLAST && lowLevelMem.RREADY && lowLevelMem.RVALID) }

  when(!cacheFill.valid) { cacheFill.valid := lowLevelMem.RLAST && lowLevelMem.RREADY && lowLevelMem.RVALID }
  .otherwise { cacheFill.valid := false.B }

  cache.io.write_block := cacheFill.block
  cache.io.write_in := cacheFill.valid
  cache.io.write_line_index := results(next).address(iCacheLineWidth + iCacheOffsetWidth + 2, iCacheOffsetWidth + 2)
  cache.io.write_tag := results(next).address(iCacheTagWidth + iCacheLineWidth + iCacheOffsetWidth + 2, iCacheLineWidth + iCacheOffsetWidth + 2)
  cache.io.clock := clock
  cache.io.reset := reset

  val cacheStalled = cacheMissed || (fromFetch.resp.valid && !fromFetch.resp.ready)
  when((!cacheMissed && fromFetch.resp.ready) || !results(next).valid) {
    when(results(buffered).valid) { results(next) := results(buffered) }
    .otherwise {
      results(next).valid := requests(servicing).valid
      results(next).address := requests(servicing).address
      results(next).instruction := cache.io.instruction
      results(next).tag := cache.io.tag
      results(next).tagValid := cache.io.tag_valid
    }
  }.elsewhen(cacheMissed && cacheFill.valid) {
    results(next).instruction := (VecInit.tabulate(1 << iCacheOffsetWidth)(i => cacheFill.block(31 + 32*i, 32*i)))(results(next).address(iCacheOffsetWidth+2, 2))
    results(next).tag := results(next).address(iCacheTagWidth + iCacheLineWidth + iCacheOffsetWidth + 2, iCacheLineWidth + iCacheOffsetWidth + 2)
    results(next).tagValid := true.B
  }

  when(!results(buffered).valid) {
    results(buffered).valid := requests(servicing).valid && results(next).valid && (cacheMissed || !fromFetch.resp.ready)
    results(buffered).address := requests(servicing).address
    results(buffered).instruction := cache.io.instruction
    results(buffered).tag := cache.io.tag
    results(buffered).tagValid := cache.io.tag_valid
  }.elsewhen(cacheMissed && cacheFill.valid && (results(next).address(31, iCacheOffsetWidth + 2) === results(buffered).address(31, iCacheOffsetWidth + 2))) {
    results(buffered).instruction := (VecInit.tabulate(1 << iCacheOffsetWidth)(i => cacheFill.block(31 + 32*i, 32*i)))(results(buffered).address(iCacheOffsetWidth+2, 2))
    results(buffered).tag := results(next).address(iCacheTagWidth + iCacheLineWidth + iCacheOffsetWidth + 2, iCacheLineWidth + iCacheOffsetWidth + 2)
    results(buffered).tagValid := true.B
  }.elsewhen(!cacheStalled) {
    results(buffered).valid := false.B
  }

  when(!results(buffered).valid) {
    requests(servicing) := requests(next)
    requests(servicing).valid := !cacheStalled && requests(next).valid
  }.otherwise {
    requests(servicing).valid := false.B
  }

  when((!cacheStalled && !results(buffered).valid) || !requests(next).valid) {
    when(requests(buffered).valid) { requests(next) := requests(buffered) }
    .otherwise {
      requests(next).valid := (fromFetch.req.ready && fromFetch.req.valid)
      requests(next).address := fromFetch.req.bits
    }
  }

  when(!requests(buffered).valid) {
    requests(buffered).valid := (cacheStalled || results(buffered).valid) && (fromFetch.req.ready && fromFetch.req.valid) && requests(next).valid
    requests(buffered).address := fromFetch.req.bits
  }.otherwise {
    requests(buffered).valid := (!cacheStalled && !results(buffered).valid)
  }

  fromFetch.resp.valid := !cacheMissed && results(next).valid
  fromFetch.resp.bits := results(next).instruction
  fromFetch.req.ready := !requests(buffered).valid

  cache.io.address := requests(next).address
  cache.io.invalidate_all := false.B
  updateAllCachelines.ready := false.B
  cachelinesUpdatesResp.ready := false.B
  // cache misses prompts requests to low level mem

  lowLevelMem.ARADDR := Cat(results(next).address(31, 2+iCacheOffsetWidth), 0.U((2+iCacheOffsetWidth).W))
  lowLevelMem.ARBURST := 1.U
  lowLevelMem.ARCACHE := 2.U
  lowLevelMem.ARID := 0.U
  lowLevelMem.ARLEN := (iCacheBlockSize - 1).U
  lowLevelMem.ARLOCK := 0.U
  lowLevelMem.ARPROT := 0.U
  lowLevelMem.ARQOS := 0.U
  lowLevelMem.ARSIZE := 2.U
  lowLevelMem.ARVALID := arvalid

  lowLevelMem.RREADY := rready

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
}

object iCache extends App {
  emitVerilog(new iCache)
}