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
    val clock = Input(Clock())
    val reset = Input(Reset())
  })

  //val clock = IO(Input(Clock()))
  addResource("/iCacheRegisters.v")
}


class iCache(
  val blockSize:Int = 4, // 4 instructions per block
) extends Module {
  /* /**
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

  val lowLevelMem = IO(new AXI)

  val currentReq = RegInit(VecInit(Seq.fill(2)(RegInit((new Bundle{
		val valid 	= Bool()
    val address = UInt(64.W)
	}).Lit(
		_.valid	-> false.B,
    _.address -> 0.U
	)))))

  val cache = Module(new iCacheRegisters)
  cache.address := currentReq(0).address(31, 0)

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
  
  fromFetch.req.ready := !cacheLookUp.valid || (fromFetch.resp.ready && cacheHit)

  val arvalid , rready, writeToCache = RegInit(false.B)

  cache.write_in := writeToCache

  lowLevelMem.ARVALID := arvalid
  lowLevelMem.RREADY := rready

  // cache misses prompts requests to low level mem
  when(!arvalid) { arvalid := cacheLookUp.valid && !cacheHit }
  when(!rready) { rready := cacheLookUp.valid && !cacheHit }

  lowLevelMem.ARADDR := Cat(cacheLookUp.address(31, 2+iCacheOffsetWidth), 0.U((2+iCacheOffsetWidth).W))
 */
}
