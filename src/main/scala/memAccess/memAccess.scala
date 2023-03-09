package pipeline.memAccess

/**
  * This is a place holder code will change in the future in major ways
  * like removing a dedicated register to keep track of state.
  */

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

import pipeline.ports._
import pipeline.configuration.coreConfiguration._
import os.read

class pullPipelineMemReq extends composableInterface {
  val address  = Input(UInt(64.W))
  val writeData = Input(UInt(64.W)) // not aligned
  val robAddr = Input(UInt(robAddrWidth.W))
  val instruction = Input(UInt(32.W))
}

class pushMemResultToRob extends composableInterface {
  val robAddr = Output(UInt(robAddrWidth.W))
  val writeBackData = Output(UInt(64.W))
} 

class AXI(
  idWidth: Int = 1,
  addressWidth: Int = 32,
  dataWidth: Int = 32
)extends Bundle {
  val AWID = Output(UInt(idWidth.W))
	val AWADDR = Output(UInt(addressWidth.W))
	val AWLEN = Output(UInt(8.W))
	val AWSIZE = Output(UInt(3.W))
	val AWBURST = Output(UInt(2.W))
	val AWLOCK = Output(UInt(1.W))
	val AWCACHE = Output(UInt(4.W))
	val AWPROT = Output(UInt(3.W))
	val AWQOS = Output(UInt(4.W))
	val AWVALID = Output(Bool())
	val AWREADY = Input(Bool())

	val WDATA = Output(UInt(dataWidth.W))
	val WSTRB = Output(UInt((dataWidth/8).W))
	val WLAST = Output(Bool())
	val WVALID = Output(Bool())
	val WREADY = Input(Bool())

	val BID = Input(UInt(idWidth.W))
	val BRESP = Input(UInt(2.W))
	val BVALID = Input(Bool())
	val BREADY = Output(Bool())

	val ARID = Output(UInt(idWidth.W))
	val ARADDR = Output(UInt(addressWidth.W))
	val ARLEN = Output(UInt(8.W))
	val ARSIZE = Output(UInt(3.W))
	val ARBURST = Output(UInt(2.W))
	val ARLOCK = Output(UInt(1.W))
	val ARCACHE = Output(UInt(4.W))
	val ARPROT = Output(UInt(3.W))
	val ARQOS = Output(UInt(4.W))
	val ARVALID = Output(Bool())
	val ARREADY = Input(Bool())

	val RID = Input(UInt(idWidth.W))
	val RDATA = Input(UInt(dataWidth.W))
	val RRESP = Input(UInt(2.W))
	val RLAST = Input(Bool())
	val RVALID = Input(Bool())
	val RREADY = Output(Bool())
}

class memAccess extends Module{
  val fromPipeline = IO(new pullPipelineMemReq)
  val dCache = IO(new Bundle{
    val req = DecoupledIO(new Bundle{
      val address   = UInt(64.W)
      val writeData = UInt(64.W) // right justified as in rs2
      val funct3    = UInt(3.W)
      val isWrite   = Bool()
      val robAddr   = UInt(robAddrWidth.W)
    })
    val resp = Flipped(DecoupledIO(new Bundle{
      // writes do not return a response
      val byteAlignedData = UInt(64.W)
      val address = UInt(64.W)
      val funct3 = UInt(3.W)
      val robAddr = UInt(robAddrWidth.W)
    }))
  })

  val peripherals = IO(new AXI)

  val toRob = IO(new pushMemResultToRob)

  // request to implement fences
  val carryOutFence = IO(new composableInterface)

  val cleanAllCacheLines = IO(new composableInterface)

  val waitingAllReqToFinish = RegInit(false.B)

  val dram :: peripheral :: Nil = Enum(2)

  val arvalid, rready, awvalid, wvalid, wlast, bready = RegInit(false.B)
  
