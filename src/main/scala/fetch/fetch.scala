import pipeline.fifo._
import pipeline.ports._
import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO
import firrtl.Utils.False

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
class predictor(val depth: Int) extends Module {
  val io = IO(new Bundle {
    val branchres = new branchResToFetch
    val curr_pc = Input(UInt(64.W))
    val next_pc = Output(UInt(64.W))
  })

  // Extract addresses and indexes
  val btb_addr = io.curr_pc(log2Down(depth) + 1, 2)
  val tag = io.curr_pc(63, log2Down(depth) + 2)
  val result_addr = io.branchres.pc(log2Down(depth) + 1, 2)
  val result_tag = io.branchres.pc(63, log2Down(depth) + 2)

  // Define tables and valid bits
  val btb = Mem(depth, UInt(64.W))
  val counters = Mem(depth, UInt(2.W))
  val valid_bits = RegInit(VecInit(Seq.fill(depth)(0.U(1.W))))
  val tag_store = Mem(depth, UInt((62-log2Down(depth)).W))

  // Update btb ,counters and valid bits
  when(io.branchres.fired){
    when(io.branchres.isBranch) {
      valid_bits(result_addr) := 1.U
      tag_store(result_addr) := result_tag
      btb(result_addr) := io.branchres.pcAfterBrnach
      // Update counters
      when(io.branchres.branchTaken){
        when(!(counters(result_addr) === 3.U)){
          counters(result_addr) := counters(result_addr) + 1.U
        }
      }.otherwise{
        when(!(counters(result_addr) === 0.U)) {
          counters(result_addr) := counters(result_addr) - 1.U
        }
      }
    }.otherwise{
      valid_bits(result_addr) := 0.U
    }
  }

  io.next_pc := Mux(valid_bits(btb_addr)===1.U && tag_store(btb_addr) === tag && counters(btb_addr)(1) === 1.U, btb(btb_addr), io.curr_pc + 4.U)

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

  //redirect signal calc
  val redirect = Wire(Bool())
  redirect := !(toDecode.expected.pc === toDecode.pc) & toDecode.expected.valid

  //register defs
  val PC = RegInit(0.U(64.W))
  val redirect_bit= RegInit(0.U(1.W))
  val handle_fenceI= RegInit(0.U(1.W))
  val clear_cache_req= RegInit(0.U(1.W))
  val cache_cleared= RegInit(0.U(1.W))
  val fence_pending= RegInit(0.U(1.W))


  // initialize BHT and fifo buffer
  val predictor = Module(new predictor(32))
  predictor.io.branchres <> branchRes
  predictor.io.curr_pc := PC
  val PC_fifo = Module(new regFifo(UInt(128.W), fifo_size))

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
    handle_fenceI := is_fenceI & toDecode.fired & !(redirect || redirect_bit ===1.U)
  }.otherwise{
    when (clear_cache_req===0.U & cache_cleared === 0.U & fence_pending===0.U){
      handle_fenceI := 0.U
    }
  }

  when (clear_cache_req===0.U & !handle_fenceI===1.U){
    clear_cache_req := is_fenceI & !(redirect || redirect_bit ===1.U) & toDecode.fired
  }.elsewhen(updateAllCachelines.fired){
    clear_cache_req := 0.U
  }

  when(cache_cleared === 0.U & !handle_fenceI===1.U) {
    cache_cleared := is_fenceI & !(redirect || redirect_bit ===1.U) & toDecode.fired
  }.elsewhen(cachelinesUpdatesResp.fired) {
    cache_cleared := 0.U
  }

  carryOutFence.ready := fence_pending

  when (fence_pending===0.U & !handle_fenceI===1.U){
    fence_pending:= is_fenceI & !(redirect || redirect_bit ===1.U) & toDecode.fired
  }.elsewhen(carryOutFence.fired){
    fence_pending:=0.U
  }


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

  when (redirect || redirect_bit===1.U || !cache.resp.valid || !PC_fifo.io.deq.valid || handle_fenceI === 1.U || handle_fenceI===1.U){
    toDecode.ready := 0.B
  }.otherwise{
    toDecode.ready := 1.B
  }

  toDecode.instruction := cache.resp.bits
  //printf(p"${cache} ${updateAllCachelines} ${cachelinesUpdatesResp} ${carryOutFence} ${fence_pending}\n")
}

object fetchVerilog extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new fetch(4))
}
