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

class pullPipelineMemReq extends composableInterface {
  val address  = Input(UInt(64.W))
  val writeData = Input(UInt(64.W)) // not aligned
  val robAddr = Input(UInt(robAddrWidth.W))
  val instruction = Input(UInt(32.W))
}

class AXI(
  idWidth: Int = 2,
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
  /* val fromPipeline = IO(new pullPipelineMemReq)
  val dCache = IO(new Bundle{
    val req = DecoupledIO(new Bundle{
      val address   = UInt(64.W)
      val writeData = UInt(64.W) // right justified as in rs2
      val writeMask = UInt(8.W)
      val funct3    = UInt(3.W)
      val isWrite   = Bool()
    })
    val resp = IO(new Bundle{
      val justifiedLoadData = UInt(64.W)
    })
  })

  val peripherals = IO(new AXI)

  val dram :: peripheral :: Nil = Enum(2)

  val arvalid, rready, awvalid, wvalid, wready = RegInit(false.B)
  
  // if interfaces are ready then the request is store in a buffer
  val reqBuffer = RegInit((new Bundle{
    val entryType = dram.cloneType
		val free 	= Bool()
    val address  = Input(UInt(64.W))
    val writeData = Input(UInt(64.W)) // not aligned
    val robAddr = Input(UInt(robAddrWidth.W))
    val instruction = Input(UInt(32.W))
	}).Lit(
    _.entryType     -> dram,
		_.free 	        -> true.B,
    _.address       -> 0.U,
		_.writeData 	  -> 0.U,
		_.robAddr		    -> 0.U,
    _.instruction   -> 0.U
	))

  val peripheralRequest = RegInit(reqBuffer.cloneType.Lit(
    _.entryType     -> peripheral,
		_.free 	        -> true.B,
    _.address       -> 0.U,
		_.writeData 	  -> 0.U,
		_.robAddr		    -> 0.U,
    _.instruction   -> 0.U
  ))

  // buffers the current request being served
  val currentRequests = RegInit(VecInit(Seq.fill(2)(RegInit((new Bundle{
		val free 	= Bool()
    val address  = Input(UInt(64.W))
    val writeData = Input(UInt(64.W)) // not aligned
    val robAddr = Input(UInt(robAddrWidth.W))
    val instruction = Input(UInt(32.W))
	}).Lit(
		_.free 	        -> true.B,
    _.address       -> 0.U,
		_.writeData 	  -> 0.U,
		_.robAddr		    -> 0.U,
    _.instruction   -> 0.U
	)))))

  val dramAccess = (fromPipeline.address >= ramBaseAddress) && (fromPipeline.address <= ramHighAddress)
  val entryType = Mux(dramAccess, dram, peripheral)

  fromPipeline.ready := reqBuffer.free

  // a request (for dram or peripheral can come from)
  dCache.req.valid := (fromPipeline.ready && fromPipeline.fired) || (!reqBuffer.free && (reqBuffer.entryType === dram))
   */

}