  // if interfaces are ready then the request is store in a buffer
  val reqBuffer = RegInit((new Bundle{
    val entryType = dram.cloneType
		val free 	= Bool()
    val address  = UInt(64.W)
    val writeData = UInt(64.W) // not aligned
    val robAddr = UInt(robAddrWidth.W)
    val instruction = UInt(32.W)
	}).Lit(
    _.entryType     -> dram,
		_.free 	        -> true.B,
    _.address       -> 0.U,
		_.writeData 	  -> 0.U,
		_.robAddr		    -> 0.U,
    _.instruction   -> 0.U
	))

  val dramAccess = (fromPipeline.address >= ramBaseAddress.U) && (fromPipeline.address <= ramHighAddress.U)
  val entryType = Mux(dramAccess, dram, peripheral)

  fromPipeline.ready := reqBuffer.free && !waitingAllReqToFinish

  // a request (for dram or peripheral can come from buffer or "fromPipeline" interface)
  // giving request to dCache
  dCache.req.valid := (fromPipeline.ready && fromPipeline.fired && dramAccess) || (!reqBuffer.free && (reqBuffer.entryType === dram))
  when( !reqBuffer.free && (reqBuffer.entryType === dram) ) {
    dCache.req.bits.address   := reqBuffer.address
    dCache.req.bits.writeData := reqBuffer.writeData
    dCache.req.bits.funct3    := reqBuffer.instruction(14, 12)
    dCache.req.bits.isWrite   := reqBuffer.instruction(5).asBool
    dCache.req.bits.robAddr   := reqBuffer.robAddr
  }.otherwise {
    dCache.req.bits.address    := fromPipeline.address
    dCache.req.bits.writeData  := fromPipeline.writeData
    dCache.req.bits.funct3     := fromPipeline.instruction(14, 12)
    dCache.req.bits.isWrite    := fromPipeline.instruction(5).asBool
    dCache.req.bits.robAddr    := fromPipeline.robAddr
  }

  val peripheralRequest = RegInit(reqBuffer.cloneType.Lit(
    _.entryType     -> peripheral,
		_.free 	        -> true.B,
    _.address       -> 0.U,
		_.writeData 	  -> 0.U,
		_.robAddr		    -> 0.U,
    _.instruction   -> 0.U
  ))

  val offsetLookUp = VecInit.tabulate(4)(i => (i*8).U)

  when(peripheralRequest.free) {
    // When peripheralRequest.free, all arvalid, rready, awvalid, wvalid, bready and wlast should be free
    when((fromPipeline.ready && fromPipeline.fired && !dramAccess) || (!reqBuffer.free && (reqBuffer.entryType =/= dram))) {
      peripheralRequest.free := false.B
      arvalid := Mux(!reqBuffer.free && (reqBuffer.entryType =/= dram), !reqBuffer.instruction(5).asBool, !fromPipeline.instruction(5).asBool)
      rready := Mux(!reqBuffer.free && (reqBuffer.entryType =/= dram), !reqBuffer.instruction(5).asBool, !fromPipeline.instruction(5).asBool)
      awvalid := Mux(!reqBuffer.free && (reqBuffer.entryType =/= dram), reqBuffer.instruction(5).asBool, fromPipeline.instruction(5).asBool)
      wvalid := Mux(!reqBuffer.free && (reqBuffer.entryType =/= dram), reqBuffer.instruction(5).asBool, fromPipeline.instruction(5).asBool)
      bready := Mux(!reqBuffer.free && (reqBuffer.entryType =/= dram), reqBuffer.instruction(5).asBool, fromPipeline.instruction(5).asBool)
      wlast := Mux(!reqBuffer.free && (reqBuffer.entryType =/= dram), reqBuffer.instruction(5).asBool && (reqBuffer.instruction(13, 12) < 3.U), fromPipeline.instruction(5).asBool && (fromPipeline.instruction(13, 12) < 3.U))
    }
    // buffered request is given priority
    when(!reqBuffer.free && (reqBuffer.entryType =/= dram)) {
      peripheralRequest.address := reqBuffer.address
      // data port is only 32 bit
      peripheralRequest.writeData := Mux(reqBuffer.instruction(14, 12) === 3.U, reqBuffer.writeData, reqBuffer.writeData << offsetLookUp(reqBuffer.address(1, 0)))
      peripheralRequest.robAddr := reqBuffer.robAddr
      peripheralRequest.instruction := reqBuffer.instruction
    }.otherwise {
      peripheralRequest.address := fromPipeline.address
      peripheralRequest.writeData := Mux(fromPipeline.instruction(14, 12) === 3.U, fromPipeline.writeData, fromPipeline.writeData << offsetLookUp(fromPipeline.address(1, 0)))
      peripheralRequest.robAddr := fromPipeline.robAddr
      peripheralRequest.instruction := fromPipeline.instruction
    }
  }

