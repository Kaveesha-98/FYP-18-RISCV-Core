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
  val lowLevelMem = IO(new AXI)
  val cleanCaches = IO(new composableInterface)
  val cachesCleaned = IO(new composableInterface)
  val commitFence = RegInit(false.B)


  // initiating BRAM for cache
  val cache = Module(new dCacheRegisters)
  cache.io.clock := clock
  cache.io.reset := reset

  
  val next :: buffered :: servicing :: Nil = Enum(3)
  val requests = RegInit(VecInit(Seq.fill(3)(RegInit((new Bundle {
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

  val results = RegInit(VecInit(Seq.fill(2)(RegInit((new Bundle {
    val valid = Bool()
    val address = UInt(64.W)
    val byteAlignedData = UInt(64.W)
    val tag = UInt(dCacheTagWidth.W)
    val tagValid = Bool()
    val instruction = UInt(32.W)
    val writeData = UInt(64.W)
    val robAddr = UInt(robAddrWidth.W)
  }).Lit(
    _.valid -> false.B,
    _.address -> 0.U,
    _.byteAlignedData -> 0.U,
    _.tag -> 0.U,
    _.tagValid -> false.B,
    _.instruction -> 0.U,
    _.writeData -> 0.U,
    _.robAddr -> 0.U
  )))))

  val cacheFill = RegInit((new Bundle {
    val valid = Bool()
    val block = UInt((dCacheBlockSize*64).W)
    val mask = UInt((1 << dCacheDoubleWordOffsetWidth).W)
  }).Lit(
    _.valid -> false.B,
    _.block -> 0.U,
    _.mask -> 0.U
  ))

  val reservation = RegInit((new Bundle {
    val free = Bool()
    val set = UInt(64.W)
    val wasDouble = Bool()
    val address = UInt(64.W)
  }).Lit(
    _.free -> true.B
  ))

  // processing instruction that modifies memory
  val processingMod = Seq(requests(servicing), results(next), results(buffered)).map(i => i.valid && (i.instruction(6,2) =/= 0.U)).reduce(_ || _)
  val cacheMissed = !(results(next).tagValid && (results(next).address >> (32-dCacheTagWidth)) === results(next).tag) && results(next).valid 
  val arvalid, rready = RegInit(false.B)
  val awvalid, wvalid, bready, wlast = RegInit(false.B) 
  
  val writeMask = VecInit(1.U, 3.U, 15.U, 255.U)(results(next).instruction(13, 12))
  val alignedMask = writeMask << results(next).address(2,0)
  val alignedWriteData = results(next).writeData << (VecInit.tabulate(8)(i => (i*8).U)(results(next).address(2, 0)))
  val dataPostMod = {
    val atom32 = {
      val src1 = Mux(results(next).address(2).asBool, results(next).byteAlignedData(63, 32), results(next).byteAlignedData(31, 0))
      val src2 = results(next).writeData(31, 0)

      val mod = VecInit.tabulate(8)(i => i match {
        case 0 => Mux(results(next).instruction(27).asBool, src2, src1 + src2)
        case 1 => src1 ^ src2
        case 2 => src1 | src2
        case 3 => src1 & src2
        case 4 => Mux(src1.asSInt < src2.asSInt, src1, src2)
        case 5 => Mux(src1.asSInt < src2.asSInt, src2, src1)
        case 6 => Mux(src1 < src2, src1, src2)
        case 7 => Mux(src1 < src2, src2, src1)
      })(results(next).instruction(31, 29))
      
      Cat((Seq.tabulate(2)(i => 
        Mux(results(next).address(2) === i.U, mod, results(next).byteAlignedData(31 + 32*i, 32*i))  
      )).reverse)
    }

    val atom64 = {
      val src1 = results(next).byteAlignedData
      val src2 = results(next).writeData

      VecInit.tabulate(8)(i => i match {
        case 0 => Mux(results(next).instruction(27).asBool, src2, src1 + src2)
        case 1 => src1 ^ src2
        case 2 => src1 | src2
        case 3 => src1 & src2
        case 4 => Mux(src1.asSInt < src2.asSInt, src1, src2)
        case 5 => Mux(src1.asSInt < src2.asSInt, src2, src1)
        case 6 => Mux(src1 < src2, src1, src2)
        case 7 => Mux(src1 < src2, src2, src1)
      })(results(next).instruction(31, 29))
    }

    val modData = Mux(results(next).instruction(2).asBool, Mux(results(next).instruction(12).asBool, atom64, atom32), alignedWriteData)

    Cat((Seq.tabulate(8)(i => Mux(alignedMask(i).asBool, modData(7 + 8*i, 8*i), results(next).byteAlignedData(7 + 8*i, 8*i)))).reverse)
  }

  when(lowLevelMem.RREADY && lowLevelMem.RVALID) { 
    cacheFill.block := Cat(lowLevelMem.RDATA, cacheFill.block(64*dCacheBlockSize-1, 32)) 
    cacheFill.mask := ~(0.U((1<<dCacheDoubleWordOffsetWidth).W))
  }.elsewhen(results(next).valid && !cacheMissed && !awvalid && !wvalid && !bready && (results(next).instruction(6,2) =/= 0.U)) {
    cacheFill.block := dataPostMod << VecInit.tabulate(dCacheBlockSize)(i => (64*i).U)(results(next).address(dCacheDoubleWordOffsetWidth+3,3)) 
    cacheFill.mask := 1.U(dCacheBlockSize.W) << (results(next).address(dCacheDoubleWordOffsetWidth+3-1,3))
  }

  when(!arvalid) { arvalid := cacheMissed && !rready && !cacheFill.valid } 
  .otherwise { arvalid := !(lowLevelMem.ARVALID && lowLevelMem.ARREADY) }

  when(!rready) { rready := (lowLevelMem.ARVALID && lowLevelMem.ARREADY) }
  .otherwise { rready := !(lowLevelMem.RLAST && lowLevelMem.RREADY && lowLevelMem.RVALID) }

  /* when(!cacheFill.valid) { cacheFill.valid := lowLevelMem.RLAST && lowLevelMem.RREADY && lowLevelMem.RVALID }
  .otherwise { cacheFill.valid := results(next).valid && !cacheMissed && !awvalid && !wvalid && !bready && (results(next).instruction(6,2) =/= 0.U) }
 */
  val scPass = !reservation.free && (reservation.address === results(next).address) && (reservation.wasDouble === results(next).instruction(12).asBool) && Mux(reservation.wasDouble, reservation.set === results(next).byteAlignedData, Mux(results(next).address(2).asBool, results(next).byteAlignedData(63, 32) === reservation.set(63, 32), results(next).byteAlignedData(31, 0) === reservation.set(31, 0))) 
  val scSuccess = (Cat(results(next).instruction(28, 27), results(next).instruction(3, 2)) =/= "b1111".U) || scPass
  cacheFill.valid := (results(next).valid && !cacheMissed && !awvalid && !wvalid && !bready && (results(next).instruction(6,2) =/= 0.U) && (Cat(results(next).instruction(28, 27), results(next).instruction(3, 2)) =/= "b1011".U) && scSuccess) || (lowLevelMem.RLAST && lowLevelMem.RREADY && lowLevelMem.RVALID)

  when(results(next).instruction(3, 2) === 3.U && results(next).valid && results(next).instruction(28).asBool) {
    when(results(next).instruction(27).asBool) {
      reservation.free := true.B
    }.otherwise {
      reservation.free := false.B
      reservation.address := results(next).address
      reservation.set := results(next).byteAlignedData
      reservation.wasDouble := results(next).instruction(12).asBool
    }
  }

  Seq(awvalid, wvalid)
  .zip(Seq(lowLevelMem.AWREADY, (lowLevelMem.WREADY && lowLevelMem.WLAST)))
  .foreach{ case (ctrlReg, cond) => ctrlReg := Mux(ctrlReg, !cond, results(next).valid && !cacheMissed && !awvalid && !wvalid && !bready && (results(next).instruction(6,2) =/= 0.U) && (Cat(results(next).instruction(28, 27), results(next).instruction(3, 2)) =/= "b1011".U) && scSuccess) }
  /* when(!awvalid) { awvalid := results(next).valid && !cacheMissed && !awvalid && !wvalid && !bready && (results(next).instruction(6,2) =/= 0.U)}
  .otherwise { awvalid := !lowLevelMem.AWREADY } */

  when(!bready){
    switch(Cat(awvalid.asUInt, wvalid.asUInt)){
      is("b11".U) { bready := (lowLevelMem.AWREADY && lowLevelMem.WREADY && lowLevelMem.WLAST) }
      is("b10".U) { bready := lowLevelMem.AWREADY }
      is("b01".U) { bready := (lowLevelMem.WREADY && lowLevelMem.WLAST) }
    }
  }.otherwise{ bready := !lowLevelMem.BVALID }

  when(results(next).valid && !cacheMissed && !awvalid && !wvalid && !bready && (results(next).instruction(6,2) =/= 0.U)) {
    wlast := (results(next).instruction(13,12) =/= 3.U)
  }.elsewhen(wlast) { wlast := !(lowLevelMem.WVALID && lowLevelMem.WREADY) }
  .otherwise { wlast := (lowLevelMem.WVALID && lowLevelMem.WREADY) }

  val writeData = Reg(UInt(64.W))
  val wstrb = Reg(UInt(8.W))
  when(results(next).valid && results(next).instruction(6, 2) =/= 0.U && !wvalid) { 
    writeData := Mux(results(next).address(2).asBool, Cat(0.U(32.W), dataPostMod(63, 32)), dataPostMod) 
    wstrb := Mux(results(next).address(2).asBool, Cat(0.U(4.W), alignedMask(7,4)), alignedMask)
  }.elsewhen(lowLevelMem.WVALID && lowLevelMem.WREADY) { 
    writeData := Cat(0.U(32.W), writeData(63, 32)) 
    wstrb := Cat(0.U(4.W), wstrb(7,4))
  }

  cache.io.write_block := cacheFill.block
  cache.io.write_in := cacheFill.valid
  cache.io.write_line_index := results(next).address(dCacheLineWidth + dCacheDoubleWordOffsetWidth + 3, dCacheDoubleWordOffsetWidth + 3)
  cache.io.write_tag := results(next).address(31, dCacheLineWidth + dCacheDoubleWordOffsetWidth + 3)
  cache.io.write_mask := cacheFill.mask
  cache.io.clock := clock
  cache.io.reset := reset

  val cacheStalled = cacheMissed || (pipelineMemAccess.resp.valid && !pipelineMemAccess.resp.ready)
  when((!cacheMissed && (pipelineMemAccess.resp.ready || results(next).instruction(6, 2) =/= 0.U)) || !results(next).valid) {
    when(results(buffered).valid) { results(next) := results(buffered) }
    .elsewhen(results(next).valid && results(next).instruction(6, 2) =/= 0.U) {

      results(next).valid := !(lowLevelMem.BVALID && lowLevelMem.BREADY) || results(next).instruction(3, 2) === 3.U
      when(results(next).instruction(3, 2) === 3.U && scSuccess && (Cat(results(next).instruction(28, 27), results(next).instruction(3, 2)) =/= "b1011".U)){
        results(next).instruction := Mux((lowLevelMem.BVALID && lowLevelMem.BREADY), results(next).instruction, Cat(results(next).instruction(31, 7), 3.U(7.W)))
        when((Cat(results(next).instruction(28, 27), results(next).instruction(3, 2)) === "b1111".U)) {
          results(next).byteAlignedData := 0.U
        }
      }.elsewhen(results(next).instruction(3, 2) === 3.U) {
        results(next).instruction := Cat(results(next).instruction(31, 7), 3.U(7.W))
        when((Cat(results(next).instruction(28, 27), results(next).instruction(3, 2)) === "b1111".U)) {
          results(next).byteAlignedData := Cat(0.U(31.W), 1.U & results(next).instruction(12), 0.U(31.W), 1.U)
        }
      }

    }.otherwise {
      results(next).valid := requests(servicing).valid
      results(next).address := requests(servicing).address
      results(next).instruction := requests(servicing).instruction
      results(next).tag := cache.io.tag
      results(next).tagValid := cache.io.tag_valid
      results(next).byteAlignedData := cache.io.byte_aligned_data
      results(next).robAddr := requests(servicing).robAddr
      results(next).writeData := requests(servicing).writeData
    }
  }.elsewhen(cacheMissed && cacheFill.valid) {
    results(next).byteAlignedData := (VecInit.tabulate(1 << dCacheDoubleWordOffsetWidth)(i => cacheFill.block(63 + 64*i, 64*i)))(results(next).address(dCacheDoubleWordOffsetWidth+3, 3))
    results(next).tag := results(next).address(32, 32 - dCacheTagWidth)
    results(next).tagValid := true.B
  }

  when(!results(buffered).valid) {
    results(buffered).valid := requests(servicing).valid && results(next).valid && (cacheMissed || !pipelineMemAccess.resp.ready)
    results(buffered).address := requests(servicing).address
    results(buffered).instruction := requests(servicing).instruction
    results(buffered).tag := cache.io.tag
    results(buffered).tagValid := cache.io.tag_valid
    results(buffered).byteAlignedData := cache.io.byte_aligned_data
    results(buffered).robAddr := requests(servicing).robAddr
    results(buffered).writeData := requests(servicing).writeData
  }.elsewhen(cacheMissed && cacheFill.valid && (results(next).address(31, iCacheOffsetWidth + 2) === results(buffered).address(31, iCacheOffsetWidth + 2))) {
    results(buffered).byteAlignedData := (VecInit.tabulate(1 << dCacheDoubleWordOffsetWidth)(i => cacheFill.block(63 + 64*i, 64*i)))(results(buffered).address(dCacheDoubleWordOffsetWidth+3, 3))
    results(buffered).tag := results(next).address(iCacheTagWidth + iCacheLineWidth + iCacheOffsetWidth + 2, iCacheLineWidth + iCacheOffsetWidth + 2)
    results(buffered).tagValid := true.B
  }.elsewhen(!cacheStalled) {
    results(buffered).valid := false.B
  }

  when(!results(buffered).valid) {
    requests(servicing) := requests(next)
    requests(servicing).valid := !cacheStalled && requests(next).valid && !processingMod
  }.otherwise {
    requests(servicing).valid := false.B
  }

  when(((!cacheStalled && !results(buffered).valid && !processingMod) || !requests(next).valid)) {
    when(requests(buffered).valid) { requests(next) := requests(buffered) }
    .otherwise {
      requests(next).valid := (pipelineMemAccess.req.ready && pipelineMemAccess.req.valid)
      requests(next).address := pipelineMemAccess.req.bits.address
      requests(next).instruction := pipelineMemAccess.req.bits.instruction
      requests(next).robAddr := pipelineMemAccess.req.bits.robAddr
      requests(next).writeData := pipelineMemAccess.req.bits.writeData
    }
  }

  when(!requests(buffered).valid) {
    requests(buffered).valid := (cacheStalled || results(buffered).valid) && (pipelineMemAccess.req.ready && pipelineMemAccess.req.valid) && requests(next).valid && processingMod
    requests(buffered).address := pipelineMemAccess.req.bits.address
    requests(buffered).instruction := pipelineMemAccess.req.bits.instruction
    requests(buffered).robAddr := pipelineMemAccess.req.bits.robAddr
    requests(buffered).writeData := pipelineMemAccess.req.bits.writeData

  }.otherwise {
    requests(buffered).valid := (!cacheStalled && !results(buffered).valid)
  }

  pipelineMemAccess.resp.valid := !cacheMissed && results(next).valid && (results(next).instruction(6,2) === 0.U)
  pipelineMemAccess.resp.bits.address := results(next).address
  pipelineMemAccess.resp.bits.byteAlignedData := results(next).byteAlignedData
  pipelineMemAccess.resp.bits.funct3 := results(next).instruction(14, 12)
  pipelineMemAccess.resp.bits.robAddr := results(next).robAddr

  pipelineMemAccess.req.ready := !requests(buffered).valid && !commitFence

  cache.io.address := requests(next).address

  lowLevelMem.ARADDR := Cat(results(next).address(31, 3+dCacheDoubleWordOffsetWidth), 0.U((3+dCacheDoubleWordOffsetWidth).W))
  lowLevelMem.ARBURST := 1.U
  lowLevelMem.ARCACHE := 2.U
  lowLevelMem.ARID := 0.U
  lowLevelMem.ARLEN := (dCacheBlockSize*2 - 1).U
  lowLevelMem.ARLOCK := 0.U
  lowLevelMem.ARPROT := 0.U
  lowLevelMem.ARQOS := 0.U
  lowLevelMem.ARSIZE := 2.U
  lowLevelMem.ARVALID := arvalid

  lowLevelMem.RREADY := rready

  lowLevelMem.AWADDR := results(next).address
  lowLevelMem.AWBURST := 1.U
  lowLevelMem.AWCACHE := 2.U
  lowLevelMem.AWID := 0.U
  lowLevelMem.AWLEN := (results(next).instruction(13,12) === 3.U).asUInt
  lowLevelMem.AWLOCK := 0.U
  lowLevelMem.AWPROT := 0.U
  lowLevelMem.AWQOS := 0.U
  lowLevelMem.AWSIZE := 2.U
  lowLevelMem.AWVALID := awvalid

  lowLevelMem.WDATA := writeData(31, 0)
  lowLevelMem.WLAST := wlast
  lowLevelMem.WSTRB := wstrb
  lowLevelMem.WVALID := wvalid

  lowLevelMem.BREADY := bready

  when(!commitFence) {
    commitFence := cleanCaches.fired
  }.otherwise {
    commitFence := (Seq(requests, results).flatten.map(_.valid).reduce(_ || _)) || !cachesCleaned.fired
  }
  cachesCleaned.ready := !(Seq(requests, results).flatten.map(_.valid).reduce(_ || _)) && commitFence
  cleanCaches.ready := !commitFence
}

object dCache extends App {
  emitVerilog(new dCache)
}