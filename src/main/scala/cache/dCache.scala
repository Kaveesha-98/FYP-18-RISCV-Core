package pipeline.memAccess.cache

/**
  * This is a place holder code will change in the future in major ways
  * like removing a dedicated register to keep track of state.
  */

/**
  * Current implementations:
  * Handling read requests - Once read is on CurrentReq(0). The necessary 
  * cacheline is looked up on the cache and results are returned on to 
  * cacheLookUp. 
  * When cachehit - The result is ready to be accepted by pipeline
  * 
  * When cachemiss - cache pipeline is stalled
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

class dCacheRegisters extends BlackBox(
  Map("offset_width " -> dCacheDoubleWordOffsetWidth,
  "line_width" -> dCacheLineWidth)
) with HasBlackBoxResource {

  val io = IO(new Bundle {
    val address = Input(UInt(32.W))//input [31:0] address,
    val byte_aligned_data = Output(UInt(64.W))//output [31:0] instruction,
    val tag = Output(UInt(dCacheTagWidth.W))//output [tag_width-1: 0] tag,
    val tag_valid = Output(Bool())//output valid,
    val write_line_index = Input(UInt(dCacheLineWidth.W))//input [line_width-1:0] write_line_index,
    val write_block = Input(UInt((64*dCacheBlockSize).W))//input [32*block_size - 1:0] write_block,
    val write_tag = Input(UInt(dCacheTagWidth.W))//input [tag_width-1, 0] write_tag,
    val write_in = Input(Bool())//input reset, write_in, clock
    val store_data = Input(UInt(64.W))
    val store_address = Input(UInt(32.W))
    val store_commit = Input(Bool())
    val clock = Input(Clock())
    val reset = Input(Reset())
  })

  //val clock = IO(Input(Clock()))
  addResource("/dCacheRegisters.v")
}

class dCache extends Module {
  /* val pipelineMemAccess = IO(Flipped((new memAccess).dCache.cloneType))
  val lowLevelAXI = IO(new AXI)
  val cleanCaches = IO(new composableInterface)
  val cachesCleaned = IO(new composableInterface)

  val servicing :: buffered :: Nil = Enum(2)

  val cache = Module(new dCacheRegisters)
  cache.io.clock := clock
  cache.io.reset := reset

  val updateCache = RegInit(false.B)
  val updatedCacheLine = Reg(UInt((64*dCacheBlockSize).W))

  val updateCacheBlock = RegInit(new Bundle {
    val valid = Bool()
    val block = UInt((dCacheBlockSize*64).W)
    val index = UInt(dCacheLineWidth.W)
    val tag   = UInt(dCacheTagWidth.W)
  }).Lit(
    _.valid -> false.B,
    _.blobk -> 0.U,
    _.index -> 0.U,
    _.tag   -> 0.U
  )

  cache.io.write_in := updateCacheBlock.valid
  cache.io.write_block := updateCacheBlock.block
  cache.io.write_line_index := updateCacheBlock.index
  cache.io.write_tag := updateCacheBlock.tag
  // need a way to only write a specific double word

  cache.io.store_data := 0.U
  cache.io.store_commit := false.B
  cache.io.store_address := 0.U

  // cache requests are buffered in these registers
  val cacheReqs = RegInit(VecInit(Seq.fill(2)(RegInit((new Bundle {
    val valid = Bool()
    val address = UInt(64.W)
    val writeData = UInt(64.W)
    val instruction = UInt(32.W)
    val robAddr = UInt(robAddrWidth.W)
  }).Lit(
    _.valid -> false.B,
    _.address -> 0.U,
    _.writeData -> 0.U,
    _.instruction -> 0.U,
    _.robAddr -> 0.U
  )))))

  cache.io.address := cacheReqs(servicing).address 

  // response from cache lookup stored here
  val lookupResponse = RegInit((new Bundle {
    val valid = Bool()
    val address = UInt(64.W)
    val writeData = UInt(64.W)
    val instruction = UInt(32.W)
    val robAddr = UInt(robAddrWidth.W)
    val byteAlignedData = UInt((dCacheBlockSize*64).W)
  })) */
}