  peripherals.ARADDR := peripheralRequest.address
  peripherals.ARBURST := 1.U
  peripherals.ARCACHE := 2.U
  peripherals.ARID := 0.U // only one transaction is managed at a time
  peripherals.ARLEN := Mux(peripheralRequest.instruction(13, 12) === 3.U, 1.U, 0.U) // 32-bit bus requires 2 bursts for 64-bit reads
  peripherals.ARLOCK := 0.U
  peripherals.ARPROT := 0.U
  peripherals.ARQOS := 0.U
  peripherals.ARSIZE := (peripheralRequest.instruction(14, 12) & 3.U)
  peripherals.ARVALID := arvalid

  peripherals.RREADY := rready

  peripherals.AWADDR := peripheralRequest.address
  peripherals.AWBURST := 1.U
  peripherals.AWCACHE := 2.U
  peripherals.AWID := 0.U
  peripherals.AWLEN := Mux(peripheralRequest.instruction(13, 12) === 3.U, 1.U, 0.U) // 32-bit bus requires 2 bursts for 64-bit writes
  peripherals.AWLOCK := 0.U
  peripherals.AWPROT := 0.U
  peripherals.AWQOS := 0.U
  peripherals.AWSIZE := (peripheralRequest.instruction(14, 12) & 3.U)
  peripherals.AWVALID := awvalid

  val maskLookUp = VecInit.tabulate(4)(i => i match {
    case 0 => 1.U
    case 1 => 3.U
    case 2 => 15.U
    case 3 => 15.U
  })
  peripherals.WDATA := peripheralRequest.writeData(31, 0)
  peripherals.WLAST := wlast
  peripherals.WSTRB := (maskLookUp(peripheralRequest.instruction(13, 12)) << peripheralRequest.address(1, 0))
  peripherals.WVALID := wvalid

  peripherals.BREADY := true.B

  val readData = Reg(UInt(64.W))
  val justifiedRDATA = peripherals.RDATA >> peripheralRequest.address(1, 0)

  when(peripherals.RREADY && peripherals.RVALID) {
    when(peripherals.RLAST && peripheralRequest.instruction(13, 12) === 3.U) {
      readData := Cat(peripherals.RDATA, readData(31, 0))
    }.otherwise {
      readData := VecInit.tabulate(4)(i => i match {
        case 0 => Cat(Fill(56 , ((~peripheralRequest.instruction(14)) & justifiedRDATA(7))), justifiedRDATA(7, 0))
        case 1 => Cat(Fill(48 , ((~peripheralRequest.instruction(14)) & justifiedRDATA(15))), justifiedRDATA(15, 0))
        case 2 => Cat(Fill(32 , ((~peripheralRequest.instruction(14)) & justifiedRDATA(31))), justifiedRDATA(31, 0))
        case 3 => Cat(0.U(32.W), justifiedRDATA)
      })(peripheralRequest.instruction(13, 12))
    }
    // reads finished
    rready := !peripherals.RLAST
  }
  // read request sent
  when(peripherals.ARREADY && peripherals.ARVALID) { arvalid := false.B }

  // write address sent
  when(peripherals.AWVALID && peripherals.AWREADY) { awvalid := false.B }

  // sending write data
  when(peripherals.WVALID && peripherals.WREADY) {
    when(!wlast){
      // for 64-bit transfers
      peripheralRequest.writeData := Cat(0.U(32.W), peripheralRequest.writeData(63, 32))
    }
    wvalid := !wlast // will turnoff if last transaction
    wlast := !wlast
  }

