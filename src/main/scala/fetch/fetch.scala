import pipeline.fifo._
import pipeline.ports._
import pipeline.ports.ras_constants._
import chisel3._
import chisel3.util._
import pipeline.stack._
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

abstract class base_pred(val ras_depth: Int,val btb_depth: Int) extends Module{
  val io = IO(new Bundle {
    val branchres = new branchResToFetch
    val curr_pc = Input(UInt(64.W))
    val next_pc = Output(UInt(64.W))
  })

  val reqSent = IO(Input(Bool()))

  val misprediction = IO(Input(Bool()))

  // Extract addresses and indexes
  val btb_addr = io.curr_pc(log2Down(btb_depth) + 1, 2)
  val tag = io.curr_pc(63, log2Down(btb_depth) + 2)
  val result_addr = io.branchres.pc(log2Down(btb_depth) + 1, 2)
  val result_tag = io.branchres.pc(63, log2Down(btb_depth) + 2)

  // Define tables and valid bits
  val btb = Mem(btb_depth, UInt(64.W))
  val valid_bits = RegInit(VecInit(Seq.fill(btb_depth)(0.U(1.W))))
  val tag_store = Mem(btb_depth, UInt((62 - log2Down(btb_depth)).W))
  val is_ras = RegInit(VecInit(Seq.fill(btb_depth)(0.U(1.W))))
  val ras_actions = Mem(btb_depth, UInt(2.W))
  val ras = Module(new stack(UInt(64.W),ras_depth))

  //update btb valid bits and ras valid bits
  when(io.branchres.fired) {
    when(io.branchres.isBranch) {
      valid_bits(result_addr) := 1.U
      tag_store(result_addr) := result_tag
      btb(result_addr) := io.branchres.pcAfterBrnach
      when(io.branchres.isRas){
        is_ras(result_addr) := 1.U
        ras_actions(result_addr) := io.branchres.rasAction
      }
    }.otherwise {
      when(io.branchres.branchTaken) {
        valid_bits(result_addr) := 0.U
      }
    }
  }

  val btb_hit = valid_bits(btb_addr)===1.U && tag_store(btb_addr) === tag
  val ras_overide = Wire(Bool())
  ras.io.dataIn := io.curr_pc + 4.U


  when(is_ras(btb_addr) === 1.U && btb_hit) {
      when(ras_actions(btb_addr) === pop.U) {
        ras_overide := 1.B
        ras.io.valid := 1.B && reqSent
        ras.io.op := pop.U
      }.elsewhen(ras_actions(btb_addr) === push.U) {
        ras.io.valid := 1.B && reqSent
        ras.io.op := push.U
        ras_overide := 0.B
      }.otherwise{
        ras.io.valid := 1.B && reqSent
        ras.io.op := popThenPush.U
        ras_overide := 1.B
      }
  }.otherwise {
    ras.io.valid := 0.B
    ras.io.op := 0.U
    ras_overide := 0.B
  }

  io.branchres.ready := 1.B

  //debug
  val predRasAction = IO(Output(UInt(64.W)))
  predRasAction := ras_actions(btb_addr)
  val rasOveride = IO(Output(UInt(64.W)))
  rasOveride := ras_overide
  val rasLogicTrigger = IO(Output(UInt(64.W)))
  rasLogicTrigger := is_ras(btb_addr) === 1.U && btb_hit
  val btbVal = IO(Output(UInt(64.W)))
  btbVal := ras.sp_o
}

class bimodal_predictor(btb_depth: Int, ras_depth: Int) extends base_pred(ras_depth: Int, btb_depth: Int) {

  // counter table
  val counters = Mem(btb_depth, UInt(2.W))

  // Update counters
  when(io.branchres.fired){
      // Update counters
      when(io.branchres.branchTaken && !(counters(result_addr) === 3.U)){
        when(!(counters(result_addr) === 3.U)){
          counters(result_addr) := counters(result_addr) + 1.U
        }
      }.otherwise{
        when(!(counters(result_addr) === 0.U)) {
          counters(result_addr) := counters(result_addr) - 1.U
        }
      }
  }


  val btb_target = Mux(btb_hit && counters(btb_addr)(1) === 1.U, btb(btb_addr), io.curr_pc + 4.U)

  io.next_pc := Mux(ras_overide, ras.io.dataOut, btb_target)

  //debug signals
  val btbhitOut = IO(Output(Bool()))
  btbhitOut := btb_hit
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
  val predictor = Module(new bimodal_predictor(2048,64))
  predictor.io.branchres <> branchRes
  predictor.io.curr_pc := PC
  val PC_fifo = Module(new regFifo(UInt(128.W), fifo_size))

  //Connect PC fifo
  PC_fifo.io.enq.bits := PC
  PC_fifo.io.enq.valid := cache.req.valid & cache.req.ready
  PC_fifo.io.deq.ready := cache.resp.valid & cache.resp.ready
  toDecode.pc := PC_fifo.io.deq.bits

  //fence.I
  val is_fenceI = (toDecode.instruction(6,2) === "b00011".U) & (toDecode.instruction(14,13) === 0.U) & (toDecode.fired)
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

  when (redirect || redirect_bit===1.U || !cache.resp.valid || !PC_fifo.io.deq.valid || handle_fenceI===1.U){
    toDecode.ready := 0.B
  }.otherwise{
    toDecode.ready := 1.B
  }

  toDecode.instruction := cache.resp.bits


  // detect misprediction
  val misPredicted = RegInit(0.B)
  when (redirect_bit===0.U & PC_fifo.io.deq.valid){
    misPredicted := redirect
  }.otherwise{
    misPredicted := 0.B
  }

  predictor.misprediction := misPredicted
  predictor.reqSent := cache.req.valid && cache.req.ready


  //debug signals
  val misprediction = IO(Output(Bool()))
  misprediction := misPredicted
  val isabranch = IO(Output(Bool()))
  isabranch := branchRes.isBranch
  val resfired = IO(Output(Bool()))
  resfired := branchRes.fired
  val btbhit = IO(Output(Bool()))
  btbhit := predictor.btbhitOut
  val fetchsent = IO(Output(Bool()))
  fetchsent := cache.req.valid && cache.req.ready
  val resPC = IO(Output(UInt(64.W)))
  resPC := branchRes.pc
  val isras = IO(Output(Bool()))
  isras := branchRes.isRas
  val rasAction = IO(Output(UInt(64.W)))
  rasAction := branchRes.rasAction
  val curPC = IO(Output(UInt(64.W)))
  curPC := PC
  val predRasAction = IO(Output(UInt(64.W)))
  predRasAction := predictor.predRasAction
  val rasOveride = IO(Output(UInt(64.W)))
  rasOveride := predictor.rasOveride
  val rasLogicTrigger = IO(Output(UInt(64.W)))
  rasLogicTrigger := predictor.rasLogicTrigger
  val btbVal = IO(Output(UInt(64.W)))
  btbVal := predictor.btbVal

//  val branchOut = IO(Output(new Bundle() {
//    val fired = Bool()
//    val pc = UInt(64.W)
//    val isBranch = Bool()
//}))
//  branchOut.pc := branchRes.pc
//  branchOut.fired := branchRes.fired
//  branchOut.isBranch := branchRes.isBranch
  //printf(p"${cache} ${updateAllCachelines} ${cachelinesUpdatesResp} ${carryOutFence} ${fence_pending}\n")
}

object Verilog extends App {

  (new chisel3.stage.ChiselStage).emitVerilog(new fetch(64))
}

