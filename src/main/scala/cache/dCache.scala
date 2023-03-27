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
  Map("double_word_offset_width " -> dCacheDoubleWordOffsetWidth,
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
    val write_mask = Input(UInt(dCacheBlockSize.W))
    val clock = Input(Clock())
    val reset = Input(Reset())
  })

  //val clock = IO(Input(Clock()))
  addResource("/dCacheRegisters.v")
}

class dCache extends Module {
  val pipelineMemAccess = IO(new Bundle{
    val req = Flipped(DecoupledIO(new Bundle{
      val address   = UInt(64.W)
      val writeData = UInt(64.W) // right justified as in rs2
      val instruction    = UInt(32.W)
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
  val lowLevelAXI = IO(new AXI)
  val cleanCaches = IO(new composableInterface)
  val cachesCleaned = IO(new composableInterface)


  // initiating BRAM for cache
  val cache = Module(new dCacheRegisters)
  cache.io.clock := clock
  cache.io.reset := reset

  // element that updates cache for writes, atomics and miss-handling
  val updateCacheBlock = RegInit((new Bundle {
    val valid = Bool()
    val block = UInt((dCacheBlockSize*64).W)
    val index = UInt(dCacheLineWidth.W)
    val tag   = UInt(dCacheTagWidth.W)
    val mask = UInt(dCacheBlockSize.W)
  }).Lit(
    _.valid -> false.B,
    _.block -> 0.U,
    _.index -> 0.U,
    _.tag   -> 0.U,
    _.mask -> 0.U
  ))

  cache.io.write_in := updateCacheBlock.valid
  cache.io.write_block := updateCacheBlock.block
  cache.io.write_line_index := updateCacheBlock.index
  cache.io.write_tag := updateCacheBlock.tag
  cache.io.write_mask := updateCacheBlock.mask

  /**
    * servicing - combinationally connected to cached memory and is immediately
    * serviced.
    * 
    * When there the cache is not in stall (servicing atomics, stores or handling 
    * cache miss) all requests are stored in cacheReqs(servicing) and serviced 
    * immeditely.
    * 
    * After handling miss the request in cacheReqs(servicing) is transfered to 
    * cacheReqs(waiting) and the request that resulted in the miss is loaded in to
    * cacheReqs(servicing).
    * 
    * If the new request from pipeline cannot be serviced in the next cycle, it is
    * then loaded to cacheReqs(buffered). Once loaded the module will not be ready 
    * for more requests from pipeline until the request in cacheReqs(buffered) is
    * cleared.
    */
  val servicing :: buffered :: waiting :: Nil = Enum(3)
  // cache requests are buffered in these registers
  val cacheReqs = RegInit(VecInit(Seq.fill(3)(RegInit((new Bundle {
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
    val tag = UInt(dCacheTagWidth.W)
    val tagValid = Bool()
  }).Lit(
    _.valid -> false.B,
    _.address -> 0.U,
    _.writeData -> 0.U,
    _.instruction -> 0.U,
    _.robAddr -> 0.U,
    _.byteAlignedData -> 0.U,
    _.tag -> 0.U,
    _.tagValid -> false.B
  ))

  val arvalid, rready, awvalid, wvalid, bready, wlast = RegInit(false.B)
  val writeData = Reg(UInt(64.W))
  val newCacheBlock = Reg(UInt((64*dCacheBlockSize).W))

  // reads are only done to handle misses
  lowLevelAXI.ARADDR  := Cat(lookupResponse.address(31, 3+dCacheDoubleWordOffsetWidth), 0.U((3+dCacheDoubleWordOffsetWidth).W))
  lowLevelAXI.ARBURST := 1.U
  lowLevelAXI.ARCACHE := 2.U
  lowLevelAXI.ARID    := 0.U
  lowLevelAXI.ARLEN   := (dCacheBlockSize*2 - 1).U // axi is 32 bits wide
  lowLevelAXI.ARLOCK  := 0.U
  lowLevelAXI.ARPROT  := 0.U
  lowLevelAXI.ARQOS   := 0.U
  lowLevelAXI.ARSIZE  := 2.U
  lowLevelAXI.ARVALID := arvalid

  lowLevelAXI.RREADY := rready

  lowLevelAXI.AWADDR  := lookupResponse.address
  lowLevelAXI.AWBURST := 1.U
  lowLevelAXI.AWCACHE := 2.U
  lowLevelAXI.AWID    := 0.U
  lowLevelAXI.AWLEN   := Mux(lookupResponse.instruction(13, 12) === 3.U, 1.U, 0.U)
  lowLevelAXI.AWLOCK  := 0.U
  lowLevelAXI.AWPROT  := 0.U
  lowLevelAXI.AWQOS   := 0.U
  lowLevelAXI.AWSIZE  := Mux(lookupResponse.instruction(13, 12) === 3.U, 2.U, lookupResponse.instruction(13, 12))
  lowLevelAXI.AWVALID := awvalid

  def AXI_MASK(offset: UInt) =
    VecInit(1.U, 3.U, 15.U, 15.U)(offset)

  lowLevelAXI.WDATA   := writeData(31, 0)
  lowLevelAXI.WLAST   := wlast
  lowLevelAXI.WSTRB   := AXI_MASK(lookupResponse.instruction(13, 12)) << lookupResponse.address(1, 0)
  lowLevelAXI.WVALID  := wvalid

  lowLevelAXI.BREADY := bready

  val semaphoreSet = RegInit((new Bundle {
    val valid           = Bool()
    val address         = UInt(32.W) // physical memory is 32bits wide
    val reservationSet  = UInt(64.W)
    val funct3          = UInt(3.W)
  }).Lit(
    _.valid           -> false.B,
    _.address         -> 0.U,
    _.reservationSet  -> 0.U,
    _.funct3          -> 0.U
  ))

  val atomicComplete = RegInit(false.B)

  val cacheHit = lookupResponse.tagValid && (lookupResponse.tag === lookupResponse.address(31, 31 - dCacheTagWidth))
  pipelineMemAccess.req.ready := !cacheReqs(buffered).valid

  def isStoreConditional(instruction: UInt) = 
    (instruction(31, 27) === 3.U) && (instruction(6, 2) === "b01011".U)

  // normal stores do not drive a response to pipeline of core
  pipelineMemAccess.resp.valid := lookupResponse.valid && cacheHit && ((lookupResponse.instruction(6, 2) === 0.U) || ((lookupResponse.instruction(6, 2) === "b01011".U) && atomicComplete))

  val storeConditionalSuccess = (
    semaphoreSet.valid && // make sure that there was a LR.* before the current SC.*
    semaphoreSet.address === lookupResponse.address && // make sure SC.* targets same address as latest LR.*
    semaphoreSet.funct3 === lookupResponse.instruction(14,12) && // make sure SC.* is the same width as latest LR.*
    // making sure reservation set is valid(data values has not been changed since last LR.*)
    Mux(semaphoreSet.funct3 === 3.U, semaphoreSet.reservationSet === lookupResponse.byteAlignedData, 
    (semaphoreSet.address(2).asBool && (semaphoreSet.reservationSet(63, 32) === lookupResponse.byteAlignedData(63, 32))) || 
    (!semaphoreSet.address(2).asBool && (semaphoreSet.reservationSet(31, 0) === lookupResponse.byteAlignedData(31, 0))))
  ) 

  pipelineMemAccess.resp.bits.byteAlignedData := Mux(
    isStoreConditional(lookupResponse.instruction), Mux(storeConditionalSuccess, 0.U, 1.U << lookupResponse.address(2, 0)), // handling data returns on SC.*
    lookupResponse.byteAlignedData
  )
  pipelineMemAccess.resp.bits.address := lookupResponse.address
  pipelineMemAccess.resp.bits.funct3 := lookupResponse.instruction(14, 12)
  pipelineMemAccess.resp.bits.robAddr := lookupResponse.robAddr
  
  val waitToFinish = RegInit(false.B)

  pipelineMemAccess.req.ready := !cacheReqs(buffered).valid && !waitToFinish

  cleanCaches.ready := !waitToFinish

  cachesCleaned.ready := waitToFinish

  val responseStalled = lookupResponse.valid && (
    (lookupResponse.instruction(6, 2) === "b0100".U && !updateCacheBlock.valid) || // for stores after lookup it must be written to cache after commiting it to memory
    (lookupResponse.instruction(6, 2) =/= "b0100".U && !pipelineMemAccess.resp.valid) || // wait for atomics to be finished, misses to be handled
    (lookupResponse.instruction(6, 2) =/= "b0100".U && pipelineMemAccess.resp.valid && !pipelineMemAccess.resp.ready)
  )

  // filling cacheReqs(servicing)
  when(!responseStalled) {
    when(lookupResponse.valid && !cacheHit && updateCacheBlock.valid) {
      // after fetching the missed cache block and writing it to cache, the
      // the request that resulted in the miss is reloaded
      cacheReqs(servicing).valid        := true.B
      cacheReqs(servicing).address      := lookupResponse.address
      cacheReqs(servicing).writeData    := lookupResponse.writeData
      cacheReqs(servicing).instruction  := lookupResponse.instruction
      cacheReqs(servicing).robAddr      := lookupResponse.robAddr
    }.elsewhen(cacheReqs(waiting).valid) {
      // after handling the request that resulted in a cache miss, the next 
      // oldest request is serviced next
      cacheReqs(servicing) := cacheReqs(waiting)
    }.elsewhen(cacheReqs(buffered).valid) {
      // the instruction which got buffered because the response was stalled is given
      // priority next
      cacheReqs(servicing) := cacheReqs(buffered)
    }.otherwise {
      // getting a new instruction to serve
      cacheReqs(servicing).valid        := (pipelineMemAccess.req.ready && pipelineMemAccess.req.valid)
      cacheReqs(servicing).address      := pipelineMemAccess.req.bits.address
      cacheReqs(servicing).writeData    := pipelineMemAccess.req.bits.writeData
      cacheReqs(servicing).instruction  := pipelineMemAccess.req.bits.instruction
      cacheReqs(servicing).robAddr      := pipelineMemAccess.req.bits.robAddr
    }
  }

  // filling cacheReqs(buffered)
  when(!responseStalled && !cacheReqs(waiting).valid) {
    // servicing the buffered request
    cacheReqs(buffered).valid := false.B
  }.elsewhen(responseStalled && cacheReqs(servicing).valid) {
    // new request has to be buffered
    cacheReqs(buffered).valid        := (pipelineMemAccess.req.ready && pipelineMemAccess.req.valid)
    cacheReqs(buffered).address      := pipelineMemAccess.req.bits.address
    cacheReqs(buffered).writeData    := pipelineMemAccess.req.bits.writeData
    cacheReqs(buffered).instruction  := pipelineMemAccess.req.bits.instruction
    cacheReqs(buffered).robAddr      := pipelineMemAccess.req.bits.robAddr
  }

  // filling cacheReqs(waiting)
  when(lookupResponse.valid && !cacheHit && updateCacheBlock.valid) {
    // caches updated and the request is being reloaded to reservice
    cacheReqs(waiting) := cacheReqs(servicing)
  }.elsewhen(!responseStalled) {
    // request is being moved to cacheReqs(servicing)
    cacheReqs(waiting).valid := false.B
  }

  // handling lookup response
  when(!responseStalled){
    // lookupResponse only changes iff !responseStalled
    lookupResponse.valid := cacheReqs(servicing).valid
    lookupResponse.address := cacheReqs(servicing).address
    lookupResponse.writeData := cacheReqs(servicing).writeData
    lookupResponse.instruction := cacheReqs(servicing).instruction
    lookupResponse.robAddr := cacheReqs(servicing).robAddr
    lookupResponse.byteAlignedData := cache.io.byte_aligned_data
    lookupResponse.tag := cache.io.tag
    lookupResponse.tagValid := cache.io.tag_valid
  }

  // carrying out mem reads for cachemisses
  // sending read request
  arvalid := Mux(arvalid, !lowLevelAXI.ARREADY, (lookupResponse.valid && !cacheHit))
  // getting the data
  rready := Mux(rready, !(lowLevelAXI.RVALID && lowLevelAXI.RLAST), (lowLevelAXI.ARVALID && lowLevelAXI.ARREADY))
  val blockFetched = RegInit(false.B)
  blockFetched := Mux(blockFetched, updateCacheBlock.valid, (lowLevelAXI.RREADY && lowLevelAXI.RVALID && lowLevelAXI.RLAST))

  // organizing the fetched data
  when(lowLevelAXI.RVALID && lowLevelAXI.RREADY) { newCacheBlock := Cat(newCacheBlock(64*dCacheBlockSize-1, 32), lowLevelAXI.RDATA) }

  // commiting writes(and atmoics) to system memory
  val writeInProgress = RegInit(false.B)
  // writes are initiated only for stores(both normal and conditional) and atmoics
  val initiateWrite = (lookupResponse.valid && cacheHit && (lookupResponse.instruction(6, 2)=/=0.U && lookupResponse.instruction(31, 27)=/=2.U)) && !atomicComplete && !writeInProgress
  awvalid := Mux(awvalid, !lowLevelAXI.AWREADY, initiateWrite && !writeInProgress)
  wvalid := Mux(wvalid, !(lowLevelAXI.WREADY && lowLevelAXI.WLAST), initiateWrite && !writeInProgress)
  when(initiateWrite && writeInProgress) { wlast := lookupResponse.instruction(13, 12) =/= 3.U }
  .elsewhen(lowLevelAXI.WREADY && lowLevelAXI.WVALID) { wlast := !wlast }
  writeInProgress := Mux(writeInProgress, !(awvalid || wvalid || bready), initiateWrite)

  val modifiedData = {
    val atmoicW = Cat(Seq.tabulate(2)(i => {
      val original = lookupResponse.byteAlignedData(31 + 32*i, 32*i)
      val rs2      = writeData(31, 0)
      Mux(lookupResponse.address(2) =/= i.U, original, Mux(lookupResponse.instruction(27).asBool, rs2, 
        VecInit.tabulate(8)(i => i match {
          case 0 => original + rs2
          case 1 => original ^ rs2
          case 2 => original | rs2
          case 3 => original & rs2
          case 4 => Mux(original.asSInt < rs2.asSInt, original, rs2)
          case 5 => Mux(original.asSInt < rs2.asSInt, rs2, original)
          case 6 => Mux(original < rs2, original, rs2)
          case 7 => Mux(original < rs2, rs2, original)
        })(lookupResponse.instruction(31, 29))
      ))
    }).reverse)

    val atomicD = {
      val original = lookupResponse.byteAlignedData
      val rs2      = writeData

      Mux(lookupResponse.instruction(27).asBool, rs2, 
      VecInit.tabulate(8)(i => i match {
        case 0 => original + rs2
        case 1 => original ^ rs2
        case 2 => original | rs2
        case 3 => original & rs2
        case 4 => Mux(original.asSInt < rs2.asSInt, original, rs2)
        case 5 => Mux(original.asSInt < rs2.asSInt, rs2, original)
        case 6 => Mux(original < rs2, original, rs2)
        case 7 => Mux(original < rs2, rs2, original)
      })(lookupResponse.instruction(31, 29))
    )}

    val storeMask = VecInit(1.U, 3.U, 15.U, 255.U)(lookupResponse.instruction(13, 12)) << lookupResponse.address(2, 0)
    val rs2Align = lookupResponse.writeData << VecInit(Seq.tabulate(8)(_*8).map{_.U})(lookupResponse.address(2, 0))
    val postStore = Cat(Seq.tabulate(8)(i => Mux(storeMask(i).asBool, rs2Align(7+8*i, 8*i), lookupResponse.byteAlignedData(7+8*i, 8*i))).reverse)

    Mux(lookupResponse.instruction(6,2) === "b0100".U, postStore, Mux(lookupResponse.instruction(12).asBool, atomicD, atmoicW))
  }

  // updating cache after handling cache miss and for stores and atomics
  when(lookupResponse.valid && !cacheHit && blockFetched) {
    updateCacheBlock.valid := true.B
    updateCacheBlock.block := newCacheBlock
    updateCacheBlock.index := lookupResponse.address(31 - dCacheTagWidth-1, 3+dCacheDoubleWordOffsetWidth)
    updateCacheBlock.tag   := lookupResponse.address(31 ,31 - dCacheTagWidth)
    updateCacheBlock.mask := ((1 << dCacheBlockSize)-1).U
  }.elsewhen(lookupResponse.valid && cacheHit && writeInProgress && !(awvalid || wvalid || bready) && !atomicComplete) {
    updateCacheBlock.valid := true.B
    updateCacheBlock.block := modifiedData
    updateCacheBlock.index := lookupResponse.address(31 - dCacheTagWidth-1, 3+dCacheDoubleWordOffsetWidth)
    updateCacheBlock.tag   := lookupResponse.address(31 ,31 - dCacheTagWidth)
    updateCacheBlock.mask := Cat(Seq.tabulate(1<<dCacheDoubleWordOffsetWidth)(_.U === lookupResponse.address(3+dCacheDoubleWordOffsetWidth,3)).reverse)
  }.otherwise {
    updateCacheBlock.valid := false.B
  }

  when(initiateWrite && !writeInProgress) { writeData := Mux(lookupResponse.address(2).asBool, Cat(0.U(32.W), modifiedData(63, 32)), modifiedData) }
  .elsewhen(lowLevelAXI.WREADY && lowLevelAXI.WVALID) { writeData := (writeData >> 32) }

  def responseAtomic(instruction: UInt) = (instruction(6,2) === "b01011".U)

  // completing atmoics
  atomicComplete := Mux(atomicComplete, !(pipelineMemAccess.resp.ready && pipelineMemAccess.resp.valid), (responseAtomic(lookupResponse.instruction) && updateCacheBlock.valid))

  def isLoadReserved(instruction: UInt) = (instruction(6,2) === "b01011".U && instruction(31, 27) === "b00010".U)
  // getting reservation set
  when(isLoadReserved(lookupResponse.instruction) && lookupResponse.valid) {
    semaphoreSet.valid := true.B
    semaphoreSet.address := lookupResponse.address
    semaphoreSet.funct3 := lookupResponse.instruction(14,12)
    semaphoreSet.reservationSet := lookupResponse.byteAlignedData
  }.elsewhen(isStoreConditional(lookupResponse.instruction) && lookupResponse.valid) {
    semaphoreSet.valid := false.B
  }

  // waiting to clean cache
}

object dCache extends App {
  emitVerilog(new dCache)
}