  // accepting write response
  when(peripherals.BREADY && peripherals.BVALID) { bready := false.B }

  val peripheralRequestServed = !(peripheralRequest.free) && !(arvalid || rready || awvalid || wvalid || bready)

  // both responses(from peripheral and dram) are buffered here(responseBuffers(0)) before pushing to commit
  // In case both peripheral and dram have responses pending the peripheral is stored to responseBuffers(1)
  // responses will be sent to buffers iff responseBuffers(1).free
  val responseBuffers = RegInit(VecInit(Seq.fill(2)(RegInit((new Bundle{
		val free 	= Bool()
    val writeBackData = UInt(64.W)
		val robAddr = UInt(robAddrWidth.W)
	}).Lit(
		_.free 	-> true.B,
    _.writeBackData -> 0.U,
		_.robAddr 	-> 0.U
	)))))

  dCache.resp.ready := responseBuffers(1).free

  // peripheral request served
  when(peripheralRequestServed && (responseBuffers(0).free || peripheralRequest.instruction(5).asBool)) { peripheralRequest.free := true.B }
  val cacheResponseJustified = dCache.resp.bits.byteAlignedData >> dCache.resp.bits.address(2, 0)

  // filling the response buffers
  when(toRob.fired && !responseBuffers(0).free) {
    // current response in responseBuffers is full
    // buffered response is given priority
    when(!responseBuffers(1).free) {
      // clearing buffered response
      responseBuffers(0) := responseBuffers(1)
      responseBuffers(1).free := false.B
    }.elsewhen(dCache.resp.ready && dCache.resp.valid) {
      responseBuffers(0).robAddr := dCache.resp.bits.robAddr
      responseBuffers(0).writeBackData := VecInit.tabulate(8)(i => i match {
        case 0 => Cat(Fill(56, cacheResponseJustified(7)), cacheResponseJustified(7, 0))
        case 1 => Cat(Fill(48, cacheResponseJustified(15)), cacheResponseJustified(15, 0))
        case 2 => Cat(Fill(32, cacheResponseJustified(31)), cacheResponseJustified(31, 0))
        case 3 => cacheResponseJustified
        case 4 => Cat(0.U(56.W), cacheResponseJustified(7, 0))
        case 5 => Cat(0.U(48.W), cacheResponseJustified(15, 0))
        case 6 => Cat(0.U(32.W), cacheResponseJustified(31, 0))
        case 7 => 0.U
      })(dCache.resp.bits.funct3)
    }.elsewhen(peripheralRequestServed && !peripheralRequest.instruction(5).asBool) {
      // only reads get written to response buffers
      responseBuffers(0).robAddr := peripheralRequest.robAddr
      responseBuffers(0).writeBackData := readData
    }.otherwise {
      responseBuffers(0).free := true.B
    }
  }

  // pending cache response and a peripheral access at the same time
  when(responseBuffers(1).free && (dCache.resp.ready && dCache.resp.valid) && peripheralRequestServed && !peripheralRequest.instruction(5).asBool) {
    // periperal is scheduled after cache access
    responseBuffers(1).robAddr := peripheralRequest.robAddr
    responseBuffers(1).writeBackData := readData
  }

  toRob.ready := !responseBuffers(0).free
  toRob.robAddr := responseBuffers(0).robAddr
  toRob.writeBackData := responseBuffers(0).writeBackData

  carryOutFence.ready := !waitingAllReqToFinish
  // theoritically when and after this rule is fired there should be no instructions from pipeline until all address spaces are coherent
  when(carryOutFence.ready && carryOutFence.fired) { waitingAllReqToFinish := true.B }

  cleanAllCacheLines.ready := waitingAllReqToFinish && reqBuffer.free && peripheralRequest.free

  // after commiting all requests to memory, wait for next access
  when(cleanAllCacheLines.ready && cleanAllCacheLines.fired) { waitingAllReqToFinish := false.B }
}

object memAccess extends App {
  (new stage.ChiselStage).emitVerilog(new memAccess)
}