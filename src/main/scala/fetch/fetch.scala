


import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

// definition of all ports can be found here


/**
  * Question: does the fetch unit need to communicate the predicted next address along with
  * instruction to the decode unit?
  * It will be 64-bit that will be rarely used(Only for branch instructions it will be used)
  */

/**
  * Functionality - decode unit communicates the pc of the instruction
  * its epecting through the decodeIssue.expeted port.
  * 
  * Results of branch predictions are returned on branchRes port.
  * 
  * cache is used to access instruction memory.
  *
  * additional information on ports can be found on common/ports.scala
  */
class predictor extends Module {
  val io = IO(new Bundle {
    val branchres = new branchResToFetch
    val curr_pc = Input(UInt(64.W))
    val next_pc = Output(UInt(64.W))
  })
  io.next_pc := io.curr_pc + 4.U
  io.branchres.ready := 1.B
}

class fetch(val fifo_size: Int) extends Module {
  /**
    * Inputs and Outputs of the module
    */

  // interface with cache
  val cache = IO(new Bundle {
    val req   = DecoupledIO(UInt(64.W)) // address is 64 bits wide for 64-bit machine
    val resp  = Flipped(DecoupledIO(UInt(32.W))) // instructions are 32 bits wide
  })

  // issuing instructions to pc
  val toDecode = IO(new issueInstrFrmFetch)

  // receiving results of branches in order
  val branchRes   = IO(new branchResToFetch)

  // request to implement fence_i
  val carryOutFence = IO(new composableInterface)

  // request to update cache lines
  // once fired all pending requests to cache are invalidated
  // updateAllCachelines and cache.req **cannot** be ready at the same time
  val updateAllCachelines = IO(new composableInterface)

  // after updateAllCachelines is fired, this should be ready
  // will fire once all cachelines in I$ is updated
  val cachelinesUpdatesResp = IO(new composableInterface)

  /**
    * Internal of the module goes here
    */

  //register defs
  val PC = RegInit(0.U(64.W))
  val redirect_bit= RegInit(0.U(1.W))
  val handle_fenceI= RegInit(0.U(1.W))
  val clear_cache_req= RegInit(0.U(1.W))
  val cache_cleared= RegInit(0.U(1.W))
  val fence_pending= RegInit(0.U(1.W))


  // initialize BHT and fifo buffer
  val predictor = Module(new predictor)
  predictor.io.branchres <> branchRes
  predictor.io.curr_pc := PC
  val PC_fifo = Module(new RegFifo(UInt(128.W), fifo_size))

  //Connect PC fifo
  PC_fifo.io.enq.bits := PC
  PC_fifo.io.enq.valid := cache.req.valid & cache.req.ready
  PC_fifo.io.deq.ready := cache.resp.valid & cache.resp.ready
  toDecode.pc := PC_fifo.io.deq.bits

  //fence.I
  val is_fenceI = (cache.resp.bits(6,2) === "b00011".U) & (cache.resp.bits(14,12) === 1.U) & (cache.resp.valid)
  when (handle_fenceI===1.U){
    PC_fifo.reset:=1.U
  }
  when (handle_fenceI === 0.U){
    handle_fenceI := is_fenceI
  }.otherwise{
    when (clear_cache_req===0.U & cache_cleared === 0.U & fence_pending===0.U){
      handle_fenceI := 0.U
    }
  }

  when (clear_cache_req===0.U & !handle_fenceI===1.U){
    clear_cache_req := is_fenceI
  }.elsewhen(updateAllCachelines.fired){
    clear_cache_req := 0.U
  }

  when(cache_cleared === 0.U & !handle_fenceI===1.U) {
    cache_cleared := is_fenceI
  }.elsewhen(cachelinesUpdatesResp.fired) {
    cache_cleared := 0.U
  }

  carryOutFence.ready := fence_pending

  when (fence_pending===0.U & !handle_fenceI===1.U){
    fence_pending:= is_fenceI
  }.elsewhen(carryOutFence.fired){
    fence_pending:=0.U
  }

  //redirect signal calc
  val redirect = Wire(Bool())
  redirect := !(toDecode.expected.pc === toDecode.pc) & toDecode.expected.valid

  //redirect bit logic
  when(redirect_bit===0.U & PC_fifo.io.deq.valid){
    redirect_bit := redirect
  }.elsewhen (PC_fifo.io.deq.valid === 0.U){
    redirect_bit := 0.U
  }


  //PC update logic
  when(redirect_bit===1.U) {
    PC := toDecode.expected.pc
  }.elsewhen(is_fenceI) {
    PC := PC_fifo.io.deq.bits + 4.U
  }.elsewhen(cache.req.valid & cache.req.ready) {
    PC := predictor.io.next_pc
  }
  cache.req.bits := PC

  //ready valid signal logic
  cache.req.valid := redirect_bit === 0.U & PC_fifo.io.enq.ready & !is_fenceI & !(handle_fenceI===1.U)
  cache.resp.ready := (redirect_bit===1.U || toDecode.fired) & !(handle_fenceI)
  updateAllCachelines.ready := clear_cache_req
  cachelinesUpdatesResp.ready := cache_cleared

  when (redirect || redirect_bit===1.U || !cache.resp.valid || !PC_fifo.io.deq.valid || is_fenceI || handle_fenceI===1.U){
    toDecode.ready := 0.B
  }.otherwise{
    toDecode.ready := 1.B
  }

  toDecode.instruction := cache.resp.bits
  printf(p"${cache} ${updateAllCachelines} ${cachelinesUpdatesResp} ${carryOutFence} ${fence_pending}\n")
}

object Verilog extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new fetch(4))
}