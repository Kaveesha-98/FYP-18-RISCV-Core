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
  /* val fromPipeline = IO(new Bundle{
    val req = Flipped(DecoupledIO(new Bundle{
      val address   = UInt(64.W)
      val writeData = UInt(64.W) // right justified as in rs2
      val funct3    = UInt(3.W)
      val isWrite   = Bool()
      val robAddr   = UInt(robAddrWidth.W)
    }))
    val resp = DecoupledIO(new Bundle{
      // writes do not return a response
      val byteAlignedData = UInt(64.W)
      val address = UInt(64.W)
      val funct3 = UInt(3.W)
      val robAddr = UInt(robAddrWidth.W)
    })
  })

  val lowLevelMem = IO(new AXI)

  val currentReq = RegInit(VecInit(Seq.fill(2)(RegInit((new Bundle{
		val valid 	= Bool()
    val address = UInt(64.W)
    val writeData = UInt(64.W) // right justified as in rs2
    val funct3    = UInt(3.W)
    val isWrite   = Bool()
    val robAddr   = UInt(robAddrWidth.W)
	}).Lit(
		_.valid	-> false.B,
    _.address -> 0.U,
    _.writeData -> 0.U, // right justified as in rs2
    _.funct3 -> 0.U,
    _.isWrite -> false.B,
    _.robAddr -> 0.U
	)))))

  val cache = Module(new dCacheRegisters)
  cache.io.clock := clock
  cache.io.reset := reset

  cache.io.address := currentReq(0).address(31, 0)

  val cacheLookUp = RegInit((new Bundle{
		val valid 	= Bool()
    val address = UInt(64.W)
    val instruction = UInt(32.W)
    val tag = UInt(iCacheTagWidth.W)
    val tagValid = Bool()
    val writeData = UInt(64.W) // right justified as in rs2
    val funct3    = UInt(3.W)
    val isWrite   = Bool()
    val robAddr   = UInt(robAddrWidth.W)
    val byteAlignedData = UInt(64.W)
	}).Lit(
		_.valid	-> false.B,
    _.address -> 0.U,
    _.writeData -> 0.U, // right justified as in rs2
    _.funct3 -> 0.U,
    _.isWrite -> false.B,
    _.robAddr -> 0.U,
    _.byteAlignedData -> 0.U
	))

  val cacheHit = (cacheLookUp.tag === cacheLookUp.address(31, 31-iCacheTagWidth)) && cacheLookUp.tagValid

  val arvalid, rready, writeToCache = RegInit(false.B)

  cache.io.write_in := writeToCache

  when(!arvalid) { arvalid := cacheLookUp.valid && !cacheHit }
  when(!rready) { rready := cacheLookUp.valid && !cacheHit }

  lowLevelMem.ARADDR := Cat(cacheLookUp.address(31, 3+dCacheDoubleWordOffsetWidth), 0.U((3+dCacheDoubleWordOffsetWidth).W))
  lowLevelMem.ARBURST := 1.U
  lowLevelMem.ARCACHE := 2.U
  lowLevelMem.ARID := 1.U
  lowLevelMem.ARLEN := ((dCacheBlockSize*2) - 1).U
  lowLevelMem.ARLOCK := 0.U
  lowLevelMem.ARPROT := 0.U
  lowLevelMem.ARQOS := 0.U
  lowLevelMem.ARSIZE := 2.U
  lowLevelMem.ARVALID := arvalid

  val newDataBlock = Reg(UInt((64*dCacheBlockSize).W))

  lowLevelMem.RREADY := rready

  when(lowLevelMem.RVALID && lowLevelMem.RREADY && (lowLevelMem.RID === 1.U)) { newDataBlock := Cat(newDataBlock(32*((dCacheBlockSize*2)-1) -1, 0), lowLevelMem.RDATA) }

  cache.io.write_block := newDataBlock
  cache.io.write_in := writeToCache

  // finish getting new instruction block from low level cache or primary memory(for now its primary memory)
  when(arvalid) { arvalid := !(lowLevelMem.ARVALID && lowLevelMem.ARREADY) }
  when(rready) { rready := !(lowLevelMem.RREADY && lowLevelMem.RVALID && lowLevelMem.RLAST && (lowLevelMem.RID === 0.U)) }

  when(!writeToCache) { writeToCache := (cacheLookUp.valid && cacheHit && cacheLookUp.isWrite) || (lowLevelMem.RREADY && lowLevelMem.RVALID && lowLevelMem.RLAST && (lowLevelMem.RID === 0.U)) }
  when(writeToCache) { writeToCache := false.B }

  val commitStore = RegInit(false.B)
  when(!commitStore) { commitStore := cacheLookUp.valid && cacheHit && cacheLookUp.isWrite }
  .otherwise { commitStore := false.B } // one cycle should be enough

  cache.io.store_commit := commitStore
  cache.io.store_address := cacheLookUp.address

  val storeMask = (VecInit.tabulate(4)(i => i match {
    case 0 => 1.U
    case 1 => 3.U
    case 3 => 7.U
    case 4 => 15.U
  })(cacheLookUp.funct3(1, 0))) << cacheLookUp.address(2,0)

  val storeDataAligned = cacheLookUp.writeData << (VecInit.tabulate(8)(i => (8*i).U)(cacheLookUp.address(2,0)))

  val doubleWordAfterStore = Cat(Seq.tabulate(8)(i => (Mux(storeMask(i).asBool, storeDataAligned(8*(i+1) - 1, 8*i), cacheLookUp.byteAlignedData(8*(i+1) - 1, 8*i)))).reverse)

  val doubleWordAfterStoreReg = Reg(UInt(64.W))
  when(!commitStore && cacheLookUp.valid && cacheHit && cacheLookUp.isWrite) { doubleWordAfterStoreReg := doubleWordAfterStore }
  */
}

